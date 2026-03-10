// INVARIANT TEST — DO NOT MODIFY WITHOUT SPEC CHANGE (implementationMap-trust-and-audit-v1.md §6)
package com.aegisone.invariants

import com.aegisone.review.ArtifactState
import com.aegisone.review.MemorySteward
import com.aegisone.review.ReviewWriteRequest
import com.aegisone.review.StewardResult
import com.aegisone.receipt.Receipt
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * I-4: No review write is applied unless all 7 MEMORY_STEWARD validation steps pass.
 * Failure at any step rejects the write. Steps are not reordered.
 *
 * Source: implementationMap-trust-and-audit-v1.md §4.3, §6 I-4 tests
 */
class I4ReviewWriteValidationTest {

    // ---------------------------------------------------------------
    // I4-T1: Write with forged token (signature invalid)
    //        → Rejected at step_1; POLICY_VIOLATION receipt written
    // ---------------------------------------------------------------
    @Test
    @DisplayName("I4-T1: Write rejected at step_1 when token signature is invalid")
    fun writeRejectedOnInvalidTokenSignature() {
        val receiptChannel = TestReceiptChannel()
        val steward = MemorySteward(
            tokenVerifier = AlwaysInvalidTokenVerifier(),
            receiptChannel = receiptChannel
        )

        val result = steward.write(
            ReviewWriteRequest(
                sessionToken = "forged-token",
                artifactId = "proposal-001",
                field = "status",
                value = "APPROVED"
            )
        )

        // Write must be rejected
        assertEquals(StewardResult.REJECTED, result,
            "I-4 violation: write not rejected on invalid token signature")

        // POLICY_VIOLATION receipt must have been written
        assertEquals(1, receiptChannel.policyViolations().size,
            "I-4 violation: no PolicyViolation receipt written on step_1 rejection")

        val violation = receiptChannel.policyViolations().first()
        assertEquals("INVALID_TOKEN_SIGNATURE", violation.violation,
            "I-4 violation: wrong violation code for step_1 rejection")
    }

    // ---------------------------------------------------------------
    // I4-T2: Write with valid token; session expired (server-side)
    //        → Rejected at step_2; POLICY_VIOLATION receipt written
    //          Server-side expiry check: active = false OR expiry_time <= now
    // ---------------------------------------------------------------
    @Test
    @DisplayName("I4-T2: Write rejected at step_2 when session is expired (server-side)")
    fun writeRejectedOnExpiredSession() {
        val receiptChannel = TestReceiptChannel()
        val expiredSession = TestSessionRegistry().apply {
            register(sessionId = "session-01", active = false, expiryTime = 0L,
                certFingerprint = "fp-01")
        }
        val steward = MemorySteward(
            tokenVerifier = FixedTokenVerifier(sessionId = "session-01", certFingerprint = "fp-01"),
            sessionRegistry = expiredSession,
            receiptChannel = receiptChannel
        )

        val result = steward.write(
            ReviewWriteRequest(
                sessionToken = "valid-but-expired-session-token",
                artifactId = "proposal-001",
                field = "status",
                value = "APPROVED"
            )
        )

        assertEquals(StewardResult.REJECTED, result,
            "I-4 violation: write not rejected on expired session")

        assertEquals(1, receiptChannel.policyViolations().size,
            "I-4 violation: no PolicyViolation receipt written on step_2 rejection")

        val violation = receiptChannel.policyViolations().first()
        assertEquals("SESSION_EXPIRED", violation.violation,
            "I-4 violation: wrong violation code for step_2 rejection")
    }

    // ---------------------------------------------------------------
    // I4-T3: Write with valid token; embedded expiry_time tampered to far future
    //        → Rejected at step_2; server-side registry expiry wins over token claim
    //          Proves the steward trusts registry truth, not token appearance.
    // ---------------------------------------------------------------
    @Test
    @DisplayName("I4-T3: Server-side registry expiry wins over tampered embedded expiry in token")
    fun serverSideExpiryWinsOverTamperedTokenExpiry() {
        val receiptChannel = TestReceiptChannel()

        // Session is active but expired per registry (expiryTime in the past)
        val registry = TestSessionRegistry().apply {
            register(
                sessionId = "session-01",
                active = true,
                expiryTime = 1L,  // epoch + 1ms — definitively in the past
                certFingerprint = "fp-01"
            )
        }

        // Token claims far-future expiry — as if tampered to bypass expiry check
        val steward = MemorySteward(
            tokenVerifier = FixedTokenVerifier(
                sessionId = "session-01",
                certFingerprint = "fp-01",
                embeddedExpiryTime = Long.MAX_VALUE  // tampered: claims it never expires
            ),
            sessionRegistry = registry,
            receiptChannel = receiptChannel
        )

        val result = steward.write(
            ReviewWriteRequest(
                sessionToken = "tampered-expiry-token",
                artifactId = "proposal-001",
                field = "status",
                value = "APPROVED"
            )
        )

        // Must be rejected — registry says expired regardless of token claim
        assertEquals(StewardResult.REJECTED, result,
            "I-4 violation: write not rejected when registry shows expired session")

        // POLICY_VIOLATION receipt must reflect the server-side check, not the token
        assertEquals(1, receiptChannel.policyViolations().size,
            "I-4 violation: no PolicyViolation receipt written on step_2 rejection")

        val violation = receiptChannel.policyViolations().first()
        assertEquals("SESSION_EXPIRED", violation.violation,
            "I-4 violation: wrong violation code — server-side expiry must be the rejection reason")
    }

