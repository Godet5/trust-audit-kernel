package com.aegisone.invariants

import com.aegisone.review.ArtifactLockManager

/**
 * In-memory artifact lock manager for Phase 0 harness tests.
 * Supports explicit lock registration for test scenario setup.
 */
class TestArtifactLockManager : ArtifactLockManager {
    private val locks = mutableMapOf<String, String>()  // artifactId → sessionId

    fun lock(artifactId: String, sessionId: String): Boolean {
        val current = locks[artifactId]
        return if (current == null || current == sessionId) {
            locks[artifactId] = sessionId
            true
        } else {
            false  // different session holds it — no displacement
        }
    }

    override fun release(artifactId: String) {
        locks.remove(artifactId)
    }

    override fun lockHolder(artifactId: String): String? = locks[artifactId]

    override fun lockedBy(sessionId: String): List<String> =
        locks.entries.filter { it.value == sessionId }.map { it.key }
}
