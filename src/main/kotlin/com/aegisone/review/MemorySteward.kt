package com.aegisone.review

import com.aegisone.receipt.Receipt
import com.aegisone.receipt.ReceiptChannel

/**
 * Owns write validation for all review system interactions.
 * All seven validation steps must pass before a write is applied.
 * Sequence is strict: failure at any step rejects the write immediately.
 *
 * Currently implemented:
 *   step_1: Verify session_token signature → reject with POLICY_VIOLATION on failure
 *   step_2: Look up session_id in registry; assert active = true AND expiry_time > now
 *           → reject with POLICY_VIOLATION on failure
 *   step_3: Assert token.cert_fingerprint == registry.cert_fingerprint
 *           → reject with POLICY_VIOLATION on failure
 *   step_4: Assert artifact is locked by this session_id
 *           → locked by other: reject with ARTIFACT_LOCKED_BY_OTHER_SESSION anomaly
 *           → not locked: reject with POLICY_VIOLATION
 *   step_5: Assert target field is in FieldScopePolicy permitted set
 *           → reject with WRITE_FIELD_NOT_PERMITTED POLICY_VIOLATION on failure
 *   step_6: Apply write to artifact store
 *   step_7: Notify MEMORY_EVENT_CHANNEL (best-effort; failure does not roll back step_6)
 *
 * Source: implementationMap-trust-and-audit-v1.md §4.3
 */
class MemorySteward(
    private val tokenVerifier: TokenVerifier,
    private val receiptChannel: ReceiptChannel,
    private val sessionRegistry: SessionRegistry = EmptySessionRegistry,
    private val artifactLockManager: ArtifactLockManager = NoLocksManager,
    private val fieldScopePolicy: FieldScopePolicy = AllFieldsPermitted,
    private val artifactStore: ArtifactStore = NoOpArtifactStore,
    private val memoryEventChannel: MemoryEventChannel = NoOpMemoryEventChannel
) {
    fun write(request: ReviewWriteRequest): StewardResult {
        // Step 1: Verify session_token signature.
        // Failure: reject; POLICY_VIOLATION receipt; no mutation.
        val token = tokenVerifier.verify(request.sessionToken)
        if (token == null) {
            receiptChannel.write(Receipt.PolicyViolation(
                violation = "INVALID_TOKEN_SIGNATURE",
                detail = "Session token signature verification failed for artifact ${request.artifactId}"
            ))
            return StewardResult.REJECTED
        }

        // Step 2: Look up session in registry; assert active = true AND expiry_time > now.
        // Failure: reject; POLICY_VIOLATION receipt; no mutation.
        val session = sessionRegistry.lookup(token.sessionId)
        if (session == null || !session.active || session.expiryTime <= System.currentTimeMillis()) {
            receiptChannel.write(Receipt.PolicyViolation(
                violation = "SESSION_EXPIRED",
                detail = "Session ${token.sessionId} is not active or has expired"
            ))
            return StewardResult.REJECTED
        }

        // Step 3: Assert token.cert_fingerprint == registry.cert_fingerprint.
        // Failure: reject; POLICY_VIOLATION receipt; no mutation.
        if (token.certFingerprint != session.certFingerprint) {
            receiptChannel.write(Receipt.PolicyViolation(
                violation = "CERT_FINGERPRINT_MISMATCH",
                detail = "Token cert_fingerprint does not match registry for session ${token.sessionId}"
            ))
            return StewardResult.REJECTED
        }

        // Step 4: Assert artifact is locked by this session_id.
        // Locked by other session: anomaly (not policy violation).
        // Not locked at all: policy violation.
        val holder = artifactLockManager.lockHolder(request.artifactId)
        when {
            holder == null -> {
                receiptChannel.write(Receipt.PolicyViolation(
                    violation = "ARTIFACT_NOT_LOCKED",
                    detail = "Artifact ${request.artifactId} is not locked by any session"
                ))
                return StewardResult.REJECTED
            }
            holder != token.sessionId -> {
                receiptChannel.write(Receipt.Anomaly(
                    type = "ARTIFACT_LOCKED_BY_OTHER_SESSION",
                    detail = "Artifact ${request.artifactId} locked by $holder; " +
                             "write attempted by ${token.sessionId}"
                ))
                return StewardResult.REJECTED
            }
        }

        // Step 5: Assert target field is in the permitted write set.
        // Failure: reject; POLICY_VIOLATION receipt; no mutation.
        if (!fieldScopePolicy.isPermitted(request.field)) {
            receiptChannel.write(Receipt.PolicyViolation(
                violation = "WRITE_FIELD_NOT_PERMITTED",
                detail = "Field '${request.field}' is not in the ReviewSystemWriteAuthority " +
                         "permitted set for artifact ${request.artifactId}"
            ))
            return StewardResult.REJECTED
        }

        // Step 6: Apply write.
        artifactStore.writeField(request.artifactId, request.field, request.value)

        // Step 7: Notify MEMORY_EVENT_CHANNEL. Best-effort — failure does not roll back step_6.
        memoryEventChannel.notify(MemoryEvent(
            artifactId = request.artifactId,
            field = request.field,
            changedBy = token.sessionId
        ))

        return StewardResult.APPLIED
    }

    /**
     * Called when a session expires. Releases all locks held by that session and
     * reverts each artifact from UNDER_REVIEW to SUBMITTED, writing a
     * ProposalStatusReceipt for each reversion.
     *
     * Source: implementationMap-trust-and-audit-v1.md §4.4 release_expiry
     */
    fun onSessionExpired(sessionId: String) {
        artifactLockManager.lockedBy(sessionId).forEach { artifactId ->
            artifactLockManager.release(artifactId)
            artifactStore.setState(artifactId, ArtifactState.SUBMITTED)
            receiptChannel.write(Receipt.ProposalStatusReceipt(
                artifactId = artifactId,
                fromState = "UNDER_REVIEW",
                toState = "SUBMITTED",
                changedBy = "SYSTEM_EXPIRY"
            ))
        }
    }
}

/** Safe default: no sessions exist. Used when no registry is wired. */
private object EmptySessionRegistry : SessionRegistry {
    override fun lookup(sessionId: String): SessionEntry? = null
}

/** Safe default: all fields permitted. Replaced by explicit policy in production and I4-T5+. */
private object AllFieldsPermitted : FieldScopePolicy {
    override fun isPermitted(field: String): Boolean = true
}

/** Safe default: no artifact state tracked. Used when no store is wired. */
private object NoOpArtifactStore : ArtifactStore {
    override fun getState(artifactId: String): ArtifactState? = null
    override fun setState(artifactId: String, state: ArtifactState) = Unit
    override fun writeField(artifactId: String, field: String, value: String) = Unit
    override fun readField(artifactId: String, field: String): String? = null
}

/** Safe default: discards all events. Used when no event channel is wired. */
private object NoOpMemoryEventChannel : MemoryEventChannel {
    override fun notify(event: MemoryEvent) = Unit
}

/** Safe default: no locks exist. Satisfies extended interface. */
private object NoLocksManager : ArtifactLockManager {
    override fun lockHolder(artifactId: String): String? = null
    override fun lockedBy(sessionId: String): List<String> = emptyList()
    override fun release(artifactId: String) = Unit
}
