package com.aegisone.db

import java.sql.Connection

/**
 * Wraps a JDBC Connection with a shared monitor for cross-object synchronization.
 *
 * Problem: Multiple db classes (SQLiteReceiptChannel, SQLiteAuditFailureChannel,
 * SQLiteAgentRegistry, etc.) may share the same Connection. Each class uses
 * @Synchronized, which locks on `this` — a different monitor per object. Two objects
 * sharing one Connection have no mutual exclusion; concurrent writes can corrupt
 * the connection's transaction state.
 *
 * Fix: All classes that share a Connection must share the same SharedConnection
 * instance and synchronize on it. The SharedConnection IS the lock monitor.
 * Classes use `synchronized(shared) { ... }` instead of @Synchronized.
 *
 * Bootstrap usage:
 *   val shared = SQLiteBootstrap.openAndInitialize("receipts.db")
 *   val receiptChannel = SQLiteReceiptChannel(shared)
 *   val auditChannel   = SQLiteAuditFailureChannel(shared)
 *   // Both channels now synchronize on the same SharedConnection instance.
 *
 * Source: adversarialSwarmSynthesis-v1.md P1, ACN-1 remediation
 */
class SharedConnection(val conn: Connection) : AutoCloseable {
    override fun close() = conn.close()
    val isClosed: Boolean get() = conn.isClosed
}
