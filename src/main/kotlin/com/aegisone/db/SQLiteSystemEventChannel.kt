package com.aegisone.db

import com.aegisone.boot.SystemEvent
import com.aegisone.boot.SystemEventChannel
import java.sql.Types

/**
 * SQLite-backed SystemEventChannel.
 *
 * Writes to the system_events table (created by SQLiteBootstrap).
 * Returns false on write failure without throwing — lifecycle event emission
 * is best-effort and must not block the operation that triggered it.
 *
 * Thread safety: synchronized on [shared].
 *
 * Source: implementationMap-trust-and-audit-v1.md §1, §4.1, observability extension
 */
class SQLiteSystemEventChannel(private val shared: SharedConnection) : SystemEventChannel {

    override fun emit(event: SystemEvent): Boolean = synchronized(shared) {
        try {
            shared.conn.autoCommit = false
            insert(event)
            shared.conn.commit()
            true
        } catch (e: Exception) {
            runCatching { shared.conn.rollback() }
            false
        }
    }

    private fun insert(event: SystemEvent) {
        val now = System.currentTimeMillis()
        shared.conn.prepareStatement(
            """
            INSERT INTO system_events (
                event_type, timestamp_ms,
                manifest_version, step, reason,
                reconciliation_status, expired_sessions, ready_for_active,
                from_state, to_state,
                inserted_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    str(9, null); str(10, null)
                }
                is SystemEvent.BootFailed -> {
                    ps.setString(1, "BootFailed")
                    ps.setLong(2, event.timestamp)
                    int(3, null)
                    str(4, event.step)
                    str(5, event.reason)
                    str(6, null); int(7, null); bool(8, null)
                    str(9, null); str(10, null)
                }
                is SystemEvent.RecoveryCompleted -> {
                    ps.setString(1, "RecoveryCompleted")
                    ps.setLong(2, event.timestamp)
                    int(3, null); str(4, null); str(5, null)
                    str(6, event.reconciliationStatus)
                    int(7, event.expiredSessions)
                    bool(8, event.readyForActive)
                    str(9, null); str(10, null)
                }
                is SystemEvent.BrokerStateChanged -> {
                    ps.setString(1, "BrokerStateChanged")
                    ps.setLong(2, event.timestamp)
                    int(3, event.manifestVersion)
                    str(4, null)
                    str(5, event.reason)
                    str(6, null); int(7, null); bool(8, null)
                    str(9, event.fromState)
                    str(10, event.toState)
                }
            }
            ps.setLong(11, now)
            ps.executeUpdate()
        }
    }
}
