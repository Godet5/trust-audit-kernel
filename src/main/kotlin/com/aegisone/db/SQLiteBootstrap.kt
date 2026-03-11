package com.aegisone.db

import java.sql.Connection
import java.sql.DriverManager

/**
 * Opens a SQLite connection and applies the durability pragmas required by
 * receiptDurabilitySpec-v1.md §4 before any schema or data access:
 *
 *   PRAGMA journal_mode = WAL
 *   PRAGMA synchronous  = FULL
 *
 * Both tables (receipts, audit_failure_records) are created here so any
 * connection opened through this entry point is immediately usable by
 * SQLiteReceiptChannel and SQLiteAuditFailureChannel.
 *
 * Durability contract: acknowledgment = successful conn.commit(). That
 * guarantee is only meaningful when these pragmas are in effect.
 */
object SQLiteBootstrap {

    fun openAndInitialize(path: String): SharedConnection {
        val conn = DriverManager.getConnection("jdbc:sqlite:$path")
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode=WAL")
            stmt.execute("PRAGMA synchronous=FULL")
            // busy_timeout: wait up to 5s for SQLite write lock instead of
            // returning SQLITE_BUSY immediately. Required for cross-process
            // serialization via BEGIN IMMEDIATE (G-1 closure).
            stmt.execute("PRAGMA busy_timeout=5000")
        }
        applySchema(conn)
        return SharedConnection(conn)
    }

    private fun applySchema(conn: Connection) {
        conn.createStatement().use { stmt ->

            // Full receipt store — RECEIPT_CHANNEL target.
            // One discriminated row per Receipt subtype; unused columns are NULL.
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS receipts (
                    id                INTEGER PRIMARY KEY AUTOINCREMENT,
                    receipt_type      TEXT    NOT NULL,
                    timestamp_ms      INTEGER NOT NULL,
                    receipt_id        TEXT,
                    status            TEXT,
                    capability_name   TEXT,
                    agent_id          TEXT,
                    session_id        TEXT,
                    sequence_number   INTEGER,
                    violation         TEXT,
                    step              TEXT,
                    anomaly_type      TEXT,
                    artifact_id       TEXT,
                    from_state        TEXT,
                    to_state          TEXT,
                    changed_by        TEXT,
                    detail            TEXT,
                    reason            TEXT,
                    inserted_at_ms    INTEGER NOT NULL
                )
                """.trimIndent()
            )

            // Audit failure store — AUDIT_FAILURE_CHANNEL target.
            // Receives PENDING records before execution; also Failed and
            // UnauditedIrreversible records. Zone D append-only in production.
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS audit_failure_records (
                    id                INTEGER PRIMARY KEY AUTOINCREMENT,
                    record_type       TEXT    NOT NULL,
                    receipt_id        TEXT    NOT NULL,
                    timestamp_ms      INTEGER NOT NULL,
                    capability_name   TEXT,
                    agent_id          TEXT,
                    session_id        TEXT,
                    sequence_number   INTEGER,
                    reason            TEXT,
                    detail            TEXT,
                    inserted_at_ms    INTEGER NOT NULL
                )
                """.trimIndent()
            )

            // Authority decision store — every grant attempt, issued or denied.
            // Separate from receipts; authority decisions and execution receipts are
            // distinct audit streams. One row per grant attempt.
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS authority_decisions (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    decision_type    TEXT    NOT NULL,
                    timestamp_ms     INTEGER NOT NULL,
                    capability_name  TEXT    NOT NULL,
                    target_role      TEXT    NOT NULL,
                    authority        TEXT    NOT NULL,
                    manifest_version INTEGER,
                    floor_version    INTEGER,
                    reason           TEXT,
                    inserted_at_ms   INTEGER NOT NULL
                )
                """.trimIndent()
            )

            // System lifecycle event store — structured operational events from
            // orchestration components: boot outcomes, recovery results, broker
            // state transitions. One row per lifecycle transition.
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS system_events (
                    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_type            TEXT    NOT NULL,
                    timestamp_ms          INTEGER NOT NULL,
                    manifest_version      INTEGER,
                    step                  TEXT,
                    reason                TEXT,
                    reconciliation_status TEXT,
                    expired_sessions      INTEGER,
                    ready_for_active      INTEGER,
                    from_state            TEXT,
                    to_state              TEXT,
                    inserted_at_ms        INTEGER NOT NULL
                )
                """.trimIndent()
            )

            // Active agent registry — AGENT_REGISTRY target (D-NC3 ceiling enforcement).
            // One row per active agent. PRIMARY KEY on agent_id enforces uniqueness
            // at the DB level as a safety net beneath the AgentRegistry synchronized block.
            // Rows are inserted on registration and deleted on deregistration.
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS active_agents (
                    agent_id         TEXT    NOT NULL PRIMARY KEY,
                    slot             TEXT    NOT NULL,
                    registered_at_ms INTEGER NOT NULL
                )
                """.trimIndent()
            )

            // Uniqueness guard on ActionReceipt.receipt_id.
            // Non-ActionReceipt subtypes set receipt_id = NULL; the WHERE clause
            // keeps them out of the index. SQLite treats NULL != NULL for UNIQUE
            // purposes, so multiple NULL rows are permitted by design.
            // This index is the DB-level complement to the coordinator's UUIDv4 PRNG:
            // if a duplicate UUID is written (external tampering or PRNG failure),
            // the write returns false and the coordinator follows the honest failure path.
            stmt.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS ux_receipts_action_receipt_id " +
                "ON receipts(receipt_id) WHERE receipt_id IS NOT NULL"
            )

            // Receipt summary store — RECEIPT_SUMMARY_CHANNEL target.
            // Summaries are derived from full ActionReceipts and are regenerable
            // (W3 crash window). Zone B storage in production (best-effort, not
            // correctness-critical). Kept in the same DB for Phase 0 simplicity.
            // 'regenerated' flag distinguishes startup-regenerated rows from
            // rows written inline during normal coordinator operation.
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS receipt_summaries (
                    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
                    receipt_id              TEXT    NOT NULL UNIQUE,
                    agent_id                TEXT    NOT NULL,
                    session_id              TEXT    NOT NULL,
                    status                  TEXT    NOT NULL,
                    capability_name         TEXT    NOT NULL,
                    receipt_timestamp_ms    INTEGER NOT NULL,
                    inserted_at_ms          INTEGER NOT NULL,
                    regenerated             INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }
    }
}
