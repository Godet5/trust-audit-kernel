package com.aegisone.broker

/**
 * Broker-domain types used across module boundaries.
 *
 * Source: implementationMap-trust-and-audit-v1.md §2, agentPolicyEngine-v2.1
 */

/** Authority source for a capability grant. */
enum class GrantAuthority {
    USER_DIRECT,
    USER_DELEGATED,
    SYSTEM_POLICY
}

/** Agent role classification. */
enum class AgentRole {
    INTENT_INTERPRETER,
    PLANNER,
    EXECUTOR,
    VERIFIER,
    PRIVACY_SENTINEL,
    MEMORY_STEWARD,
    UI_NARRATOR,
    HELPER_SPECIALIST
}

/** A capability grant derived from the manifest during TrustInit verification. */
data class CapabilityGrant(
    val capabilityName: String,
    val targetRole: AgentRole,
    val authority: GrantAuthority
)
