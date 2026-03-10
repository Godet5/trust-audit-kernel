package com.aegisone.invariants

import com.aegisone.execution.AuditFailureChannel
import com.aegisone.execution.AuditRecord

/**
 * In-memory audit failure channel for Phase 0 harness tests.
 * Configurable to simulate: channel unavailable, write failure.
 */
class TestAuditFailureChannel(
    private val available: Boolean = true
) : AuditFailureChannel {

    val records = mutableListOf<AuditRecord>()

    override fun write(record: AuditRecord): Boolean {
        if (!available) return false
        records.add(record)
        return true
    }

    fun clear() { records.clear() }

    inline fun <reified T : AuditRecord> recordsOfType(): List<T> =
        records.filterIsInstance<T>()

    fun pendingRecords(): List<AuditRecord.Pending> = recordsOfType()
    fun failedRecords(): List<AuditRecord.Failed> = recordsOfType()
    fun unauditedIrreversibleRecords(): List<AuditRecord.UnauditedIrreversible> = recordsOfType()
}
