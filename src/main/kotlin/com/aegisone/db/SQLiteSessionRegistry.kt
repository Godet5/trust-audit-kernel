package com.aegisone.db

import com.aegisone.review.SessionEntry
import com.aegisone.review.SessionRegistry

/**
 * SQLite-backed SessionRegistry.
 *
 * Implements the read interface (lookup) and provides management operations
 * on the concrete class. Management operations are not part of SessionRegistry
 * because session lifecycle belongs to the broker/review orchestration layer,
 * not to the read path consumed by MemorySteward.
 *
 * Session states:
 *   ACTIVE  — session is live; lookup returns active=true
 *   EXPIRED — expiry_ms elapsed without closure; lookup returns active=false
 *   CLOSED  — explicit close; lookup returns active=false
 *
 * [active] on SessionEntry is derived: state == "ACTIVE".
 * MemorySteward checks both active and expiryTime; for EXPIRED/CLOSED rows,
 * active=false causes step_2 to reject before reaching the expiry check.
 *
 * Write methods throw IllegalStateException on SQL failure.
 * [lookup] returns null on missing or read failure (safe default).
 *
 * Thread safety: synchronized on [shared].
 *
 * Source: implementationMap-trust-and-audit-v1.md §4.2, §4.3 step_2
 */
class SQLiteSessionRegistry(private val shared: SharedConnection) : SessionRegistry {

    // --- SessionRegistry interface ---

    override fun lookup(sessionId: String): SessionEntry? = synchronized(shared) {
        runCatching {
            shared.conn.prepareStatement(
                """
                SELECT session_id, cert_fingerprint, state, expiry_ms
                FROM sessions WHERE session_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, sessionId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    SessionEntry(
                        sessionId        = rs.getString("session_id"),
                        active           = rs.getString("state") == "ACTIVE",
                        expiryTime       = rs.getLong("expiry_ms"),
                        certFingerprint  = rs.getString("cert_fingerprint")
                    )
                }
            }
        }.getOrNull()
    }

    // --- Management operations (not in SessionRegistry interface) ---

    /**
     * Open a new session. [expiryMs] is the absolute epoch-ms expiry time.
     * Returns false if a session with this ID already exists or on write failure.
     * Throws IllegalStateException on SQL failure after a successful row check.
     */
    fun openSession(sessionId: String, certFingerprint: String, expiryMs: Long): Boolean = synchronized(shared) {
        val now = System.currentTimeMillis()
        try {
            shared.conn.autoCommit = false
            shared.conn.prepareStatement(
                """
                INSERT OR IGNORE INTO sessions
                    (session_id, cert_fingerprint, state, created_at_ms, last_heartbeat_ms, expiry_ms)
                VALUES (?, ?, 'ACTIVE', ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, sessionId)
                ps.setString(2, certFingerprint)
                ps.setLong(3, now)
                ps.setLong(4, now)
                ps.setLong(5, expiryMs)
                val rows = ps.executeUpdate()
                shared.conn.commit()
                return rows > 0
            }
        } catch (e: Exception) {
            runCatching { shared.conn.rollback() }
            throw IllegalStateException("openSession failed for $sessionId", e)
        }
    }

