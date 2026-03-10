package com.aegisone.trust

/**
 * TrustInit owns the boot-time sequence from power-on to Broker availability.
 * It is the only module permitted to interact with Zone A during manifest verification.
 *
 * Each TrustInit instance has a unique identity. The Broker is configured with one
 * trusted identity at construction. Only BootSignals from that identity are accepted.
 *
 * Traces to: I-1, I-2
 * Source: implementationMap-trust-and-audit-v1.md §1, §2.2.2
 */
class TrustInit(
    private val zoneAStore: ZoneAStore
) {
    /** Opaque identity for this TrustInit instance. Configure the Broker with this. */
    val identity: TrustInitIdentity = TrustInitIdentity()

    private var sequenceCounter = 0

    /**
     * Execute the full manifest verification sequence (phases 0–7).
     * Returns a BootSignal carrying this instance's identity and a monotonic
     * sequence number. The signal is the only proof of verification the Broker accepts.
     */
    fun verifyManifest(): BootSignal {
        val seq = ++sequenceCounter

        val access = zoneAStore.acquireAccess()
            ?: return BootSignal.Failed(
                step = VerificationStep.ZONE_A_ACCESS,
                reason = "Zone A access unavailable",
                issuer = identity
            )

        try {
            val manifest = access.readManifest()
                ?: return BootSignal.Failed(
                    step = VerificationStep.MANIFEST_LOAD,
                    reason = "Manifest not found in Zone A",
                    issuer = identity
                )

            if (!manifest.isSchemaValid()) {
                return BootSignal.Failed(
                    step = VerificationStep.SCHEMA_VERIFY,
                    reason = "SCHEMA_INVALID",
                    issuer = identity
                )
            }

            val platformKey = access.readPlatformTrustRootPublicKey()
            if (!manifest.verifySignature(platformKey)) {
                return BootSignal.Failed(
                    step = VerificationStep.SIGNATURE_VERIFY,
                    reason = "SIGNATURE_INVALID",
                    issuer = identity
                )
            }

            val versionFloor = access.readVersionFloor()
            if (manifest.version < versionFloor) {
                return BootSignal.Failed(
                    step = VerificationStep.VERSION_FLOOR,
                    reason = "VERSION_BELOW_FLOOR",
                    issuer = identity
                )
            }

            if (manifest.createdAt > System.currentTimeMillis()) {
                return BootSignal.Failed(
                    step = VerificationStep.TIMESTAMP,
                    reason = "TIMESTAMP_IN_FUTURE",
                    issuer = identity
                )
            }

            // Phase 7: derive runtime objects; manifest reference not retained
            return BootSignal.Verified(
                manifestVersion = manifest.version,
                grantList = manifest.deriveGrantList(),
                agentEnumeration = manifest.deriveAgentEnumeration(),
                sequenceNumber = seq,
                issuer = identity
            )
        } finally {
            access.release()
        }
    }
}

enum class VerificationStep {
    ZONE_A_ACCESS,
    MANIFEST_LOAD,
    SCHEMA_VERIFY,
    SIGNATURE_VERIFY,
    VERSION_FLOOR,
    TIMESTAMP
}
