package com.aegisone.db

import com.aegisone.broker.AuthorityDecision
import com.aegisone.broker.AuthorityDecisionChannel
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
 * Thread safety: synchronized on [shared].
 *
 * Source: implementationMap-trust-and-audit-v1.md §2.2, observability extension
 */
class SQLiteAuthorityDecisionChannel(private val shared: SharedConnection) : AuthorityDecisionChannel {

    override fun record(decision: AuthorityDecision): Boolean = synchronized(shared) {
        try {
            shared.conn.autoCommit = false
            insert(decision)
            shared.conn.commit()
            true
        } catch (e: Exception) {
            runCatching { shared.conn.rollback() }
            false
        }
    }

    private fun insert(decision: AuthorityDecision) {
        val now = System.currentTimeMillis()
        shared.conn.prepareStatement(
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
                // Spawn subtypes repurpose grant columns (Phase 0 pragmatics):
                //   capability_name → agentId
                //   target_role     → slot name
                //   authority       → requestingAgentId or "SYSTEM"
                //   manifest_version, floor_version → NULL (not applicable)
                //   reason          → DenialReason name (SpawnDenied only)
                is AuthorityDecision.SpawnIssued -> {
                    ps.setString(1, "SpawnIssued")
                    ps.setLong(2, decision.timestamp)
                    ps.setString(3, decision.agentId)
                    ps.setString(4, decision.slot.name)
                    ps.setString(5, decision.requestingAgentId ?: "SYSTEM")
                    int(6, null)
                    int(7, null)
                    str(8, null)
                }
                is AuthorityDecision.SpawnDenied -> {
                    ps.setString(1, "SpawnDenied")
                    ps.setLong(2, decision.timestamp)
                    ps.setString(3, decision.agentId)
                    ps.setString(4, decision.slot.name)
                    ps.setString(5, decision.requestingAgentId ?: "SYSTEM")
                    int(6, null)
                    int(7, null)
                    str(8, decision.reason)
                }
            }
            ps.setLong(9, now)
            ps.executeUpdate()
        }
    }
}
