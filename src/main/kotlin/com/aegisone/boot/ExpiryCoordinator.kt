package com.aegisone.boot

import com.aegisone.db.SQLiteSessionRegistry
import com.aegisone.review.MemorySteward

/**
 * Sweeps stale sessions and triggers MemorySteward cleanup for each.
 *
 * The recovery sequence per expired session:
 *   1. expireSessionsAndGetIds() transitions ACTIVE → EXPIRED in the registry
 *      and returns the IDs atomically (SELECT + UPDATE in one transaction).
 *   2. memorySteward.onSessionExpired(sessionId) releases all locks held by
 *      that session, reverts each artifact from UNDER_REVIEW to SUBMITTED, and
 *      writes a ProposalStatusReceipt to the receipt channel.
 *
 * Returns the count of sessions recovered this sweep.
 *
 * [now] is injectable so integration tests can force expiry without clock
 * manipulation. Production callers use the default (System.currentTimeMillis()).
 *
 * This coordinator is the missing live integration point between the session
 * lifecycle and the review cleanup path. Without it, expired sessions would
 * remain in the registry as ACTIVE, locks would never be released, and
 * artifacts would remain stuck in UNDER_REVIEW.
 *
 * Traces to: reviewSystemSpec-v1.md §4, implementationMap §4.4 release_expiry
 */
class ExpiryCoordinator(
    private val sessionRegistry: SQLiteSessionRegistry,
    private val memorySteward: MemorySteward
) {
    fun sweep(now: Long = System.currentTimeMillis()): Int {
        val expiredIds = sessionRegistry.expireSessionsAndGetIds(now)
        expiredIds.forEach { sessionId ->
            memorySteward.onSessionExpired(sessionId)
        }
        return expiredIds.size
    }
}
