package com.aegisone.broker

import com.aegisone.invariants.TestAuthorityDecisionChannel
import com.aegisone.invariants.TestReceiptChannel
import com.aegisone.receipt.Receipt
import com.aegisone.trust.Manifest
import com.aegisone.trust.TrustInit
import com.aegisone.invariants.TestZoneAStore
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P5 fail-closed authority decision write tests.
 *
 * Proves the broker enforces: "no grant or denial is considered complete unless
 * its authority decision record is durably written, or the operation fails closed."
 *
 * AD-1: GrantIssued write failure → grant blocked, PolicyViolation written.
 *        The capability was correctly authorized, but the decision record could
 *        not be durably written, so the grant is withheld (fail-closed).
 *
 * AD-2: GrantDenied write failure → denial still enforced, Anomaly written.
 *        The denial stands regardless of write outcome. The write failure is
 *        surfaced as an Anomaly so the operator knows there is a gap.
 *
 * AD-3: GrantIssued write succeeds → capability returned normally.
 *        Baseline: when the channel works, the grant flows through.
 *
 * AD-4: Channel fails mid-session — first grant succeeds, channel goes down,
 *        second grant blocked. Proves the check is per-call, not cached.
 *
 * AD-5: Denial write failure on GRANT_NOT_IN_MANIFEST path — denial stands,
 *        Anomaly written, PolicyViolation also present (the denial reason).
 */
class AuthorityDecisionFailClosedTest {

    private val KEY = byteArrayOf(1, 2, 3, 4)

    private fun testManifest() = Manifest(
        version = 1,
        createdAt = System.currentTimeMillis() - 1000,
        schemaValid = true,
        signature = KEY.copyOf(),
        grants = listOf(CapabilityGrant("WRITE_NOTE", AgentRole.EXECUTOR, GrantAuthority.SYSTEM_POLICY)),
        agents = mapOf("exec-01" to AgentRole.EXECUTOR)
    )

    private fun setupBroker(
        decisionChannel: TestAuthorityDecisionChannel,
        receiptChannel: TestReceiptChannel = TestReceiptChannel()
    ): Pair<CapabilityBroker, TestReceiptChannel> {
        val store = TestZoneAStore(manifest = testManifest())
        val trustInit = TrustInit(store)
        val signal = trustInit.verifyManifest()

        val broker = CapabilityBroker(
            receiptChannel = receiptChannel,
            trustedIssuer = trustInit.identity,
            authorityDecisionChannel = decisionChannel
        )
        assertTrue(broker.initialize(signal), "Broker must initialize successfully")
        assertEquals(BrokerState.ACTIVE, broker.state)
        return Pair(broker, receiptChannel)
    }

    // --- AD-1: GrantIssued write fails → grant blocked ---

    @Test
    fun `AD-1 GrantIssued write failure — grant blocked PolicyViolation written`() {
        val decisionChannel = TestAuthorityDecisionChannel(available = false)
        val (broker, receiptChannel) = setupBroker(decisionChannel)

        val result = broker.issueGrant("WRITE_NOTE", AgentRole.EXECUTOR, GrantAuthority.SYSTEM_POLICY)

        assertNull(result, "Grant must be blocked when authority decision write fails (fail-closed)")

        // PolicyViolation must identify the failure
        val violations = receiptChannel.receipts.filterIsInstance<Receipt.PolicyViolation>()
        val writeFailure = violations.find { it.violation == "AUTHORITY_WRITE_FAILED" }
        assertNotNull(writeFailure, "PolicyViolation with AUTHORITY_WRITE_FAILED must be written")
        assertTrue(writeFailure!!.detail.contains("WRITE_NOTE"),
            "PolicyViolation detail must identify the blocked capability")
        assertTrue(writeFailure.detail.contains("fail-closed"),
            "PolicyViolation detail must indicate fail-closed behavior")
    }

    // --- AD-2: GrantDenied write fails → denial stands, Anomaly surfaced ---

