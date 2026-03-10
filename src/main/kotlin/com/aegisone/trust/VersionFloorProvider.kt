package com.aegisone.trust

/**
 * Provides the current manifest version floor. Queried by the Capability Broker
 * at SYSTEM_POLICY grant issuance time to detect stale manifests.
 *
 * The floor is stored in Zone A separately from the manifest. It can only increase.
 * The Broker does not cache this value — it must be re-read at each grant issuance.
 *
 * Source: implementationMap-trust-and-audit-v1.md §2.2, §2.5 (I2-T3)
 */
interface VersionFloorProvider {
    fun currentFloor(): Int
}
