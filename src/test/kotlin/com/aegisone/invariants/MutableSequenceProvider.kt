package com.aegisone.invariants

import com.aegisone.execution.SequenceProvider

/**
 * Test double for SequenceProvider.
 * Auto-increments by default; supports forced reset to simulate mid-session sequence decrease.
 * Used in I3-T6 to inject a sequence reset and verify the coordinator detects it.
 */
class MutableSequenceProvider : SequenceProvider {
    private var current = 0

    override fun next(): Int = ++current

    /** Force the counter to a specific value. Next call to next() returns that value + 1. */
    fun reset(to: Int) {
        current = to - 1
    }
}
