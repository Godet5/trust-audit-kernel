package com.aegisone.zonea

import com.aegisone.trust.Manifest
import com.aegisone.trust.ZoneAAccess
import com.aegisone.trust.ZoneAStore
import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream

/**
 * File-backed ZoneAStore for Phase 0.
 *
 * Stores the manifest, platform trust root key, and version floor as files
 * under [storeDir]. Intended to live in a protected directory on the device
 * (e.g. app-private storage). TEE/Strongbox backing is the Phase 3 target;
 * this implementation provides the correct interface with durable file semantics.
 *
 * File layout:
 *   storeDir/
 *     manifest.json       active manifest (atomic replace target)
 *     manifest.tmp        write staging area (always removed after commit)
 *     platform_key.bin    platform trust root public key (raw bytes)
 *     version_floor       current version floor (decimal integer, UTF-8)
 *
 * Write protocol (crash-safe atomic replace):
 *   1. Write full content to manifest.tmp
 *   2. flush() + fd.sync() — data durable before directory is updated
 *   3. renameTo(manifest.json) — atomic on Linux/Android
 *   4. Best-effort parent directory sync
 *
 * Version monotonicity: provision() rejects any write where
 * record.version <= currentManifest.version. This enforces the anti-rollback
 * guarantee required by systemCapabilityManifestPolicy-v1.md §4.
 *
 * acquireAccess() returns null if any required file is absent, which causes
 * TrustInit to report a boot failure rather than proceeding with partial state.
 *
 * Source: systemCapabilityManifestPolicy-v1.md §3, §4; receiptDurabilitySpec-v1.md §4 (fsync pattern)
 */
class FileBackedZoneAStore(private val storeDir: File) : ZoneAStore {

    private val manifestFile    = File(storeDir, "manifest.json")
    private val manifestTmp     = File(storeDir, "manifest.tmp")
    private val platformKeyFile = File(storeDir, "platform_key.bin")
    private val versionFloorFile = File(storeDir, "version_floor")

    private val gson = Gson()

    // --- ZoneAStore interface ---

    override fun acquireAccess(): ZoneAAccess? {
        if (!manifestFile.exists() || !platformKeyFile.exists() || !versionFloorFile.exists()) {
            return null
        }
        return FileBackedZoneAAccess()
    }

    // --- Provisioning write path (not part of ZoneAStore interface) ---

    /**
     * Write a new manifest record to Zone A storage.
     *
     * Returns false (without modifying stored state) if:
     *   - An existing manifest is present and record.version <= existing.version (anti-rollback)
     *   - Any I/O error occurs during the atomic write sequence
     *
     * platformKey and versionFloor are written unconditionally on success.
     * In production these would be provisioned by a separate secure channel;
     * for Phase 0 they are co-provisioned with the manifest.
     */
    fun provision(record: ManifestRecord, platformKey: ByteArray, versionFloor: Int): Boolean {
        // Anti-rollback: reject if new version does not strictly advance
        if (manifestFile.exists()) {
            val current = readRecord() ?: return false
            if (record.version <= current.version) return false
        }

        return try {
            storeDir.mkdirs()
            writePlatformKey(platformKey)
            writeVersionFloor(versionFloor)
            writeManifestAtomic(record)
            true
        } catch (e: Exception) {
            false
        }
    }

    // --- Private write helpers ---

    private fun writeManifestAtomic(record: ManifestRecord) {
        val json = gson.toJson(record).toByteArray(Charsets.UTF_8)

        // Write and fsync temp file before rename
        FileOutputStream(manifestTmp).use { fos ->
            fos.write(json)
            fos.flush()
            fos.fd.sync()
        }

        // Atomic rename — on Linux/Android this is guaranteed atomic by the kernel
        val renamed = manifestTmp.renameTo(manifestFile)
        if (!renamed) throw RuntimeException("Atomic rename failed: $manifestTmp -> $manifestFile")

        // Best-effort parent directory sync (ensures directory entry is durable)
        runCatching { syncDirectory(storeDir) }
    }

    private fun writePlatformKey(key: ByteArray) {
        FileOutputStream(platformKeyFile).use { fos ->
            fos.write(key)
            fos.flush()
            fos.fd.sync()
        }
    }

    private fun writeVersionFloor(floor: Int) {
        FileOutputStream(versionFloorFile).use { fos ->
            fos.write(floor.toString().toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
    }

    private fun syncDirectory(dir: File) {
        // Sync directory entry — ensures the rename is durable at the filesystem level.
        // Uses RandomAccessFile in read mode; fails silently if the OS doesn't support it.
        java.io.RandomAccessFile(dir, "r").use { it.fd.sync() }
    }

    // --- Shared read helper (used by acquireAccess and provision) ---

    private fun readRecord(): ManifestRecord? = runCatching {
        gson.fromJson(manifestFile.readText(Charsets.UTF_8), ManifestRecord::class.java)
    }.getOrNull()

    // --- Inner access implementation ---

    private inner class FileBackedZoneAAccess : ZoneAAccess {

        override fun readManifest(): Manifest? = runCatching {
            readRecord()?.toManifest()
        }.getOrNull()

        override fun readPlatformTrustRootPublicKey(): ByteArray =
            platformKeyFile.readBytes()

        override fun readVersionFloor(): Int =
            versionFloorFile.readText(Charsets.UTF_8).trim().toInt()

        override fun release() {
            // No lock to release in Phase 0 file-backed implementation.
            // TEE/Strongbox implementation will close the secure channel here.
        }
    }
}
