package com.aegisone.broker

import com.aegisone.boot.SystemEvent
import com.aegisone.boot.SystemEventChannel
import com.aegisone.receipt.Receipt
import com.aegisone.receipt.ReceiptChannel
import com.aegisone.trust.BootSignal
import com.aegisone.trust.TrustInitIdentity
import com.aegisone.trust.VersionFloorProvider

/**
 * The Capability Broker issues, validates, and revokes Capability objects.
 * It does not execute actions; it authorizes them.
 *
 * State machine:
 *   UNINITIALIZED → ACTIVE     (on accepted BootSignal.Verified from trusted issuer)
 *   UNINITIALIZED → RESTRICTED (on BootSignal.Failed)
 *   ACTIVE → RESTRICTED        (on stale manifest detection at grant time)
 *   any state → RESTRICTED     (on broker restart)
 *
 * Signal validation enforced on every Verified signal:
 *   1. issuer === trustedIssuer            (origin check)
 *   2. sequenceNumber > lastAcceptedSeq    (replay resistance)
 *   3. manifestVersion >= currentVersion   (version non-regression)
 *
 * Traces to: I-1, I-2
 * Source: implementationMap-trust-and-audit-v1.md §2, §2.2.1, §2.2.2
 */
class CapabilityBroker(
    private val receiptChannel: ReceiptChannel,
    private val trustedIssuer: TrustInitIdentity,
    private val versionFloorProvider: VersionFloorProvider? = null,
    private val authorityDecisionChannel: AuthorityDecisionChannel,
    private val systemEventChannel: SystemEventChannel? = null
) {
    var state: BrokerState = BrokerState.UNINITIALIZED
        private set

    private var verifiedManifestVersion: Int? = null
    private var grantList: List<CapabilityGrant> = emptyList()
    private var agentEnumeration: Map<String, AgentRole> = emptyMap()
    private var lastAcceptedSequence: Int = -1

    /**
     * Accept a BootSignal from TrustInit. Enforces origin, replay, and version checks
     * before transitioning to ACTIVE. Failed signals force RESTRICTED unconditionally.
     *
     * Returns true if the signal was accepted (state → ACTIVE).
     * Returns false if rejected or if the signal is Failed (state → RESTRICTED or unchanged).
     */
    fun initialize(signal: BootSignal): Boolean {
        return when (signal) {
            is BootSignal.Failed -> {
                state = BrokerState.RESTRICTED
                false
            }
            is BootSignal.Verified -> acceptVerifiedSignal(signal)
        }
    }

    private fun acceptVerifiedSignal(signal: BootSignal.Verified): Boolean {
        // Check 1: Origin — signal must come from the trusted TrustInit instance
        if (signal.issuer !== trustedIssuer) {
            receiptChannel.write(Receipt.Anomaly(
                type = "FORGED_TRUSTINIT_SIGNAL",
                detail = "Signal issuer does not match trusted TrustInit identity; " +
                         "signal rejected, state unchanged"
            ))
            return false
        }

        // Check 2: Sequence — must be strictly greater than last accepted
        if (signal.sequenceNumber <= lastAcceptedSequence) {
            receiptChannel.write(Receipt.Anomaly(
                type = "REPLAYED_SIGNAL",
                detail = "Signal sequence ${signal.sequenceNumber} <= " +
                         "last accepted $lastAcceptedSequence; replay rejected"
            ))
            return false
        }

        // Check 3: Version non-regression — cannot accept a lower manifest version
        val currentVersion = verifiedManifestVersion
        if (currentVersion != null && signal.manifestVersion < currentVersion) {
            receiptChannel.write(Receipt.Anomaly(
                type = "MANIFEST_VERSION_REGRESSION",
                detail = "Signal manifest version ${signal.manifestVersion} < " +
                         "current verified version $currentVersion"
            ))
            return false
        }

        // All checks passed — accept signal
        lastAcceptedSequence = signal.sequenceNumber
        verifiedManifestVersion = signal.manifestVersion
        grantList = signal.grantList
        agentEnumeration = signal.agentEnumeration
        state = BrokerState.ACTIVE
        return true
    }

    /**
     * Attempt to issue a grant. Enforces I-2: no SYSTEM_POLICY grants unless
     * Broker is ACTIVE with a verified, current manifest.
     *
     * P5 fail-closed contract:
     *   - GrantIssued: decision record must be durably written before capability is returned.
     *     If the write fails, the grant is blocked — the system did the right thing but
     *     cannot prove it, so the outcome is withheld.
     *   - GrantDenied: the denial stands regardless of write outcome. Write failure is
     *     surfaced as an Anomaly to the receipt channel — the operator must never be left
     *     with missing authority history for a real outcome.
     *
     * Returns the issued capability on success, null on policy violation or write failure.
     */
    fun issueGrant(
        capabilityName: String,
        targetRole: AgentRole,
        authority: GrantAuthority
    ): IssuedCapability? {
        // capturedFloor: the floor re-read during this grant attempt (SYSTEM_POLICY only).
        // Carried to all subsequent denial/success records for full attribution.
        var capturedFloor: Int? = null

        if (state == BrokerState.UNINITIALIZED) {
            receiptChannel.write(Receipt.PolicyViolation(
                violation = "GRANT_BEFORE_INIT",
                detail = "Grant attempt while Broker is UNINITIALIZED: $capabilityName for $targetRole via $authority"
            ))
            recordDenial(AuthorityDecision.GrantDenied(
                capabilityName = capabilityName, targetRole = targetRole, authority = authority,
                reason = "GRANT_BEFORE_INIT", manifestVersion = null, floorVersion = null
            ))
            return null
        }

        if (authority == GrantAuthority.SYSTEM_POLICY && state != BrokerState.ACTIVE) {
            receiptChannel.write(Receipt.PolicyViolation(
                violation = "SYSTEM_POLICY_UNAVAILABLE",
                detail = "SYSTEM_POLICY grant attempt in $state state: $capabilityName for $targetRole"
            ))
            recordDenial(AuthorityDecision.GrantDenied(
                capabilityName = capabilityName, targetRole = targetRole, authority = authority,
                reason = "SYSTEM_POLICY_UNAVAILABLE",
                manifestVersion = verifiedManifestVersion, floorVersion = null
            ))
            return null
        }

        // Re-check version floor at grant time for SYSTEM_POLICY
        if (authority == GrantAuthority.SYSTEM_POLICY && versionFloorProvider != null) {
            val manifestVersion = verifiedManifestVersion ?: 0
            val floor = versionFloorProvider.currentFloor()
            capturedFloor = floor
            if (manifestVersion < floor) {
                receiptChannel.write(Receipt.PolicyViolation(
                    violation = "MANIFEST_VERSION_BELOW_FLOOR",
                    detail = "Verified manifest version $manifestVersion is below current floor $floor; " +
                             "SYSTEM_POLICY grants suspended"
                ))
                recordDenial(AuthorityDecision.GrantDenied(
                    capabilityName = capabilityName, targetRole = targetRole, authority = authority,
                    reason = "MANIFEST_VERSION_BELOW_FLOOR",
                    manifestVersion = verifiedManifestVersion, floorVersion = floor
                ))
                val previousState = state
                state = BrokerState.RESTRICTED
                systemEventChannel?.emit(SystemEvent.BrokerStateChanged(
                    fromState = previousState.name,
                    toState = BrokerState.RESTRICTED.name,
                    manifestVersion = verifiedManifestVersion,
                    reason = "MANIFEST_VERSION_BELOW_FLOOR: manifest $manifestVersion < floor $floor"
                ))
                return null
            }
        }

        if (authority == GrantAuthority.SYSTEM_POLICY) {
            if (targetRole !in agentEnumeration.values) {
                receiptChannel.write(Receipt.PolicyViolation(
                    violation = "ROLE_NOT_IN_MANIFEST",
                    detail = "SYSTEM_POLICY grant for role $targetRole not in manifest agent enumeration"
                ))
                recordDenial(AuthorityDecision.GrantDenied(
                    capabilityName = capabilityName, targetRole = targetRole, authority = authority,
                    reason = "ROLE_NOT_IN_MANIFEST",
                    manifestVersion = verifiedManifestVersion, floorVersion = capturedFloor
                ))
                return null
            }
        }

        if (authority == GrantAuthority.SYSTEM_POLICY) {
            val matchingGrant = grantList.any {
                it.capabilityName == capabilityName && it.targetRole == targetRole
            }
            if (!matchingGrant) {
                receiptChannel.write(Receipt.PolicyViolation(
                    violation = "GRANT_NOT_IN_MANIFEST",
                    detail = "SYSTEM_POLICY grant ($capabilityName, $targetRole) not found in manifest grant list"
                ))
                recordDenial(AuthorityDecision.GrantDenied(
                    capabilityName = capabilityName, targetRole = targetRole, authority = authority,
                    reason = "GRANT_NOT_IN_MANIFEST",
                    manifestVersion = verifiedManifestVersion, floorVersion = capturedFloor
                ))
                return null
            }
        }

        // All checks passed — attempt to record the issued decision durably.
        // P5: if the record write fails, the grant is blocked. The capability was
        // correctly authorized but cannot be proven, so we fail closed.
        val decision = AuthorityDecision.GrantIssued(
            capabilityName = capabilityName, targetRole = targetRole, authority = authority,
            manifestVersion = verifiedManifestVersion, floorVersion = capturedFloor
        )
        if (!authorityDecisionChannel.record(decision)) {
            receiptChannel.write(Receipt.PolicyViolation(
                violation = "AUTHORITY_WRITE_FAILED",
                detail = "Grant of $capabilityName for $targetRole via $authority was authorized " +
                         "but decision record write failed; grant blocked (fail-closed)"
            ))
            return null
        }

        return IssuedCapability(
            capabilityName = capabilityName,
            targetRole = targetRole,
            authority = authority,
            manifestVersion = verifiedManifestVersion
        )
    }

    /**
     * Record a denial decision. If the write fails, the denial still stands —
     * but the write failure is surfaced as an Anomaly so the operator knows
     * there is a gap in the authority decision history.
     */
    private fun recordDenial(denial: AuthorityDecision.GrantDenied) {
        if (!authorityDecisionChannel.record(denial)) {
            receiptChannel.write(Receipt.Anomaly(
                type = "DENIAL_RECORD_WRITE_FAILED",
                detail = "GrantDenied for ${denial.capabilityName} (reason=${denial.reason}) " +
                         "could not be written to authority_decisions; denial still enforced"
            ))
        }
    }
}

enum class BrokerState {
    UNINITIALIZED,
    RESTRICTED,
    ACTIVE
}

data class IssuedCapability(
    val capabilityName: String,
    val targetRole: AgentRole,
    val authority: GrantAuthority,
    val manifestVersion: Int?
)
