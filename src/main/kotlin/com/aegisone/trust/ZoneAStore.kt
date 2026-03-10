package com.aegisone.trust

/**
 * Zone A key store interface. TrustInit is the only consumer.
 * Implementations: real TEE/Strongbox for production, in-memory for Phase 0 harness.
 *
 * Source: implementationMap-trust-and-audit-v1.md §1.1
 */
interface ZoneAStore {
    fun acquireAccess(): ZoneAAccess?
}

interface ZoneAAccess {
    fun readManifest(): Manifest?
    fun readPlatformTrustRootPublicKey(): ByteArray
    fun readVersionFloor(): Int
    fun release()
}