    // ---------------------------------------------------------------
    // I4-T4: Write to artifact locked by a different session
    //        → Rejected at step_4; ARTIFACT_LOCKED anomaly event (not a policy violation)
    //          Proves lock ownership is enforced; competing sessions cannot overwrite each other.
    // ---------------------------------------------------------------
    @Test
    @DisplayName("I4-T4: Write rejected at step_4 with anomaly when artifact locked by different session")
    fun writeRejectedWhenArtifactLockedByOtherSession() {
        val receiptChannel = TestReceiptChannel()
        val registry = TestSessionRegistry().apply {
            register(
                sessionId = "session-01",
                active = true,
                expiryTime = Long.MAX_VALUE,
                certFingerprint = "fp-01"
            )
        }
        // Artifact is locked by a different session — not the one making the write request
        val lockManager = TestArtifactLockManager().apply {
            lock(artifactId = "proposal-001", sessionId = "session-02")
        }

        val steward = MemorySteward(
            tokenVerifier = FixedTokenVerifier(sessionId = "session-01", certFingerprint = "fp-01"),
            sessionRegistry = registry,
            artifactLockManager = lockManager,
            receiptChannel = receiptChannel
        )

        val result = steward.write(
            ReviewWriteRequest(
                sessionToken = "valid-token",
                artifactId = "proposal-001",
                field = "status",
                value = "APPROVED"
            )
        )

        assertEquals(StewardResult.REJECTED, result,
            "I-4 violation: write not rejected when artifact locked by different session")

        // Must be an anomaly, not a policy violation — spec distinguishes these explicitly
        assertEquals(0, receiptChannel.policyViolations().size,
            "I-4 violation: lock conflict must not produce a PolicyViolation receipt")

        assertEquals(1, receiptChannel.anomalies("ARTIFACT_LOCKED_BY_OTHER_SESSION").size,
            "I-4 violation: no ARTIFACT_LOCKED_BY_OTHER_SESSION anomaly on lock conflict")
    }

    // ---------------------------------------------------------------
    // I4-T5: Write to out-of-scope field (e.g., diff — system-controlled)
    //        → Rejected at step_5; POLICY_VIOLATION receipt
    //          Proves field boundary is system-controlled, not caller-controlled.
    //          Also confirms this is POLICY_VIOLATION, not anomaly — distinct from T4.
    // ---------------------------------------------------------------
    @Test
    @DisplayName("I4-T5: Write rejected at step_5 with POLICY_VIOLATION when field is out of scope")
    fun writeRejectedOnOutOfScopeField() {
        val receiptChannel = TestReceiptChannel()
        val registry = TestSessionRegistry().apply {
            register(
                sessionId = "session-01",
                active = true,
                expiryTime = Long.MAX_VALUE,
                certFingerprint = "fp-01"
            )
        }
        val lockManager = TestArtifactLockManager().apply {
            lock(artifactId = "proposal-001", sessionId = "session-01")
        }
        // Field scope policy: only "status" is permitted; "diff" is system-controlled
        val fieldScope = TestFieldScopePolicy(permitted = setOf("status"))

        val steward = MemorySteward(
            tokenVerifier = FixedTokenVerifier(sessionId = "session-01", certFingerprint = "fp-01"),
            sessionRegistry = registry,
            artifactLockManager = lockManager,
            fieldScopePolicy = fieldScope,
            receiptChannel = receiptChannel
        )

        val result = steward.write(
            ReviewWriteRequest(
                sessionToken = "valid-token",
                artifactId = "proposal-001",
                field = "diff",   // out of scope — system-controlled field
                value = "injected content"
            )
        )

        assertEquals(StewardResult.REJECTED, result,
            "I-4 violation: write not rejected on out-of-scope field")

        // Must be a policy violation, not an anomaly
        assertEquals(0, receiptChannel.anomalies().size,
            "I-4 violation: out-of-scope field must not produce an anomaly receipt")

        assertEquals(1, receiptChannel.policyViolations("WRITE_FIELD_NOT_PERMITTED").size,
            "I-4 violation: no WRITE_FIELD_NOT_PERMITTED PolicyViolation on out-of-scope field")
    }

