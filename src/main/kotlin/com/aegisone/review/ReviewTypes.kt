package com.aegisone.review

/**
 * Core types for the MEMORY_STEWARD write validation sequence.
 *
 * Source: implementationMap-trust-and-audit-v1.md §4.3
 */

/** A write request submitted to the MEMORY_STEWARD. */
data class ReviewWriteRequest(
    val sessionToken: String,
    val artifactId: String,
    val field: String,
    val value: String
)

/** The result of a MEMORY_STEWARD write attempt. */
enum class StewardResult {
    /** All validation steps passed; write was applied. */
    APPLIED,
    /** A validation step failed; write was rejected. */
    REJECTED
}

/**
 * Claims extracted from a verified session token.
 * Only populated after step_1 passes — never constructed from an unverified token.
 *
 * Source: implementationMap-trust-and-audit-v1.md §4.2, §4.3 step_1
 */
data class VerifiedToken(
    val sessionId: String,
    val certFingerprint: String,
    /** Expiry time embedded in the token itself. NOT used for server-side validation.
     *  Server-side check at step_2 uses SessionRegistry.expiryTime exclusively.
     *  This field exists so tests can prove the steward ignores it. */
    val embeddedExpiryTime: Long = Long.MAX_VALUE
)

/**
 * Token verifier. Step_1 of the write validation sequence.
 * Verifies the session_token signature against the Zone A platform trust root public key.
 * Returns the extracted claims on success, null on failure.
 *
 * Source: implementationMap-trust-and-audit-v1.md §4.3 step_1
 */
interface TokenVerifier {
    /** Returns extracted token claims if signature is valid, null otherwise. */
    fun verify(token: String): VerifiedToken?
}

/**
 * Session registry entry. Owned by the Capability Broker.
 * MEMORY_STEWARD queries it at step_2 and step_3.
 *
 * Source: implementationMap-trust-and-audit-v1.md §4.2 step_4, §4.3 step_2
 */
data class SessionEntry(
    val sessionId: String,
    val active: Boolean,
    val expiryTime: Long,
    val certFingerprint: String
)

/**
 * Session registry interface. MEMORY_STEWARD queries this at step_2 and step_3.
 * Owned by the Capability Broker; injected into MEMORY_STEWARD.
 *
 * Source: implementationMap-trust-and-audit-v1.md §4.3 step_2, step_3
 */
interface SessionRegistry {
    /** Returns the session entry, or null if the session_id is not found. */
    fun lookup(sessionId: String): SessionEntry?
}

/**
 * Artifact lock manager. Tracks exclusive locks on proposal artifacts.
 * MEMORY_STEWARD queries this at step_4 and releases locks on session expiry.
 *
 * Source: implementationMap-trust-and-audit-v1.md §4.3 step_4, §4.4
 */
interface ArtifactLockManager {
    /** Returns the session_id holding the lock, or null if the artifact is not locked. */
    fun lockHolder(artifactId: String): String?
    /** Returns all artifact IDs locked by the given session_id. */
    fun lockedBy(sessionId: String): List<String>
    /** Releases the lock on the given artifact. No-op if not locked. */
    fun release(artifactId: String)
}

/** Artifact lifecycle states relevant to the review path. */
enum class ArtifactState {
    SUBMITTED, UNDER_REVIEW, APPROVED, REJECTED, NEEDS_REVISION
}

/**
 * Artifact store. Tracks the current state of proposal artifacts.
 * MEMORY_STEWARD reads and writes state here during lock lifecycle events.
 *
 * Source: implementationMap-trust-and-audit-v1.md §4.4
 */
interface ArtifactStore {
    fun getState(artifactId: String): ArtifactState?
    fun setState(artifactId: String, state: ArtifactState)
    fun writeField(artifactId: String, field: String, value: String)
    fun readField(artifactId: String, field: String): String?
}

/**
 * Memory event channel. Receives best-effort notifications after a write is committed at step_6.
 * BEST-EFFORT ONLY — failure must not roll back the write or block result delivery.
 * Downstream modules must not depend on this for correctness.
 *
 * Source: implementationMap-trust-and-audit-v1.md §4.3 step_7
 */
interface MemoryEventChannel {
    fun notify(event: MemoryEvent)
}

data class MemoryEvent(
    val artifactId: String,
    val field: String,
    val changedBy: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Field scope policy. Defines which fields the review system is permitted to write.
 * MEMORY_STEWARD enforces this at step_5.
 *
 * Source: implementationMap-trust-and-audit-v1.md §4.3 step_5,
 *         reviewSystemSpec-v1.md §3.1 ReviewSystemWriteAuthority
 */
interface FieldScopePolicy {
    /** Returns true if the field is in the permitted write set. */
    fun isPermitted(field: String): Boolean
}
