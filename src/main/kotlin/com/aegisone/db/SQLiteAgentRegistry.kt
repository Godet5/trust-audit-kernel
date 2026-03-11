package com.aegisone.db

import com.aegisone.broker.AuthorityDecision
import com.aegisone.broker.AuthorityDecisionChannel
import com.aegisone.execution.ActiveAgentRow
import com.aegisone.execution.AgentRegistry
import com.aegisone.execution.AgentSlot
import com.aegisone.execution.DenialReason
import com.aegisone.execution.RegistrationResult
import java.sql.Connection

/**
 * SQLite-backed AgentRegistry.
 *
 * Enforces the Phase 0 ceiling (D-NC3): max 2 agents globally, max 1 per slot
 * (PRIMARY, HELPER), no recursive spawn from HELPER agents.
 *
 * Atomicity guarantee:
 *   All reads (count, slot check, requester slot) and the INSERT are performed
 *   inside a single @Synchronized block on the registry instance. Concurrent
 *   register() calls are serialized — the ceiling check and the slot insertion
 *   are never observed by two threads simultaneously. The DB PRIMARY KEY on
 *   agent_id is a DB-level safety net that rejects any duplicate that somehow
 *   bypasses the synchronized block.
 *
 * Observability:
 *   Every registration attempt (success or denial) is recorded to
 *   [decisionChannel] if wired. The channel writes are best-effort:
 *   a channel failure does not affect the RegistrationResult.
 *
 * Schema: active_agents table (created by SQLiteBootstrap).
 *
 * Source: agentPolicyEngine-v2.1 §D-NC3, §DR-01
 */
class SQLiteAgentRegistry(
    private val conn: Connection,
    private val decisionChannel: AuthorityDecisionChannel? = null
) : AgentRegistry {

    companion object {
        private const val GLOBAL_CEILING = 2
        private const val SLOT_CEILING   = 1
    }

    @Synchronized
    override fun register(
        agentId: String,
        slot: AgentSlot,
        requestingAgentId: String?
    ): RegistrationResult {

        // Step 1: Recursive spawn check.
        // If the requesting agent is itself a HELPER, deny before ceiling check.
        if (requestingAgentId != null && slotOf(requestingAgentId) == AgentSlot.HELPER) {
            val denial = RegistrationResult.Denied(DenialReason.RECURSIVE_SPAWN_DENIED)
            decisionChannel?.record(AuthorityDecision.SpawnDenied(
                agentId          = agentId,
                slot             = slot,
                requestingAgentId = requestingAgentId,
                reason           = DenialReason.RECURSIVE_SPAWN_DENIED.name
            ))
            return denial
        }

        // Step 2: Duplicate agent_id check.
        if (slotOf(agentId) != null) {
            val denial = RegistrationResult.Denied(DenialReason.DUPLICATE_AGENT_ID)
            decisionChannel?.record(AuthorityDecision.SpawnDenied(
                agentId          = agentId,
                slot             = slot,
                requestingAgentId = requestingAgentId,
                reason           = DenialReason.DUPLICATE_AGENT_ID.name
            ))
            return denial
        }

        // Step 3: Global ceiling check (max 2 total).
        val totalActive = queryCount("SELECT COUNT(*) FROM active_agents")
        if (totalActive >= GLOBAL_CEILING) {
            val denial = RegistrationResult.Denied(DenialReason.CEILING_REACHED)
            decisionChannel?.record(AuthorityDecision.SpawnDenied(
                agentId          = agentId,
                slot             = slot,
                requestingAgentId = requestingAgentId,
                reason           = DenialReason.CEILING_REACHED.name
            ))
            return denial
        }

        // Step 4: Per-slot ceiling check (max 1 per slot).
        val slotActive = queryCount("SELECT COUNT(*) FROM active_agents WHERE slot = '${slot.name}'")
        if (slotActive >= SLOT_CEILING) {
            val denial = RegistrationResult.Denied(DenialReason.SLOT_OCCUPIED)
            decisionChannel?.record(AuthorityDecision.SpawnDenied(
                agentId          = agentId,
                slot             = slot,
                requestingAgentId = requestingAgentId,
                reason           = DenialReason.SLOT_OCCUPIED.name
            ))
            return denial
        }

        // Step 5: All checks passed — insert.
        try {
            conn.autoCommit = false
            conn.prepareStatement(
                "INSERT INTO active_agents (agent_id, slot, registered_at_ms) VALUES (?, ?, ?)"
            ).use { ps ->
                ps.setString(1, agentId)
                ps.setString(2, slot.name)
                ps.setLong(3, System.currentTimeMillis())
                ps.executeUpdate()
            }
            conn.commit()
        } catch (e: Exception) {
            runCatching { conn.rollback() }
            // The DB PRIMARY KEY constraint is the last line of defence.
            // A duplicate key exception here means DUPLICATE_AGENT_ID.
            // Any other exception is also treated as DUPLICATE_AGENT_ID
            // to preserve fail-closed semantics.
            val denial = RegistrationResult.Denied(DenialReason.DUPLICATE_AGENT_ID)
            decisionChannel?.record(AuthorityDecision.SpawnDenied(
                agentId          = agentId,
                slot             = slot,
                requestingAgentId = requestingAgentId,
                reason           = DenialReason.DUPLICATE_AGENT_ID.name
            ))
            return denial
        }

        decisionChannel?.record(AuthorityDecision.SpawnIssued(
            agentId          = agentId,
            slot             = slot,
            requestingAgentId = requestingAgentId
        ))
        return RegistrationResult.Registered
    }

    @Synchronized
    override fun deregister(agentId: String) {
        try {
            conn.autoCommit = false
            conn.prepareStatement("DELETE FROM active_agents WHERE agent_id = ?").use { ps ->
                ps.setString(1, agentId)
                ps.executeUpdate()
            }
            conn.commit()
        } catch (e: Exception) {
            runCatching { conn.rollback() }
            // Deregister is best-effort; failure is not propagated.
        }
    }

    @Synchronized
    override fun activeCount(): Int = queryCount("SELECT COUNT(*) FROM active_agents")

    @Synchronized
    override fun activeAgents(): List<ActiveAgentRow> {
        val rows = mutableListOf<ActiveAgentRow>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT agent_id, slot, registered_at_ms FROM active_agents ORDER BY registered_at_ms ASC"
            ).use { rs ->
                while (rs.next()) {
                    rows.add(ActiveAgentRow(
                        agentId        = rs.getString("agent_id"),
                        slot           = AgentSlot.valueOf(rs.getString("slot")),
                        registeredAtMs = rs.getLong("registered_at_ms")
                    ))
                }
            }
        }
        return rows
    }

    @Synchronized
    override fun slotOf(agentId: String): AgentSlot? {
        return conn.prepareStatement(
            "SELECT slot FROM active_agents WHERE agent_id = ?"
        ).use { ps ->
            ps.setString(1, agentId)
            ps.executeQuery().use { rs ->
                if (rs.next()) AgentSlot.valueOf(rs.getString("slot")) else null
            }
        }
    }

    // Not synchronized — must only be called from within a synchronized block.
    private fun queryCount(sql: String): Int =
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
}
