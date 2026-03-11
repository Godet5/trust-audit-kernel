package com.aegisone.db

import com.aegisone.broker.AuthorityDecision
import com.aegisone.broker.AuthorityDecisionChannel
import java.sql.Connection
import java.sql.Types

/**
 * SQLite-backed AuthorityDecisionChannel.
 *
 * Writes to the authority_decisions table (created by SQLiteBootstrap).
 * Every call to record() is a separate committed transaction — decisions
 * are written one at a time and are immediately durable on return.
 *
 * Returns false on write failure without throwing. The broker records
 * decisions best-effort — a failure here does not block the grant decision
 * itself (the grant was already issued or denied before record() is called).
 *
 * Source: implementationMap-trust-and-audit-v1.md §2.2, observability extension
 */
class SQLiteAuthorityDecisionChannel(private val conn: Connection) : AuthorityDecisionChannel {

    @Synchronized
    override fun record(decision: AuthorityDecision): Boolean {
        return try {
            conn.autoCommit = false
            insert(decision)
            conn.commit()
            true
        } catch (e: Exception) {
            runCatching { conn.rollback() }
            false
        }
    }

    private fun insert(decision: AuthorityDecision) {
        val now = System.currentTimeMillis()
        conn.prepareStatement(
            """
            INSERT INTO authority_decisions (
                decision_type, timestamp_ms, capability_name, target_role,
                authority, manifest_version, floor_version, reason, inserted_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            fun int(pos: Int, v: Int?) =
                if (v != null) ps.setInt(pos, v) else ps.setNull(pos, Types.INTEGER)
            fun str(pos: Int, v: String?) =
                if (v != null) ps.setString(pos, v) else ps.setNull(pos, Types.VARCHAR)

            when (decision) {
                is AuthorityDecision.GrantIssued -> {
                    ps.setString(1, "GrantIssued")
                    ps.setLong(2, decision.timestamp)
                    ps.setString(3, decision.capabilityName)
                    ps.setString(4, decision.targetRole.name)
                    ps.setString(5, decision.authority.name)
                    int(6, decision.manifestVersion)
                    int(7, decision.floorVersion)
                    str(8, null)
                }
                is AuthorityDecision.GrantDenied -> {
                    ps.setString(1, "GrantDenied")
                    ps.setLong(2, decision.timestamp)
                    ps.setString(3, decision.capabilityName)
                    ps.setString(4, decision.targetRole.name)
                    ps.setString(5, decision.authority.name)
                    int(6, decision.manifestVersion)
                    int(7, decision.floorVersion)
                    str(8, decision.reason)
                }
            }
            ps.setLong(9, now)
            ps.executeUpdate()
        }
    }
}
