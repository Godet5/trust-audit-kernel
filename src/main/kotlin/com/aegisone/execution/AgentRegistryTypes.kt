package com.aegisone.execution

/**
 * Agent slot classification for Phase 0 ceiling enforcement (D-NC3).
 *
 * Two slots exist:
 *   PRIMARY  — a manifest-enumerated primary platform agent (SYSTEM_POLICY authority).
 *              At most 1 may be active at a time.
 *   HELPER   — a user-direct helper specialist (USER_DIRECT authority).
 *              At most 1 may be active at a time.
 *
 * Total ceiling: max 2 agents globally (1 PRIMARY + 1 HELPER).
 * No agent in either slot may exceed its per-slot count.
 *
 * Source: agentPolicyEngine-v2.1 §D-NC3, implementationMap-trust-and-audit-v1.md
 */
enum class AgentSlot {
    PRIMARY,
    HELPER
}

/**
 * Result of an AgentRegistry.register() call.
 *
 * Registered              — agent slot assigned; agent may proceed.
 * DeniedCeilingReached    — global 2-agent ceiling is full; denied.
 * DeniedSlotOccupied      — the requested slot (PRIMARY or HELPER) is already filled; denied.
 * DeniedRecursiveSpawn    — the requesting agent is a HELPER; HELPER cannot spawn; denied.
 * DeniedDuplicateId       — an active agent with this agent_id already exists; denied.
 */
sealed class RegistrationResult {
    object Registered : RegistrationResult() {
        override fun toString() = "Registered"
    }
    data class Denied(val reason: DenialReason) : RegistrationResult()
}

enum class DenialReason {
    CEILING_REACHED,
    SLOT_OCCUPIED,
    RECURSIVE_SPAWN_DENIED,
    DUPLICATE_AGENT_ID
}

/**
 * Snapshot of one active agent row returned by AgentRegistry.activeAgents().
 */
data class ActiveAgentRow(
    val agentId: String,
    val slot: AgentSlot,
    val registeredAtMs: Long
)
