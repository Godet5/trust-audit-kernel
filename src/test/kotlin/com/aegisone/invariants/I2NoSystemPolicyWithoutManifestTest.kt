// INVARIANT TEST — DO NOT MODIFY WITHOUT SPEC CHANGE (implementationMap-trust-and-audit-v1.md §6)
// Modified per spec change: §2.2.2 (TrustInitSignalPolicy) — Broker now requires TrustInitIdentity;
// initialize() accepts BootSignal with origin/sequence/version checks enforced.
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I-2: No SYSTEM_POLICY grants without a verified, non-rolled-back manifest.
 *
 * Source: implementationMap-trust-and-audit-v1.md §6, I-2 tests
 */
class I2NoSystemPolicyWithoutManifestTest {

    // Shared setup: produce an ACTIVE broker from a legitimate TrustInit.
    private fun activeBrokerSetup(
        receiptChannel: TestReceiptChannel,
        floor: MutableVersionFloorProvider? = null
    ): Pair<CapabilityBroker, TrustInit> {
        val key = byteArrayOf(1, 2, 3, 4)
        val trustInit = TrustInit(TestZoneAStore(manifest = TestManifests.valid(key = key), platformKey = key))
        val broker = CapabilityBroker(receiptChannel, trustInit.identity, floor)
        val signal = trustInit.verifyManifest()
        assert(signal is BootSignal.Verified)
        broker.initialize(signal)
        assertEquals(BrokerState.ACTIVE, broker.state)
        return Pair(broker, trustInit)
    }

    // ---------------------------------------------------------------
    // I2-T1: Broker in RESTRICTED state: SYSTEM_POLICY grant attempt
    //        → BLOCK; PolicyViolationReceipt
    // ---------------------------------------------------------------
    @Test
    @DisplayName("I2-T1: SYSTEM_POLICY grant blocked in RESTRICTED state with PolicyViolationReceipt")
    fun systemPolicyBlockedInRestrictedState() {
        val receiptChannel = TestReceiptChannel()
        val trustInit = TrustInit(TestZoneAStore(manifest = TestManifests.signatureInvalid()))
        val broker = CapabilityBroker(receiptChannel, trustInit.identity)

        val signal = trustInit.verifyManifest()
        assert(signal is BootSignal.Failed)
        broker.initialize(signal)
        assertEquals(BrokerState.RESTRICTED, broker.state)

        val grant = broker.issueGrant(
            capabilityName = "AGENT_SPAWN",
            targetRole = AgentRole.PLANNER,
            authority = GrantAuthority.SYSTEM_POLICY
        )

        assertNull(grant, "I-2 violation: SYSTEM_POLICY grant issued in RESTRICTED state")

        val violations = receiptChannel.policyViolations("SYSTEM_POLICY_UNAVAILABLE")
        assertEquals(1, violations.size,
            "Expected exactly one SYSTEM_POLICY_UNAVAILABLE policy violation receipt")
        assertTrue(violations[0].detail.contains("RESTRICTED"),
            "Receipt detail must reference the RESTRICTED state")

        val userGrant = broker.issueGrant(
            capabilityName = "FILE_READ",
            targetRole = AgentRole.HELPER_SPECIALIST,
            authority = GrantAuthority.USER_DIRECT
        )
        assertNotNull(userGrant, "USER_DIRECT grants must be permitted in RESTRICTED state")
    }

    // ---------------------------------------------------------------
    // I2-T2: Broker in ACTIVE state: SYSTEM_POLICY grant for manifest-unlisted agent
    //        → BLOCK; PolicyViolationReceipt (ROLE_NOT_IN_MANIFEST)
    // ---------------------------------------------------------------
    @Test
    @DisplayName("I2-T2: SYSTEM_POLICY grant blocked for role not in manifest enumeration")
    fun systemPolicyBlockedForUnlistedRole() {
        val receiptChannel = TestReceiptChannel()
        val (broker, _) = activeBrokerSetup(receiptChannel)

        // EXECUTOR not in manifest — valid() only enumerates PLANNER
        val grant = broker.issueGrant(
            capabilityName = "AGENT_SPAWN",
            targetRole = AgentRole.EXECUTOR,
            authority = GrantAuthority.SYSTEM_POLICY
        )

        assertNull(grant, "I-2 violation: SYSTEM_POLICY grant issued for role not in manifest")

        val violations = receiptChannel.policyViolations("ROLE_NOT_IN_MANIFEST")
        assertEquals(1, violations.size,
            "Expected exactly one ROLE_NOT_IN_MANIFEST policy violation receipt")
        assertTrue(violations[0].detail.contains("EXECUTOR"),
            "Receipt detail must name the rejected role")

        val validGrant = broker.issueGrant(
            capabilityName = "AGENT_SPAWN",
            targetRole = AgentRole.PLANNER,
            authority = GrantAuthority.SYSTEM_POLICY
        )
        assertNotNull(validGrant,
            "SYSTEM_POLICY grant for manifest-enumerated role must succeed in ACTIVE state")
    }

