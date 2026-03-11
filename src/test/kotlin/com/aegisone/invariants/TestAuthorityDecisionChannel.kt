package com.aegisone.invariants

import com.aegisone.broker.AuthorityDecision
import com.aegisone.broker.AuthorityDecisionChannel

/**
 * In-memory AuthorityDecisionChannel for invariant and unit tests.
 *
 * [available] controls whether record() returns true or false.
 * All recorded decisions are collected in [decisions] for assertion.
 */
class TestAuthorityDecisionChannel(
    var available: Boolean = true
) : AuthorityDecisionChannel {
    val decisions = mutableListOf<AuthorityDecision>()

    override fun record(decision: AuthorityDecision): Boolean {
        if (!available) return false
        decisions.add(decision)
        return true
    }

    fun grantIssued(): List<AuthorityDecision.GrantIssued> =
        decisions.filterIsInstance<AuthorityDecision.GrantIssued>()

    fun grantDenied(): List<AuthorityDecision.GrantDenied> =
        decisions.filterIsInstance<AuthorityDecision.GrantDenied>()
}
