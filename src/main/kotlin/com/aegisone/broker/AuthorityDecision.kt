package com.aegisone.broker

/**
 * Durable records of authority decisions made by the CapabilityBroker.
 *
 * Every grant attempt — whether issued or denied — produces one AuthorityDecision.
 * These records form a queryable audit trail of all authority decisions, separate
 * from the execution receipt trail (RECEIPT_CHANNEL) and the policy violation log.
 *
 * Fields carried:
 *   capabilityName  — what was requested
 *   targetRole      — for whom
 *   authority       — by what authority (USER_DIRECT, SYSTEM_POLICY, ...)
 *   manifestVersion — the verified manifest version at decision time (null if broker not yet initialized)
 *   floorVersion    — the floor re-checked at grant time (only for SYSTEM_POLICY, null otherwise)
 *   reason          — denial code (GrantDenied only): GRANT_BEFORE_INIT,
 *                     SYSTEM_POLICY_UNAVAILABLE, MANIFEST_VERSION_BELOW_FLOOR,
 *                     ROLE_NOT_IN_MANIFEST, GRANT_NOT_IN_MANIFEST
 *
 * Source: agentPolicyEngine-v2.1 §8, implementationMap-trust-and-audit-v1.md §2.2
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
}

/**
 * Channel for durable authority decision records.
 * Separate from ReceiptChannel — authority decisions and execution receipts
 * are distinct audit streams. Returns true if durably acknowledged.
 */
interface AuthorityDecisionChannel {
    fun record(decision: AuthorityDecision): Boolean
}