    // ---------------------------------------------------------------
    // I2-T3: Broker ACTIVE; manifest_version < floor at grant time
    //        → BLOCK; floor re-checked at grant issuance; broker enters RESTRICTED
    // ---------------------------------------------------------------
    @Test
    @DisplayName("I2-T3: SYSTEM_POLICY grant blocked when manifest version falls below updated floor")
    fun systemPolicyBlockedWhenManifestVersionFallsBelowFloor() {
        val receiptChannel = TestReceiptChannel()
        val floor = MutableVersionFloorProvider(currentFloor = 1)

        val key = byteArrayOf(1, 2, 3, 4)
        val trustInit = TrustInit(TestZoneAStore(
            manifest = TestManifests.valid(version = 1, key = key),
            platformKey = key,
            versionFloor = 1
        ))
        val broker = CapabilityBroker(receiptChannel, trustInit.identity, floor)
        broker.initialize(trustInit.verifyManifest())
        assertEquals(BrokerState.ACTIVE, broker.state)

        // Floor updated externally — manifest version 1 is now below floor 2
        floor.currentFloor = 2

        val grant = broker.issueGrant(
            capabilityName = "AGENT_SPAWN",
            targetRole = AgentRole.PLANNER,
            authority = GrantAuthority.SYSTEM_POLICY
        )

        assertNull(grant, "I-2 violation: SYSTEM_POLICY grant issued with manifest below floor")
        assertEquals(BrokerState.RESTRICTED, broker.state,
            "Broker must enter RESTRICTED when verified manifest version falls below floor")

        val violations = receiptChannel.policyViolations("MANIFEST_VERSION_BELOW_FLOOR")
        assertEquals(1, violations.size)
        assertTrue(violations[0].detail.contains("1"))
        assertTrue(violations[0].detail.contains("2"))
    }

    // ---------------------------------------------------------------
    // I2-T4: Broker ACTIVE; AGENT_SPAWN for HELPER_SPECIALIST via SYSTEM_POLICY
    //        → BLOCK (HELPER_SPECIALIST is USER_DIRECT path only)
    // ---------------------------------------------------------------
    @Test
    @DisplayName("I2-T4: SYSTEM_POLICY cannot spawn HELPER_SPECIALIST — USER_DIRECT path only")
    fun systemPolicyCannotSpawnHelperSpecialist() {
        val receiptChannel = TestReceiptChannel()
        val (broker, _) = activeBrokerSetup(receiptChannel)

        val grant = broker.issueGrant(
            capabilityName = "AGENT_SPAWN",
            targetRole = AgentRole.HELPER_SPECIALIST,
            authority = GrantAuthority.SYSTEM_POLICY
        )

        assertNull(grant, "I-2 violation: SYSTEM_POLICY spawned HELPER_SPECIALIST")

        val violations = receiptChannel.policyViolations("ROLE_NOT_IN_MANIFEST")
        assertEquals(1, violations.size)

        val userGrant = broker.issueGrant(
            capabilityName = "AGENT_SPAWN",
            targetRole = AgentRole.HELPER_SPECIALIST,
            authority = GrantAuthority.USER_DIRECT
        )
        assertNotNull(userGrant, "USER_DIRECT AGENT_SPAWN for HELPER_SPECIALIST must succeed")
        assertEquals(GrantAuthority.USER_DIRECT, userGrant.authority)
    }

