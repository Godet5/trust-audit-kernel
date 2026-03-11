package com.aegisone.db

import com.aegisone.broker.AuthorityDecision
import com.aegisone.broker.AuthorityDecisionChannel
import com.aegisone.execution.ActiveAgentRow
import com.aegisone.execution.AgentRegistry
import com.aegisone.execution.AgentSlot
import com.aegisone.execution.DenialReason
import com.aegisone.execution.RegistrationResult

/**
 * SQLite-backed AgentRegistry.
 *
 * Enforces the Phase 0 ceiling (D-NC3): max 2 agents globally, max 1 per slot
 * (PRIMARY, HELPER), no recursive spawn from HELPER agents.
 *
 * Atomicity guarantee (dual-layer):
 *   Layer 1 (JVM): synchronized(shared) serializes threads within one process.
 *   Layer 2 (SQLite): BEGIN IMMEDIATE serializes across processes. The entire
 *   check-and-insert sequence (recursive spawn check, duplicate check, global
 *   ceiling, per-slot ceiling, INSERT) executes inside a single IMMEDIATE
 *   transaction. BEGIN IMMEDIATE acquires SQLite's reserved lock before any
 *   reads, preventing concurrent connections from interleaving their own
 *   check-and-insert between this connection's count read and insert.
 *
 * The DB PRIMARY KEY on agent_id is a third-layer safety net that rejects
 * any duplicate that somehow bypasses both locks.
 *
 * Thread safety: synchronized on [shared] (intra-process) + BEGIN IMMEDIATE
 * (inter-process). Closes ACN-1 and G-1.
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
    private val shared: SharedConnection,
    private val decisionChannel: AuthorityDecisionChannel? = null
) : AgentRegistry {

    companion object {
        private const val GLOBAL_CEILING = 2
        private const val SLOT_CEILING   = 1
    }

    override fun register(
        agentId: String,
        slot: AgentSlot,
        requestingAgentId: String?
    ): RegistrationResult = synchronized(shared) {
        // BEGIN IMMEDIATE acquires SQLite's reserved lock before any reads.
        // This serializes the entire check-and-insert across connections (processes).
        shared.conn.autoCommit = true
        shared.conn.createStatement().use { it.execute("BEGIN IMMEDIATE") }

        val result: RegistrationResult
        try {
            val denial = doRegisterChecks(agentId, slot, requestingAgentId)
            if (denial != null) {
                shared.conn.createStatement().use { it.execute("ROLLBACK") }
                result = denial
            } else {
                // All checks passed — insert within the same IMMEDIATE transaction.
                shared.conn.prepareStatement(
                    "INSERT INTO active_agents (agent_id, slot, registered_at_ms) VALUES (?, ?, ?)"
                ).use { ps ->
                    ps.setString(1, agentId)
                    ps.setString(2, slot.name)
                    ps.setLong(3, System.currentTimeMillis())
                    ps.executeUpdate()
                }
                shared.conn.createStatement().use { it.execute("COMMIT") }
                result = RegistrationResult.Registered
            }
        } catch (e: Exception) {
            runCatching { shared.conn.createStatement().use { it.execute("ROLLBACK") } }
            val denial = RegistrationResult.Denied(DenialReason.DUPLICATE_AGENT_ID)
            // Decision recording is outside the transaction (best-effort).
            decisionChannel?.record(AuthorityDecision.SpawnDenied(
                agentId          = agentId,
                slot             = slot,
                requestingAgentId = requestingAgentId,
                reason           = DenialReason.DUPLICATE_AGENT_ID.name
            ))
            return denial
        }

        // Decision recording happens AFTER the transaction closes.
        // decisionChannel?.record() starts its own transaction — cannot nest
        // inside the IMMEDIATE transaction above.
        when (result) {
            is RegistrationResult.Registered -> {
                decisionChannel?.record(AuthorityDecision.SpawnIssued(
                    agentId          = agentId,
                    slot             = slot,
                    requestingAgentId = requestingAgentId
                ))
            }
            is RegistrationResult.Denied -> {
                decisionChannel?.record(AuthorityDecision.SpawnDenied(
                    agentId          = agentId,
                    slot             = slot,
                    requestingAgentId = requestingAgentId,
                    reason           = result.reason.name
                ))
            }
        }
        return result
    }

    /**
     * Runs steps 1–4 of the registration checks. MUST be called inside an
     * active BEGIN IMMEDIATE transaction — all reads see a serialized snapshot.
     *
     * Returns null if all checks pass (caller should proceed to INSERT).
     * Returns a Denied result if any check fails (caller should ROLLBACK).
     *
     * Does NOT record decisions — caller records after the transaction closes,
     * since the decision channel starts its own transaction.
     */
    private fun doRegisterChecks(
        agentId: String,
        slot: AgentSlot,
        requestingAgentId: String?
    ): RegistrationResult.Denied? {
        // Step 1: Recursive spawn check.
        if (requestingAgentId != null && slotOfInternal(requestingAgentId) == AgentSlot.HELPER) {
            return RegistrationResult.Denied(DenialReason.RECURSIVE_SPAWN_DENIED)
        }

        // Step 2: Duplicate agent_id check.
        if (slotOfInternal(agentId) != null) {
            return RegistrationResult.Denied(DenialReason.DUPLICATE_AGENT_ID)
        }

        // Step 3: Global ceiling check (max 2 total).
        val totalActive = queryCount("SELECT COUNT(*) FROM active_agents")
        if (totalActive >= GLOBAL_CEILING) {
            return RegistrationResult.Denied(DenialReason.CEILING_REACHED)
        }

        // Step 4: Per-slot ceiling check (max 1 per slot).
        val slotActive = queryCountParam(
            "SELECT COUNT(*) FROM active_agents WHERE slot = ?", slot.name
        )
        if (slotActive >= SLOT_CEILING) {
            return RegistrationResult.Denied(DenialReason.SLOT_OCCUPIED)
        }

        return null  // all checks passed
    }

    override fun deregister(agentId: String): Unit = synchronized(shared) {
        try {
            shared.conn.autoCommit = true
            shared.conn.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            shared.conn.prepareStatement("DELETE FROM active_agents WHERE agent_id = ?").use { ps ->
                ps.setString(1, agentId)
                ps.executeUpdate()
            }
            shared.conn.createStatement().use { it.execute("COMMIT") }
        } catch (e: Exception) {
            runCatching { shared.conn.createStatement().use { it.execute("ROLLBACK") } }
            // Deregister is best-effort; failure is not propagated.
        }
    }

    override fun activeCount(): Int = synchronized(shared) {
        queryCount("SELECT COUNT(*) FROM active_agents")
    }

    override fun activeAgents(): List<ActiveAgentRow> = synchronized(shared) {
        val rows = mutableListOf<ActiveAgentRow>()
        shared.conn.createStatement().use { stmt ->
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
        rows
    }

    override fun slotOf(agentId: String): AgentSlot? = synchronized(shared) {
        slotOfInternal(agentId)
    }

    override fun <T> checkAndBegin(agentId: String, block: () -> T): T? = synchronized(shared) {
        // slotOfInternal runs inside synchronized(shared) — same lock as register()/deregister().
        // The block runs while this lock is still held, so no concurrent deregister()
        // can interleave between the registration check and whatever the block does
        // (e.g., writing a PENDING record).
        if (slotOfInternal(agentId) == null) return null
        block()
    }

    // Internal — must only be called from within a synchronized(shared) block.
    private fun slotOfInternal(agentId: String): AgentSlot? {
        return shared.conn.prepareStatement(
            "SELECT slot FROM active_agents WHERE agent_id = ?"
        ).use { ps ->
            ps.setString(1, agentId)
            ps.executeQuery().use { rs ->
                if (rs.next()) AgentSlot.valueOf(rs.getString("slot")) else null
            }
        }
    }

    // Internal — must only be called from within a synchronized(shared) block.
    private fun queryCount(sql: String): Int =
        shared.conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }

    // Parameterized count query — used for slot ceiling check (AOE-1 fix).
    private fun queryCountParam(sql: String, param: String): Int =
        shared.conn.prepareStatement(sql).use { ps ->
            ps.setString(1, param)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
}
