package com.aegisone.trust

import com.aegisone.broker.AgentRole
import com.aegisone.broker.CapabilityGrant

/**
 * The authenticated output of TrustInit verification. This is the only object
 * the Broker accepts as proof of manifest verification.
 *
 * A BootSignal.Verified carries:
 *   - derived runtime data (grant list, agent enumeration)
 *   - a monotonic sequence number scoped to the issuing TrustInit instance
 *   - the issuer identity (reference to the TrustInit that produced it)
 *
 * The Broker validates issuer identity, sequence non-regression, and
 * manifest version non-regression before accepting a Verified signal.
 *
 * Source: implementationMap-trust-and-audit-v1.md §2.2.2 (TrustInitSignalPolicy)
 */
sealed class BootSignal {

    data class Verified(
        val manifestVersion: Int,
        val grantList: List<CapabilityGrant>,
        val agentEnumeration: Map<String, AgentRole>,
        val sequenceNumber: Int,
        val issuer: TrustInitIdentity
    ) : BootSignal()

    data class Failed(
        val step: VerificationStep,
        val reason: String,
        val issuer: TrustInitIdentity
    ) : BootSignal()
}
