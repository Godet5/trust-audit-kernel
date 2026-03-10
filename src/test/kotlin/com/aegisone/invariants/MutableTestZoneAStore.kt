package com.aegisone.invariants

import com.aegisone.trust.Manifest
import com.aegisone.trust.ZoneAAccess
import com.aegisone.trust.ZoneAStore

/**
 * Mutable Zone A store — allows tests to update manifest/key mid-session
 * to simulate Zone A changes between re-verification calls on the same TrustInit.
 * Used in I1-T4 (re-verification reads fresh from Zone A).
 */
class MutableTestZoneAStore(
    var manifest: Manifest? = null,
    var platformKey: ByteArray = byteArrayOf(1, 2, 3, 4),
    var versionFloor: Int = 1
) : ZoneAStore {
    override fun acquireAccess(): ZoneAAccess =
        object : ZoneAAccess {
            override fun readManifest(): Manifest? = manifest
            override fun readPlatformTrustRootPublicKey(): ByteArray = platformKey
            override fun readVersionFloor(): Int = versionFloor
            override fun release() {}
        }
}
