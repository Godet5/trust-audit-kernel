package com.aegisone.execution

/**
 * Registry of currently active agents.
 *
 * Enforces the Phase 0 ceiling (D-NC3): max 2 agents globally,
 * max 1 per slot (PRIMARY, HELPER), no recursive spawn.
 *
 * [register] is the only path to activating an agent.
 * [deregister] is the only path to retiring one.
 *
 * Implementations must make register() atomic with respect to the ceiling check
 * — the count read and the insertion must be part of the same serialized unit.
 * Concurrent register() calls must not produce a transient active count > 2.
 *
 * Source: agentPolicyEngine-v2.1 §D-NC3, §DR-01
 */
interface AgentRegistry {

    /**
     * Attempt to register [agentId] in [slot].
     *
     * [requestingAgentId] is the agent initiating the spawn, if known.
     * If the requesting agent is itself a HELPER, the call is denied as recursive
     * before any ceiling check is performed.
     *
     * Returns [RegistrationResult.Registered] on success.
     * Returns [RegistrationResult.Denied] with a specific [DenialReason] on failure.
     * Denials are written to the [com.aegisone.broker.AuthorityDecisionChannel] if wired.
     */
    fun register(
        agentId: String,
        slot: AgentSlot,
        requestingAgentId: String? = null
    ): RegistrationResult

    /**
     * Retire [agentId]. No-op if the agent is not registered.
     * Does not throw on unknown agentId.
     */
    fun deregister(agentId: String)

    /** Current count of active agents (0–2 under normal operation). */
    fun activeCount(): Int

    /** All currently active agents, in registration order. */
    fun activeAgents(): List<ActiveAgentRow>

    /** Slot of [agentId] if active, null if not registered. */
    fun slotOf(agentId: String): AgentSlot?

    /**
     * Atomically check that [agentId] is currently registered and execute [block]
     * while holding the registration lock. Returns null if [agentId] is not
     * registered (block is not called). Returns the block's result otherwise.
     *
     * Closes G-5a: the registration check and any subsequent action (e.g., writing
     * PENDING) are part of the same serialized unit. A concurrent deregister() call
     * cannot interleave between the check and the block's execution.
     *
     * Implementations must hold the same lock used by register()/deregister()
     * throughout the duration of [block].
     */
    fun <T> checkAndBegin(agentId: String, block: () -> T): T?
}