    // ---------------------------------------------------------------
    // I4-T6: Session expires with artifact in UNDER_REVIEW (locked by that session)
    //        → Artifact reverts to SUBMITTED
    //        → Lock released
    //        → ProposalStatusReceipt written (UNDER_REVIEW → SUBMITTED, changedBy: SYSTEM_EXPIRY)
    // ---------------------------------------------------------------
    @Test
    @DisplayName("I4-T6: Session expiry releases lock and reverts artifact to SUBMITTED with system receipt")
    fun sessionExpiryRevertsArtifactToSubmitted() {
        val receiptChannel = TestReceiptChannel()
        val lockManager = TestArtifactLockManager().apply {
            lock(artifactId = "proposal-001", sessionId = "session-01")
        }
        val artifactStore = TestArtifactStore().apply {
            setState("proposal-001", ArtifactState.UNDER_REVIEW)
        }

        val steward = MemorySteward(
            tokenVerifier = AlwaysInvalidTokenVerifier(),  // not needed for expiry path
            receiptChannel = receiptChannel,
            artifactLockManager = lockManager,
            artifactStore = artifactStore
        )

        // Simulate session expiry notification
        steward.onSessionExpired(sessionId = "session-01")

        // Lock must be released
        assertEquals(null, lockManager.lockHolder("proposal-001"),
            "I-4 violation: lock not released after session expiry")

        // Artifact must have reverted to SUBMITTED
        assertEquals(ArtifactState.SUBMITTED, artifactStore.getState("proposal-001"),
            "I-4 violation: artifact not reverted to SUBMITTED after session expiry")

        // ProposalStatusReceipt must be written
        val statusReceipts = receiptChannel.receiptsOfType<Receipt.ProposalStatusReceipt>()
        assertEquals(1, statusReceipts.size,
            "I-4 violation: no ProposalStatusReceipt written on session expiry reversion")

        val receipt = statusReceipts.first()
        assertEquals("UNDER_REVIEW", receipt.fromState,
            "I-4 violation: ProposalStatusReceipt fromState must be UNDER_REVIEW")
        assertEquals("SUBMITTED", receipt.toState,
            "I-4 violation: ProposalStatusReceipt toState must be SUBMITTED")
        assertEquals("SYSTEM_EXPIRY", receipt.changedBy,
            "I-4 violation: ProposalStatusReceipt changedBy must be SYSTEM_EXPIRY")
    }

    // ---------------------------------------------------------------
    // I4-T7: Full validation path passes → write applied; MEMORY_EVENT_CHANNEL notified
    //        All seven steps pass. Proves the complete chain can succeed end-to-end.
    //        Also proves no violation/anomaly receipt is emitted on the happy path.
    // ---------------------------------------------------------------
    @Test
    @DisplayName("I4-T7: Full validation pass — write applied and MEMORY_EVENT_CHANNEL notified")
    fun fullValidationPassWritesAndNotifies() {
        val receiptChannel = TestReceiptChannel()
        val registry = TestSessionRegistry().apply {
            register(
                sessionId = "session-01",
                active = true,
                expiryTime = Long.MAX_VALUE,
                certFingerprint = "fp-01"
            )
        }
        val lockManager = TestArtifactLockManager().apply {
            lock(artifactId = "proposal-001", sessionId = "session-01")
        }
        val artifactStore = TestArtifactStore().apply {
            setState("proposal-001", ArtifactState.UNDER_REVIEW)
        }
        val fieldScope = TestFieldScopePolicy(permitted = setOf("status"))
        val eventChannel = TestMemoryEventChannel()

        val steward = MemorySteward(
            tokenVerifier = FixedTokenVerifier(sessionId = "session-01", certFingerprint = "fp-01"),
            sessionRegistry = registry,
            artifactLockManager = lockManager,
            artifactStore = artifactStore,
            fieldScopePolicy = fieldScope,
            memoryEventChannel = eventChannel,
            receiptChannel = receiptChannel
        )

        val result = steward.write(
            ReviewWriteRequest(
                sessionToken = "valid-token",
                artifactId = "proposal-001",
                field = "status",
                value = "APPROVED"
            )
        )

        // Write must be applied
        assertEquals(StewardResult.APPLIED, result,
            "I-4 violation: write not applied when all validation steps pass")

        // Mutation must have occurred — field written to artifact store
        assertEquals("APPROVED", artifactStore.readField("proposal-001", "status"),
            "I-4 violation: field not written to artifact store after successful validation")

        // No violation or anomaly receipts on the happy path
        assertEquals(0, receiptChannel.policyViolations().size,
            "I-4 violation: PolicyViolation receipt emitted on successful write")
        assertEquals(0, receiptChannel.anomalies().size,
            "I-4 violation: Anomaly receipt emitted on successful write")

        // MEMORY_EVENT_CHANNEL notification must be emitted (step_7)
        assertEquals(1, eventChannel.events.size,
            "I-4 violation: no MEMORY_EVENT_CHANNEL notification on successful write")
        assertEquals("proposal-001", eventChannel.events.first().artifactId,
            "I-4 violation: MEMORY_EVENT_CHANNEL notification has wrong artifactId")
    }
}
