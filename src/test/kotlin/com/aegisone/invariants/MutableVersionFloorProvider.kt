package com.aegisone.invariants

import com.aegisone.trust.VersionFloorProvider

/**
 * Mutable test double for VersionFloorProvider.
 * Allows tests to simulate external floor updates mid-session.
 */
class MutableVersionFloorProvider(var currentFloor: Int = 1) : VersionFloorProvider {
    override fun currentFloor(): Int = currentFloor
}
