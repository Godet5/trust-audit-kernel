package com.aegisone.boot

import com.aegisone.broker.AgentRole
import com.aegisone.broker.CapabilityBroker
import com.aegisone.broker.GrantAuthority
import com.aegisone.db.SQLiteSessionRegistry
import com.aegisone.receipt.ReceiptChannel
import com.aegisone.review.ArtifactLockManager
import com.aegisone.review.ArtifactStore
import com.aegisone.review.FieldScopePolicy
import com.aegisone.review.MemorySteward
import com.aegisone.review.TokenVerifier

/**
 * Opens review sessions against the live broker/session registry.
 *
 * Before opening any session, ReviewOrchestrator enforces two gates:
 *
 *   Gate 1: The broker must be ACTIVE and the manifest must authorize
 *           REVIEW_WRITE for the MEMORY_STEWARD role via SYSTEM_POLICY.
 *           issueGrant() enforces both the ACTIVE state and the manifest
 *           grant list check. If it returns null, no session opens.
 *
 *   Gate 2: SQLiteSessionRegistry.openSession() must succeed. A false
 *           return means a duplicate session_id or write failure.
 *
 * On success, returns ReviewSessionResult.Opened carrying a wired
 * MemorySteward ready to accept write requests for the opened session.
 *
 * This is the composition that was missing between the broker authority
 * subsystem and the review persistence subsystem.
 *
 * Traces to: I-2 (no SYSTEM_POLICY grant without verified manifest),
 *            I-4 (MemorySteward 7-step validation)
 * Source: implementationMap-trust-and-audit-v1.md §2.2, §4.2, §4.3
 */
class ReviewOrchestrator(
    private val broker: CapabilityBroker,
    private val sessionRegistry: SQLiteSessionRegistry,
    private val artifactStore: ArtifactStore,
    private val lockManager: ArtifactLockManager,
    private val receiptChannel: ReceiptChannel,
    private val fieldScopePolicy: FieldScopePolicy
) {
    fun openReviewSession(
        sessionId: String,
        certFingerprint: String,
        expiryMs: Long,
        tokenVerifier: TokenVerifier
    ): ReviewSessionResult {
        // Gate 1: broker must authorize REVIEW_WRITE for MEMORY_STEWARD.
        // issueGrant() checks: ACTIVE state + role in manifest + grant in manifest.
        val grant = broker.issueGrant(
            capabilityName = REVIEW_WRITE_CAPABILITY,
            targetRole     = AgentRole.MEMORY_STEWARD,
            authority      = GrantAuthority.SYSTEM_POLICY
        )
        if (grant == null) {
            return ReviewSessionResult.Denied(
                "Broker denied $REVIEW_WRITE_CAPABILITY grant for MEMORY_STEWARD " +
                "(broker state: ${broker.state})"
            )
        }

        // Gate 2: open the session in the real registry.
        val opened = sessionRegistry.openSession(sessionId, certFingerprint, expiryMs)
        if (!opened) {
            return ReviewSessionResult.Denied(
                "Session registry rejected openSession for $sessionId " +
                "(duplicate session_id or write failure)"
            )
        }

        // Both gates passed — wire and return a MemorySteward for this session.
        val memorySteward = MemorySteward(
            tokenVerifier       = tokenVerifier,
            receiptChannel      = receiptChannel,
            sessionRegistry     = sessionRegistry,
            artifactLockManager = lockManager,
            fieldScopePolicy    = fieldScopePolicy,
            artifactStore       = artifactStore
        )
        return ReviewSessionResult.Opened(memorySteward, sessionId)
    }

    companion object {
        const val REVIEW_WRITE_CAPABILITY = "REVIEW_WRITE"
    }
}

sealed class ReviewSessionResult {
    /** Session opened. [memorySteward] is wired and ready for write requests. */
    data class Opened(val memorySteward: MemorySteward, val sessionId: String) : ReviewSessionResult()

    /** Session denied. [reason] identifies the gate that blocked it. */
    data class Denied(val reason: String) : ReviewSessionResult()
}
