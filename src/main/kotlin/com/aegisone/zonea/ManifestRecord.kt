package com.aegisone.zonea

import com.aegisone.broker.AgentRole
import com.aegisone.broker.CapabilityGrant
import com.aegisone.broker.GrantAuthority
import com.aegisone.trust.Manifest

/**
 * Storage DTO for Manifest. All fields are public so Gson can serialize them.
 *
 * Manifest's runtime fields (schemaValid, signature, grants, agents) are private
 * to prevent leakage after TrustInit clears them. ManifestRecord is the write-side
 * representation used only during provisioning and storage. It is never exposed
 * to broker or executor modules.
 *
 * Signature is hex-encoded to avoid base64 / charset encoding issues in JSON.
 *
 * Source: systemCapabilityManifestPolicy-v1.md §3 (Zone A storage contract)
 */
data class ManifestRecord(
    val version: Int,
    val createdAt: Long,
    val schemaValid: Boolean,
    val signatureHex: String,
    val grants: List<GrantRecord>,
    val agents: Map<String, String>   // agentId -> AgentRole.name
) {
    /**
     * Reconstruct a runtime Manifest from this storage record.
     * Called during readManifest() inside FileBackedZoneAAccess.
     * Throws IllegalArgumentException if a stored role or authority name is not a known enum value.
     */
    fun toManifest(): Manifest = Manifest(
        version = version,
        createdAt = createdAt,
        schemaValid = schemaValid,
        signature = hexToBytes(signatureHex),
        grants = grants.map { g ->
            CapabilityGrant(
                capabilityName = g.capabilityName,
                targetRole = AgentRole.valueOf(g.targetRole),
                authority = GrantAuthority.valueOf(g.authority)
            )
        },
        agents = agents.mapValues { AgentRole.valueOf(it.value) }
    )

    companion object {
        fun bytesToHex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }

        fun hexToBytes(hex: String): ByteArray =
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

/** Grant entry as stored on disk. All fields are enum names as strings. */
data class GrantRecord(
    val capabilityName: String,
    val targetRole: String,     // AgentRole.name
    val authority: String       // GrantAuthority.name
)
