package com.aegisone.invariants

import com.aegisone.review.SessionEntry
import com.aegisone.review.SessionRegistry

/**
 * In-memory session registry for Phase 0 harness tests.
 * Supports explicit registration of session entries for test scenario setup.
 */
class TestSessionRegistry : SessionRegistry {
    private val entries = mutableMapOf<String, SessionEntry>()

    fun register(
        sessionId: String,
        active: Boolean,
        expiryTime: Long,
        certFingerprint: String
    ) {
        entries[sessionId] = SessionEntry(
            sessionId = sessionId,
            active = active,
            expiryTime = expiryTime,
            certFingerprint = certFingerprint
        )
    }

    override fun lookup(sessionId: String): SessionEntry? = entries[sessionId]
}
