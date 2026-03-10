package com.aegisone.invariants

import com.aegisone.broker.AgentRole
import com.aegisone.broker.CapabilityGrant
import com.aegisone.broker.GrantAuthority
import com.aegisone.trust.Manifest
import com.aegisone.trust.ZoneAAccess
import com.aegisone.trust.ZoneAStore

/**
 * In-memory Zone A store for Phase 0 harness tests.
 * Configurable to simulate: missing manifest, invalid schema, bad signature,
 * version below floor, and unavailable Zone A.
 */
class TestZoneAStore(
    private val manifest: Manifest? = null,
    private val platformKey: ByteArray = byteArrayOf(1, 2, 3, 4),
    private val versionFloor: Int = 1,
    private val available: Boolean = true
) : ZoneAStore {

    override fun acquireAccess(): ZoneAAccess? {
        if (!available) return null
        return TestZoneAAccess(manifest, platformKey, versionFloor)
    }
}

private class TestZoneAAccess(
    private val manifest: Manifest?,
    private val platformKey: ByteArray,
    private val versionFloor: Int
) : ZoneAAccess {
    override fun readManifest(): Manifest? = manifest
    override fun readPlatformTrustRootPublicKey(): ByteArray = platformKey
    override fun readVersionFloor(): Int = versionFloor
    override fun release() { /* no-op for in-memory store */ }
}

/** Factory for creating test manifests with controlled properties. */
object TestManifests {
    private val DEFAULT_KEY = byteArrayOf(1, 2, 3, 4)

    fun valid(
        version: Int = 1,
        createdAt: Long = System.currentTimeMillis() - 1000,
        key: ByteArray = DEFAULT_KEY
    ) = Manifest(
        version = version,
        createdAt = createdAt,
        schemaValid = true,
        signature = key.copyOf(),
        grants = listOf(
            CapabilityGrant("AGENT_SPAWN", AgentRole.PLANNER, GrantAuthority.SYSTEM_POLICY)
        ),
        agents = mapOf("planner-01" to AgentRole.PLANNER)
    )

    fun schemaInvalid() = Manifest(
        version = 1,
        createdAt = System.currentTimeMillis() - 1000,
        schemaValid = false,
        signature = DEFAULT_KEY.copyOf(),
        grants = emptyList(),
        agents = emptyMap()
    )

    fun signatureInvalid() = Manifest(
        version = 1,
        createdAt = System.currentTimeMillis() - 1000,
        schemaValid = true,
        signature = byteArrayOf(9, 9, 9, 9), // wrong key
        grants = emptyList(),
        agents = emptyMap()
    )

    fun versionBelowFloor(version: Int = 0) = Manifest(
        version = version,
        createdAt = System.currentTimeMillis() - 1000,
        schemaValid = true,
        signature = DEFAULT_KEY.copyOf(),
        grants = emptyList(),
        agents = emptyMap()
    )
}
