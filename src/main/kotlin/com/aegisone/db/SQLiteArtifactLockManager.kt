package com.aegisone.db

import com.aegisone.review.ArtifactLockManager

/**
 * SQLite-backed ArtifactLockManager.
 *
 * Replaces TestArtifactLockManager in-memory double with durable storage.
 * Uses the same SharedConnection as SQLiteArtifactStore (passed from ReviewDbBootstrap)
 * so that lock and artifact state are consistent within a single transaction boundary
 * AND share the same synchronization monitor.
 *
 * [lock] is not part of the ArtifactLockManager interface — it is a management
 * operation called by the review path to acquire a lock, not a read operation.
 * Parallel to TestArtifactLockManager.lock() used in harness tests.
 *
 * Lock semantics: exclusive, non-stealing. [lock] returns true if the lock was
 * acquired or is already held by the same session (idempotent re-lock). Returns
 * false if a different session currently holds the lock — the existing holder is
 * NOT displaced. Callers must check the return value and handle conflict.
 *
 * Write methods throw IllegalStateException on SQL failure.
 * Read methods return null / empty on failure (safe defaults).
 *
 * Thread safety: synchronized on [shared].
 *
 * Source: implementationMap-trust-and-audit-v1.md §4.3 step_4, §4.4
 */
class SQLiteArtifactLockManager(private val shared: SharedConnection) : ArtifactLockManager {

    /**
     * Attempt to acquire an exclusive lock on [artifactId] for [sessionId].
     * Not part of the ArtifactLockManager interface — management operation only.
     *
     * Returns true if:
     *   - The artifact was unlocked and this session acquired it, OR
     *   - This session already holds the lock (idempotent).
     * Returns false if a different session holds the lock (conflict — no displacement).
     * Throws IllegalStateException on write failure.
     */
    fun lock(artifactId: String, sessionId: String): Boolean = synchronized(shared) {
        try {
            shared.conn.autoCommit = false
            // INSERT OR IGNORE: succeeds only if no row exists for artifactId.
            val rows = shared.conn.prepareStatement(
                "INSERT OR IGNORE INTO artifact_locks (artifact_id, session_id, acquired_at_ms) VALUES (?, ?, ?)"
            ).use { ps ->
                ps.setString(1, artifactId)
                ps.setString(2, sessionId)
                ps.setLong(3, System.currentTimeMillis())
                ps.executeUpdate()
            }
            if (rows > 0) {
                shared.conn.commit()
                return true  // acquired — was unlocked
            }
            // Already locked — check whether this session is the current holder.
            val holder = shared.conn.prepareStatement(
                "SELECT session_id FROM artifact_locks WHERE artifact_id = ?"
            ).use { ps ->
                ps.setString(1, artifactId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
            shared.conn.commit()
            holder == sessionId  // true = idempotent re-lock; false = conflict
        } catch (e: Exception) {
            runCatching { shared.conn.rollback() }
            throw IllegalStateException("lock() failed for artifact $artifactId session $sessionId", e)
        }
    }

    override fun lockHolder(artifactId: String): String? = synchronized(shared) {
        runCatching {
            shared.conn.prepareStatement(
                "SELECT session_id FROM artifact_locks WHERE artifact_id = ?"
            ).use { ps ->
                ps.setString(1, artifactId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getString("session_id") else null
                }
            }
        }.getOrNull()
    }

    override fun lockedBy(sessionId: String): List<String> = synchronized(shared) {
        runCatching {
            val ids = mutableListOf<String>()
            shared.conn.prepareStatement(
                "SELECT artifact_id FROM artifact_locks WHERE session_id = ?"
            ).use { ps ->
                ps.setString(1, sessionId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) ids.add(rs.getString("artifact_id"))
                }
            }
            ids
        }.getOrDefault(emptyList())
    }

    override fun release(artifactId: String): Unit = synchronized(shared) {
        try {
            shared.conn.autoCommit = false
            shared.conn.prepareStatement(
                "DELETE FROM artifact_locks WHERE artifact_id = ?"
            ).use { ps ->
                ps.setString(1, artifactId)
                ps.executeUpdate()
            }
            shared.conn.commit()
        } catch (e: Exception) {
            runCatching { shared.conn.rollback() }
            throw IllegalStateException("release() failed for artifact $artifactId", e)
        }
    }
}
