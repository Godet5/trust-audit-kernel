package com.aegisone.db

import com.aegisone.review.ArtifactState
import com.aegisone.review.ArtifactStore
import java.sql.Connection

/**
 * SQLite-backed ArtifactStore.
 *
 * Replaces TestArtifactStore in-memory double with durable file storage.
 * WAL + synchronous=FULL applied at connection open time (ReviewDbBootstrap).
 *
 * Write methods throw IllegalStateException on SQL failure. The ArtifactStore
 * interface returns Unit for writes — callers that need to handle write failure
 * must catch at call site. MemorySteward does not currently catch store exceptions;
 * an uncaught exception surfaces the failure loudly rather than silently applying
 * an unacknowledged write.
 *
 * Reads return null on missing or failed lookup (safe: callers already handle null).
 *
 * Thread safety: @Synchronized on all methods. Single-connection model for Phase 0.
 *
 * Source: implementationMap-trust-and-audit-v1.md §4.4; reviewSystemSpec-v1.md §3
 */
class SQLiteArtifactStore(private val conn: Connection) : ArtifactStore {

    @Synchronized
    override fun getState(artifactId: String): ArtifactState? {
        return runCatching {
            conn.prepareStatement(
                "SELECT state FROM artifacts WHERE artifact_id = ?"
            ).use { ps ->
                ps.setString(1, artifactId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) ArtifactState.valueOf(rs.getString("state")) else null
                }
            }
        }.getOrNull()
    }

    @Synchronized
    override fun setState(artifactId: String, state: ArtifactState) {
        try {
            conn.autoCommit = false
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO artifacts (artifact_id, state, updated_at_ms)
                VALUES (?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, artifactId)
                ps.setString(2, state.name)
                ps.setLong(3, System.currentTimeMillis())
                ps.executeUpdate()
            }
            conn.commit()
        } catch (e: Exception) {
            runCatching { conn.rollback() }
            throw IllegalStateException("setState failed for artifact $artifactId", e)
        }
    }

    @Synchronized
    override fun writeField(artifactId: String, field: String, value: String) {
        try {
            conn.autoCommit = false
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO artifact_fields
                    (artifact_id, field_name, field_value, updated_at_ms)
                VALUES (?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, artifactId)
                ps.setString(2, field)
                ps.setString(3, value)
                ps.setLong(4, System.currentTimeMillis())
                ps.executeUpdate()
            }
            conn.commit()
        } catch (e: Exception) {
            runCatching { conn.rollback() }
            throw IllegalStateException("writeField failed for artifact $artifactId field $field", e)
        }
    }

    @Synchronized
    override fun readField(artifactId: String, field: String): String? {
        return runCatching {
            conn.prepareStatement(
                "SELECT field_value FROM artifact_fields WHERE artifact_id = ? AND field_name = ?"
            ).use { ps ->
                ps.setString(1, artifactId)
                ps.setString(2, field)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getString("field_value") else null
                }
            }
        }.getOrNull()
    }
}
