package com.aegisone.boot

import com.aegisone.broker.CapabilityBroker
import com.aegisone.receipt.ReceiptChannel
import com.aegisone.trust.BootSignal
import com.aegisone.trust.TrustInit
import com.aegisone.trust.VersionFloorProvider
import com.aegisone.trust.ZoneAStore

/**
 * Executes the boot sequence: TrustInit → BootSignal → CapabilityBroker.
 *
 * This is the only path through which a CapabilityBroker reaches ACTIVE state.
 * No other module constructs a Broker independently — all downstream capability
 * issuance and action execution must pass through a broker returned here.
 *
 * Returns BootResult.Active with a ready broker on success, or
 * BootResult.Failed with the failure reason if verification or broker
 * initialization fails.
 *
 * Scope: does not wire ExecutionCoordinator or MemorySteward — those are
 * caller responsibilities once a broker is in hand.
 *
 * Traces to: I-1, I-2
 * Source: implementationMap-trust-and-audit-v1.md §1, §2, §4.1
 */
class BootOrchestrator(
    private val zoneAStore: ZoneAStore,
    private val versionFloorProvider: VersionFloorProvider,
    private val receiptChannel: ReceiptChannel
) {
    fun boot(): BootResult {
        val trustInit = TrustInit(zoneAStore)
        val signal = trustInit.verifyManifest()

        val broker = CapabilityBroker(
            receiptChannel = receiptChannel,
            trustedIssuer = trustInit.identity,
            versionFloorProvider = versionFloorProvider
        )

        val accepted = broker.initialize(signal)

        return if (accepted) {
            BootResult.Active(broker)
        } else {
            val reason = when (signal) {
                is BootSignal.Failed ->
                    "Verification failed at ${signal.step}: ${signal.reason}"
                is BootSignal.Verified ->
                    "Broker rejected verified signal (origin/sequence/version check failed)"
            }
            BootResult.Failed(reason)
        }
    }
}

sealed class BootResult {
    /** Boot succeeded. [broker] is in ACTIVE state and ready for grant issuance. */
    data class Active(val broker: CapabilityBroker) : BootResult()

    /** Boot failed. [reason] describes the first failure point. */
    data class Failed(val reason: String) : BootResult()
}