    @Test
    fun `AD-2 GrantDenied write failure — denial enforced Anomaly surfaces the write gap`() {
        val decisionChannel = TestAuthorityDecisionChannel(available = false)
        val (broker, receiptChannel) = setupBroker(decisionChannel)

        // Request a capability NOT in the manifest → denial path
        val result = broker.issueGrant("UNKNOWN_CAP", AgentRole.EXECUTOR, GrantAuthority.SYSTEM_POLICY)

        assertNull(result, "Denial must stand regardless of decision write outcome")

        // Anomaly must surface the write gap
        val anomalies = receiptChannel.receipts.filterIsInstance<Receipt.Anomaly>()
        val writeGap = anomalies.find { it.type == "DENIAL_RECORD_WRITE_FAILED" }
        assertNotNull(writeGap, "Anomaly with DENIAL_RECORD_WRITE_FAILED must be written")
        assertTrue(writeGap!!.detail.contains("UNKNOWN_CAP"),
            "Anomaly detail must identify the capability that was denied")
        assertTrue(writeGap.detail.contains("GRANT_NOT_IN_MANIFEST"),
            "Anomaly detail must identify the denial reason")

        // PolicyViolation for the actual denial must also be present
        val policyViolations = receiptChannel.receipts.filterIsInstance<Receipt.PolicyViolation>()
        assertTrue(policyViolations.any { it.violation == "GRANT_NOT_IN_MANIFEST" },
            "PolicyViolation for the denial reason must still be written")
    }

    // --- AD-3: GrantIssued write succeeds → normal flow ---

    @Test
    fun `AD-3 GrantIssued write succeeds — capability returned normally`() {
        val decisionChannel = TestAuthorityDecisionChannel(available = true)
        val (broker, receiptChannel) = setupBroker(decisionChannel)

        val result = broker.issueGrant("WRITE_NOTE", AgentRole.EXECUTOR, GrantAuthority.SYSTEM_POLICY)

        assertNotNull(result, "Grant must succeed when authority decision write succeeds")
        assertEquals("WRITE_NOTE", result!!.capabilityName)
        assertEquals(1, result.manifestVersion)

        // Decision must be recorded
        assertEquals(1, decisionChannel.grantIssued().size, "GrantIssued must be recorded")

        // No PolicyViolation or Anomaly — clean path
        val violations = receiptChannel.receipts.filterIsInstance<Receipt.PolicyViolation>()
        assertTrue(violations.isEmpty(), "No PolicyViolation on clean grant path")
        val anomalies = receiptChannel.receipts.filterIsInstance<Receipt.Anomaly>()
        assertTrue(anomalies.isEmpty(), "No Anomaly on clean grant path")
    }

    // --- AD-4: Channel fails mid-session ---

    @Test
    fun `AD-4 channel fails mid-session — first grant succeeds second blocked`() {
        val decisionChannel = TestAuthorityDecisionChannel(available = true)
        val (broker, _) = setupBroker(decisionChannel)

        // First grant: channel works → succeeds
        val first = broker.issueGrant("WRITE_NOTE", AgentRole.EXECUTOR, GrantAuthority.SYSTEM_POLICY)
        assertNotNull(first, "First grant must succeed with working channel")

        // Channel goes down
        decisionChannel.available = false

        // Second grant: channel broken → blocked
        val second = broker.issueGrant("WRITE_NOTE", AgentRole.EXECUTOR, GrantAuthority.SYSTEM_POLICY)
        assertNull(second, "Second grant must be blocked when channel fails (per-call check)")
    }

    // --- AD-5: Denial write failure on GRANT_NOT_IN_MANIFEST path ---

    @Test
    fun `AD-5 GRANT_BEFORE_INIT denial with write failure — denial stands Anomaly written`() {
        val decisionChannel = TestAuthorityDecisionChannel(available = false)
        val receiptChannel = TestReceiptChannel()

        // Create broker without initializing — UNINITIALIZED state
        val store = TestZoneAStore()
        val trustInit = TrustInit(store)
        val broker = CapabilityBroker(
            receiptChannel = receiptChannel,
            trustedIssuer = trustInit.identity,
            authorityDecisionChannel = decisionChannel
        )
        assertEquals(BrokerState.UNINITIALIZED, broker.state)

        val result = broker.issueGrant("WRITE_NOTE", AgentRole.EXECUTOR, GrantAuthority.SYSTEM_POLICY)
        assertNull(result, "Grant must be denied in UNINITIALIZED state")

        // PolicyViolation for GRANT_BEFORE_INIT
        val violations = receiptChannel.receipts.filterIsInstance<Receipt.PolicyViolation>()
        assertTrue(violations.any { it.violation == "GRANT_BEFORE_INIT" },
            "PolicyViolation for GRANT_BEFORE_INIT must be written")

        // Anomaly for the failed decision write
        val anomalies = receiptChannel.receipts.filterIsInstance<Receipt.Anomaly>()
        assertTrue(anomalies.any { it.type == "DENIAL_RECORD_WRITE_FAILED" },
            "Anomaly must surface the failed denial write")
    }
}
