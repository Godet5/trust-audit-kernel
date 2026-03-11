package com.aegisone.broker

/**
 * Durable records of authority decisions made by the CapabilityBroker
 * and AgentRegistry.
 *
 * Grant subtypes (GrantIssued, GrantDenied) record capability issuance and
 * denial from the broker. Spawn subtypes (SpawnIssued, SpawnDenied) record
 * agent slot registration and denial from the AgentRegistry.
 *
 * Both streams write to the same authority_decisions table. The decision_type
 * discriminator identifies the subtype. Operator tooling reads all rows uniformly
 * via SystemInspector.recentDecisions().
 *
 * Grant fields:
 *   capabilityName  — what was requested
 *   targetRole      — for whom
 *   authority       — by what authority (USER_DIRECT, SYSTEM_POLICY, ...)
 *   manifestVersion — verified manifest version at decision time
 *   floorVersion    — floor re-checked at grant time (SYSTEM_POLICY only)
 *   reason          — denial code (GrantDenied only)
 *
 * Spawn fields (repurpose grant columns for Phase 0 simplicity):
 *   capabilityName  → agentId being registered or denied
 *   targetRole      → slot (PRIMARY maps to EXECUTOR, HELPER maps to HELPER_SPECIALIST)
 *   authority       → requestingAgentId ("SYSTEM" if system-initiated)
 *   reason          → DenialReason name (SpawnDenied only)
 *
 * Source: agentPolicyEngine-v2.1 §8, §D-NC3, implementationMap-trust-and-audit-v1.md §2.2
 */
sealed class AuthorityDecision {
    abstract val timestamp: Long

    data class GrantIssued(
        val capabilityName: String,
        val targetRole: AgentRole,
        val authority: GrantAuthority,
        val manifestVersion: Int?,
        val floorVersion: Int?,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AuthorityDecision()

    data class GrantDenied(
        val capabilityName: String,
        val targetRole: AgentRole,
        val authority: GrantAuthority,
        val reason: String,
        val manifestVersion: Int?,
        val floorVersion: Int?,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AuthorityDecision()

    /**
     * Agent slot successfully assigned.
     * [agentId] was registered in [slot] on behalf of [requestingAgentId].
     * [requestingAgentId] is null when the system initiates the spawn.
     */
    data class SpawnIssued(
        val agentId: String,
        val slot: com.aegisone.execution.AgentSlot,
        val requestingAgentId: String?,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AuthorityDecision()

    /**
     * Agent slot denied.
     * [agentId] was refused registration with [reason].
     */
    data class SpawnDenied(
        val agentId: String,
        val slot: com.aegisone.execution.AgentSlot,
        val requestingAgentId: String?,
        val reason: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AuthorityDecision()
}

/**
 * Channel for durable authority decision records.
 * Separate from ReceiptChannel — authority decisions and execution receipts
 * are distinct audit streams. Returns true if durably acknowledged.
 */
interface AuthorityDecisionChannel {
    fun record(decision: AuthorityDecision): Boolean
}