    /**
     * Extend the expiry of an ACTIVE session to [newExpiryMs].
     * No-op (returns false) if the session is not ACTIVE or does not exist.
     */
    fun heartbeat(sessionId: String, newExpiryMs: Long): Boolean = synchronized(shared) {
        val now = System.currentTimeMillis()
        try {
            shared.conn.autoCommit = false
            shared.conn.prepareStatement(
                """
                UPDATE sessions
                SET expiry_ms = ?, last_heartbeat_ms = ?
                WHERE session_id = ? AND state = 'ACTIVE'
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, newExpiryMs)
                ps.setLong(2, now)
                ps.setString(3, sessionId)
                val rows = ps.executeUpdate()
                shared.conn.commit()
                return rows > 0
            }
        } catch (e: Exception) {
            runCatching { shared.conn.rollback() }
            throw IllegalStateException("heartbeat failed for $sessionId", e)
        }
    }

    /**
     * Mark all ACTIVE sessions whose expiry_ms <= [now] as EXPIRED.
     * Returns the count of sessions transitioned.
     * Called by the coordinator or a scheduled sweep before processing actions.
     */
    fun expireSessions(now: Long = System.currentTimeMillis()): Int = synchronized(shared) {
        try {
            shared.conn.autoCommit = false
            shared.conn.prepareStatement(
                "UPDATE sessions SET state = 'EXPIRED' WHERE state = 'ACTIVE' AND expiry_ms <= ?"
            ).use { ps ->
                ps.setLong(1, now)
                val rows = ps.executeUpdate()
                shared.conn.commit()
                return rows
            }
        } catch (e: Exception) {
            runCatching { shared.conn.rollback() }
            throw IllegalStateException("expireSessions failed", e)
        }
    }

    /**
     * Mark all ACTIVE sessions whose expiry_ms <= [now] as EXPIRED, and return
     * the session IDs that were transitioned.
     *
     * This is the sweep method used by ExpiryCoordinator. It differs from
     * expireSessions() in that it returns the affected IDs rather than a count,
     * allowing callers to invoke cleanup callbacks (e.g. MemorySteward.onSessionExpired)
     * for each session that was just expired.
     *
     * SELECT and UPDATE use the same predicate within one transaction, so the
     * returned IDs are exactly the rows that were updated.
     */
    fun expireSessionsAndGetIds(now: Long = System.currentTimeMillis()): List<String> = synchronized(shared) {
        try {
            shared.conn.autoCommit = false
            // Collect IDs matching the expiry predicate before the update
            val ids = mutableListOf<String>()
            shared.conn.prepareStatement(
                "SELECT session_id FROM sessions WHERE state = 'ACTIVE' AND expiry_ms <= ?"
            ).use { ps ->
                ps.setLong(1, now)
                ps.executeQuery().use { rs ->
                    while (rs.next()) ids.add(rs.getString("session_id"))
                }
            }
            // Update the same predicate in the same transaction
            if (ids.isNotEmpty()) {
                shared.conn.prepareStatement(
                    "UPDATE sessions SET state = 'EXPIRED' WHERE state = 'ACTIVE' AND expiry_ms <= ?"
                ).use { ps ->
                    ps.setLong(1, now)
                    ps.executeUpdate()
                }
            }
            shared.conn.commit()
            return ids
        } catch (e: Exception) {
            runCatching { shared.conn.rollback() }
            throw IllegalStateException("expireSessionsAndGetIds failed", e)
        }
    }

    /**
     * Explicitly close a session (e.g., user logout). State transitions to CLOSED.
     * Returns false if the session does not exist or is already EXPIRED/CLOSED.
     */
    fun closeSession(sessionId: String): Boolean = synchronized(shared) {
        try {
            shared.conn.autoCommit = false
            shared.conn.prepareStatement(
                "UPDATE sessions SET state = 'CLOSED' WHERE session_id = ? AND state = 'ACTIVE'"
            ).use { ps ->
                ps.setString(1, sessionId)
                val rows = ps.executeUpdate()
                shared.conn.commit()
                return rows > 0
            }
        } catch (e: Exception) {
            runCatching { shared.conn.rollback() }
            throw IllegalStateException("closeSession failed for $sessionId", e)
        }
    }

    /**
     * Returns all session IDs currently in ACTIVE state.
     * Convenience method for callers that need to sweep active sessions.
     */
    fun activeSessions(): List<String> = synchronized(shared) {
        runCatching {
            val ids = mutableListOf<String>()
            shared.conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT session_id FROM sessions WHERE state = 'ACTIVE'").use { rs ->
                    while (rs.next()) ids.add(rs.getString("session_id"))
                }
            }
            ids
        }.getOrDefault(emptyList())
    }
}
