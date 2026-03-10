package com.aegisone.invariants

import com.aegisone.review.MemoryEvent
import com.aegisone.review.MemoryEventChannel

/**
 * In-memory event channel for Phase 0 harness tests.
 * Collects MemoryEvent notifications for post-test assertion.
 * Used in I4-T7 to verify MEMORY_EVENT_CHANNEL notification is emitted on successful write.
 */
class TestMemoryEventChannel : MemoryEventChannel {
    val events = mutableListOf<MemoryEvent>()

    override fun notify(event: MemoryEvent) {
        events.add(event)
    }
}
