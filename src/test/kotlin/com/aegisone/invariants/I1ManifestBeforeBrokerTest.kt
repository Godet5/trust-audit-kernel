// INVARIANT TEST — DO NOT MODIFY WITHOUT SPEC CHANGE (implementationMap-trust-and-audit-v1.md §6)
// Modified per spec change: §2.2.2 (TrustInitSignalPolicy) — BootSignal replaces ManifestVerificationResult;
// Broker now requires TrustInitIdentity and accepts only authenticated BootSignal objects.
package com.aegisone.invariants

import com.aegisone.broker.AgentRole
import com.aegisone.broker.BrokerState
import com.aegisone.broker.CapabilityBroker
import com.aegisone.broker.GrantAuthority
import com.aegisone.trust.BootSignal
import com.aegisone.trust.TrustInit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * I-1: Manifest verification precedes Broker initialization.
 *
 * The Capability Broker cannot initialize until ManifestVerifier confirms:
 * (a) schema valid, (b) signature valid against Zone A key,
 * (c) version >= version_floor, (d) manifest cleared from runtime memory.
 *
 * Source: implementationMap-trust-and-audit-v1.md §6, I-1 tests
 */
class I1ManifestBeforeBrokerTest {

    // ---------------------------------------------------------------
    // I1-T1: Broker.initialize() called before TrustInit signal
    //        → Broker returns ERROR_NOT_READY; no grants issued
    // ---------------------------------------------------------------
    @Test
    @DisplayName("I1-T1: Broker rejects all grants when uninitialized (no TrustInit signal)")
    fun brokerRejectsGrantsBeforeTrustInitSignal() {
        val trustInit = TrustInit(TestZoneAStore())  // identity only; no verification called
        val broker = CapabilityBroker(TestReceiptChannel(), trustInit.identity)

        assertEquals(BrokerState.UNINITIALIZED, broker.state,
            "Broker must start in UNINITIALIZED state")

        val userGrant = broker.issueGrant(
            capabilityName = "FILE_READ",
            targetRole = AgentRole.HELPER_SPECIALIST,
            authority = GrantAuthority.USER_DIRECT
        )
        assertNull(userGrant,
            "I-1 violation: USER_DIRECT grant issued while Broker is UNINITIALIZED")

        val systemGrant = broker.issueGrant(
            capabilityName = "AGENT_SPAWN",
            targetRole = AgentRole.PLANNER,
            authority = GrantAuthority.SYSTEM_POLICY
        )
        assertNull(systemGrant,
            "I-1 violation: SYSTEM_POLICY grant issued while Broker is UNINITIALIZED")

        assertEquals(BrokerState.UNINITIALIZED, broker.state,
            "Broker state must remain UNINITIALIZED after rejected grant attempts")
    }

    // ---------------------------------------------------------------
    // I1-T2: TrustInit fails at signature step
    //        → Broker never reaches ACTIVE; ManifestFailureReceipt in Zone D
    // ---------------------------------------------------------------
    @Test
    @DisplayName("I1-T2: Signature failure prevents Broker from reaching ACTIVE state")
    fun signatureFailurePreventsActiveState() {
        val store = TestZoneAStore(manifest = TestManifests.signatureInvalid())
        val trustInit = TrustInit(store)
        val broker = CapabilityBroker(TestReceiptChannel(), trustInit.identity)

        val signal = trustInit.verifyManifest()

        assert(signal is BootSignal.Failed) { "Expected BootSignal.Failed for invalid signature" }
        assertEquals("SIGNATURE_INVALID", (signal as BootSignal.Failed).reason)

        broker.initialize(signal)

        assertEquals(BrokerState.RESTRICTED, broker.state,
            "Broker must be RESTRICTED after signature verification failure")

        val grant = broker.issueGrant(
            capabilityName = "AGENT_SPAWN",
            targetRole = AgentRole.PLANNER,
            authority = GrantAuthority.SYSTEM_POLICY
        )
        assertNull(grant,
            "I-1/I-2 violation: SYSTEM_POLICY grant issued in RESTRICTED state")
    }

    // ---------------------------------------------------------------
    // I1-T3: Boot success — verify manifest cleared from runtime after phase_7
    //        → Canary field must not appear in derived runtime data
    // ---------------------------------------------------------------
    @Test
    @DisplayName("I1-T3: Verified result contains only derived data, not raw manifest fields")
    fun verifiedResultContainsOnlyDerivedData() {
        val canaryKey = byteArrayOf(1, 2, 3, 4)
        val store = TestZoneAStore(
            manifest = TestManifests.valid(key = canaryKey),
            platformKey = canaryKey
        )
        val trustInit = TrustInit(store)

        val signal = trustInit.verifyManifest()
        assert(signal is BootSignal.Verified) { "Expected successful verification" }

        val verified = signal as BootSignal.Verified

        // BootSignal.Verified must contain ONLY:
        //   manifestVersion, grantList, agentEnumeration, sequenceNumber, issuer
        // It must NOT contain: manifest_signature, raw manifest body, zone A key material.
        // Structural proof: if someone adds a raw manifest field to BootSignal.Verified,
        // this test must be updated to reject it.
        val fields = verified::class.java.declaredFields
            .filter { !it.isSynthetic }
            .map { it.name }
            .toSet()

        val permitted = setOf("manifestVersion", "grantList", "agentEnumeration",
                               "sequenceNumber", "issuer")
        assertEquals(permitted, fields,
            "Verified signal contains fields beyond the permitted set: ${fields - permitted}")
    }

    // ---------------------------------------------------------------
    // I1-T4: Boot success — re-verification request
    //        → TrustInit re-reads Zone A; Broker uses only TrustInit output
    // ---------------------------------------------------------------
    @Test
    @DisplayName("I1-T4: Re-verification reads fresh from Zone A, not from cache")
    fun reVerificationReadsFreshFromZoneA() {
        val keyV1 = byteArrayOf(1, 2, 3, 4)
        val mutableStore = MutableTestZoneAStore(
            manifest = TestManifests.valid(version = 1, key = keyV1),
            platformKey = keyV1
        )

        // Single TrustInit — re-verification calls the same instance with updated Zone A
        val trustInit = TrustInit(mutableStore)
        val receiptChannel = TestReceiptChannel()
        val broker = CapabilityBroker(receiptChannel, trustInit.identity)

        // First verification — version 1
        val signal1 = trustInit.verifyManifest()
        assert(signal1 is BootSignal.Verified)
        assertEquals(1, (signal1 as BootSignal.Verified).manifestVersion)
        broker.initialize(signal1)
        assertEquals(BrokerState.ACTIVE, broker.state)

        // Zone A updated — version 2
        val keyV2 = byteArrayOf(5, 6, 7, 8)
        mutableStore.manifest = TestManifests.valid(version = 2, key = keyV2)
        mutableStore.platformKey = keyV2

        // Re-verification: same TrustInit reads fresh from (updated) Zone A
        val signal2 = trustInit.verifyManifest()
        assert(signal2 is BootSignal.Verified)
        assertEquals(2, (signal2 as BootSignal.Verified).manifestVersion,
            "Re-verification must reflect updated manifest from Zone A, not cached data")

        broker.initialize(signal2)
        assertEquals(BrokerState.ACTIVE, broker.state)

        val grant = broker.issueGrant(
            capabilityName = "AGENT_SPAWN",
            targetRole = AgentRole.PLANNER,
            authority = GrantAuthority.SYSTEM_POLICY
        )
        assertEquals(2, grant?.manifestVersion,
            "Broker must use manifest version from latest TrustInit verification")
    }
}
