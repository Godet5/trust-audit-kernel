package com.aegisone.db

import com.aegisone.boot.SystemEvent
import com.aegisone.boot.SystemEventChannel
import java.sql.Connection
import java.sql.Types

/**
 * SQLite-backed SystemEventChannel.
 *
 * Writes to the system_events table (created by SQLiteBootstrap).
 * Returns false on write failure without throwing — lifecycle event emission
 * is best-effort and must not block the operation that triggered it.
 *
 * Source: implementationMap-trust-and-audit-v1.md §1, §4.1, observability extension
 */
class SQLiteSystemEventChannel(private val conn: Connection) : SystemEventChannel {

    @Synchronized
    override fun emit(event: SystemEvent): Boolean {
        return try {
            conn.autoCommit = false
            insert(event)
            conn.commit()
            true
        } catch (e: Exception) {
            runCatching { conn.rollback() }
            false
        }
    }

    private fun insert(event: SystemEvent) {
        val now = System.currentTimeMillis()
        conn.prepareStatement(
            """
            INSERT INTO system_events (
                event_type, timestamp_ms,
                manifest_version, step, reason,
                reconciliation_status, expired_sessions, ready_for_active,
                inserted_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            fun int(pos: Int, v: Int?) =
                if (v != null) ps.setInt(pos, v) else ps.setNull(pos, Types.INTEGER)
            fun str(pos: Int, v: String?) =
                if (v != null) ps.setString(pos, v) else ps.setNull(pos, Types.VARCHAR)
            fun bool(pos: Int, v: Boolean?) =
                if (v != null) ps.setInt(pos, if (v) 1 else 0) else ps.setNull(pos, Types.INTEGER)

            when (event) {
                is SystemEvent.BootVerified -> {
                    ps.setString(1, "BootVerified")
                    ps.setLong(2, event.timestamp)
                    int(3, event.manifestVersion)
                    str(4, null); str(5, null); str(6, null)
                    int(7, null); bool(8, null)
                }
                is SystemEvent.BootFailed -> {
                    ps.setString(1, "BootFailed")
                    ps.setLong(2, event.timestamp)
                    int(3, null)
                    str(4, event.step)
                    str(5, event.reason)
                    str(6, null); int(7, null); bool(8, null)
                }
                is SystemEvent.RecoveryCompleted -> {
                    ps.setString(1, "RecoveryCompleted")
                    ps.setLong(2, event.timestamp)
                    int(3, null); str(4, null); str(5, null)
                    str(6, event.reconciliationStatus)
                    int(7, event.expiredSessions)
                    bool(8, event.readyForActive)
                }
            }
            ps.setLong(9, now)
            ps.executeUpdate()
        }
    }
}
