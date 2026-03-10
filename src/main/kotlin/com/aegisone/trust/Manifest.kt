package com.aegisone.trust

import com.aegisone.broker.AgentRole
import com.aegisone.broker.CapabilityGrant

/**
 * Manifest representation. Exists only during TrustInit verification.
 * After phase_7, only derived GrantList and AgentEnumeration survive in runtime.
 *
 * Source: implementationMap-trust-and-audit-v1.md §1.2, systemCapabilityManifestPolicy-v1
 */
data class Manifest(
    val version: Int,
    val createdAt: Long,
    private val schemaValid: Boolean,
    private val signature: ByteArray,
    private val grants: List<CapabilityGrant>,
    private val agents: Map<String, AgentRole>
) {
    fun isSchemaValid(): Boolean = schemaValid

    fun verifySignature(platformKey: ByteArray): Boolean {
        // Phase 0: signature verification is simulated.
        // Real implementation: verify signature against platformKey using
        // platform crypto (e.g., KeyStore, Strongbox).
        // For harness: signature bytes must equal platformKey bytes (test convention).
        return signature.contentEquals(platformKey)
    }

    fun deriveGrantList(): List<CapabilityGrant> = grants.toList()

    fun deriveAgentEnumeration(): Map<String, AgentRole> = agents.toMap()
}
