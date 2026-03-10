package com.aegisone.db

import com.aegisone.review.ArtifactLockManager
import java.sql.Connection

/**
 * SQLite-backed ArtifactLockManager.
 *
 * Replaces TestArtifactLockManager in-memory double with durable storage.
 * Uses the same connection as SQLiteArtifactStore (passed from ReviewDbBootstrap)
 * so that lock and artifact state are consistent within a single transaction boundary.
 *
 * [lock] is not part of the ArtifactLockManager interface — it is a management
 * operation called by the review path to acquire a lock, not a read operation.
 * Parallel to TestArtifactLockManager.lock() used in harness tests.
 *
 * Lock semantics: exclusive. A second lock() call for the same artifact_id
 * replaces the holder (INSERT OR REPLACE). Callers that need "acquire only if
 * unlocked" semantics must check lockHolder() first within the same transaction.
 *
 * Write methods throw IllegalStateException on SQL failure.
 * Read methods return null / empty on failure (safe defaults).
 *
 * Thread safety: @Synchronized on all methods.
 *
 * Source: implementationMap-trust-and-audit-v1.md §4.3 step_4, §4.4
 */
class SQLiteArtifactLockManager(private val conn: Connection) : ArtifactLockManager {

    /**
     * Acquire an exclusive lock on [artifactId] for [sessionId].
     * Not part of the ArtifactLockManager interface — management operation only.
     * Replaces any existing lock holder (INSERT OR REPLACE semantics).
     * Throws IllegalStateException on write failure.
     */
    @Synchronized
    fun lock(artifactId: String, sessionId: String) {
        try {
            conn.autoCommit = false
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO artifact_locks (artifact_id, session_id, acquired_at_ms)
                VALUES (?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, artifactId)
                ps.setString(2, sessionId)
                ps.setLong(3, System.currentTimeMillis())
                ps.executeUpdate()
            }
            conn.commit()
        } catch (e: Exception) {
            runCatching { conn.rollback() }
            throw IllegalStateException("lock() failed for artifact $artifactId session $sessionId", e)
        }
    }

    @Synchronized
    override fun lockHolder(artifactId: String): String? {
        return runCatching {
            conn.prepareStatement(
                "SELECT session_id FROM artifact_locks WHERE artifact_id = ?"
            ).use { ps ->
                ps.setString(1, artifactId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getString("session_id") else null
                }
            }
        }.getOrNull()
    }

    @Synchronized
    override fun lockedBy(sessionId: String): List<String> {
        return runCatching {
            val ids = mutableListOf<String>()
            conn.prepareStatement(
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

    @Synchronized
    override fun release(artifactId: String) {
        try {
            conn.autoCommit = false
            conn.prepareStatement(
                "DELETE FROM artifact_locks WHERE artifact_id = ?"
            ).use { ps ->
                ps.setString(1, artifactId)
                ps.executeUpdate()
            }
            conn.commit()
        } catch (e: Exception) {
            runCatching { conn.rollback() }
            throw IllegalStateException("release() failed for artifact $artifactId", e)
        }
    }
}