    // ---------------------------------------------------------------
    // I2-T5: Forged/replayed TrustInit signal
    //        → BLOCK; anomaly receipt; Broker state unchanged
    // ---------------------------------------------------------------
    @Test
    @DisplayName("I2-T5: Forged, replayed, and version-regression signals rejected with anomaly receipt")
    fun forgedOrReplayedSignalRejected() {
        val key = byteArrayOf(1, 2, 3, 4)

        // --- Part A: Forged origin ---
        // Attacker's TrustInit has a different identity than the one the Broker trusts.
        val legitimateTrustInit = TrustInit(TestZoneAStore(manifest = TestManifests.valid(key = key), platformKey = key))
        val impostorTrustInit  = TrustInit(TestZoneAStore(manifest = TestManifests.valid(key = key), platformKey = key))

        val receiptA = TestReceiptChannel()
        val broker = CapabilityBroker(receiptA, legitimateTrustInit.identity)

        val forgedSignal = impostorTrustInit.verifyManifest()
        assert(forgedSignal is BootSignal.Verified)

        val accepted = broker.initialize(forgedSignal)

        assertFalse(accepted, "Forged signal (wrong issuer) must be rejected")
        assertEquals(BrokerState.UNINITIALIZED, broker.state,
            "Broker state must not change on forged signal")
        assertEquals(1, receiptA.anomalies("FORGED_TRUSTINIT_SIGNAL").size,
            "Expected FORGED_TRUSTINIT_SIGNAL anomaly receipt")

        // --- Part B: Replayed signal (stale sequence number) ---
        val mutableStore = MutableTestZoneAStore(
            manifest = TestManifests.valid(key = key), platformKey = key
        )
        val singleTrustInit = TrustInit(mutableStore)
        val receiptB = TestReceiptChannel()
        val freshBroker = CapabilityBroker(receiptB, singleTrustInit.identity)

        val signal1 = singleTrustInit.verifyManifest() as BootSignal.Verified  // seq=1
        val signal2 = singleTrustInit.verifyManifest() as BootSignal.Verified  // seq=2

        // Accept the newer signal first
        freshBroker.initialize(signal2)
        assertEquals(BrokerState.ACTIVE, freshBroker.state)

        // Replay the older signal — must be rejected
        receiptB.clear()
        val replayAccepted = freshBroker.initialize(signal1)

        assertFalse(replayAccepted, "Replayed signal (stale sequence) must be rejected")
        assertEquals(BrokerState.ACTIVE, freshBroker.state,
            "Broker state must not degrade on replay attempt")
        assertEquals(1, receiptB.anomalies("REPLAYED_SIGNAL").size,
            "Expected REPLAYED_SIGNAL anomaly receipt")
        assertTrue(receiptB.anomalies("REPLAYED_SIGNAL")[0].detail.contains("1"),
            "Receipt must reference the stale sequence number")

        // --- Part C: Version regression ---
        val keyV2 = byteArrayOf(5, 6, 7, 8)
        val mutableStoreC = MutableTestZoneAStore(
            manifest = TestManifests.valid(version = 2, key = keyV2), platformKey = keyV2
        )
        val trustInitC = TrustInit(mutableStoreC)
        val receiptC = TestReceiptChannel()
        val brokerC = CapabilityBroker(receiptC, trustInitC.identity)

        // Accept version=2
        val signalV2 = trustInitC.verifyManifest() as BootSignal.Verified
        brokerC.initialize(signalV2)
        assertEquals(BrokerState.ACTIVE, brokerC.state)

        // Construct a version-regression signal: same trusted issuer, valid sequence,
        // but lower manifest version. Models Zone A rollback attack.
        val regressionSignal = BootSignal.Verified(
            manifestVersion = 1,
            grantList = signalV2.grantList,
            agentEnumeration = signalV2.agentEnumeration,
            sequenceNumber = signalV2.sequenceNumber + 1,  // valid sequence
            issuer = trustInitC.identity                    // valid issuer
        )
        val regAccepted = brokerC.initialize(regressionSignal)

        assertFalse(regAccepted, "Version regression signal must be rejected")
        assertEquals(BrokerState.ACTIVE, brokerC.state,
            "Broker state must not change on version regression signal")
        assertEquals(1, receiptC.anomalies("MANIFEST_VERSION_REGRESSION").size,
            "Expected MANIFEST_VERSION_REGRESSION anomaly receipt")
    }
}
