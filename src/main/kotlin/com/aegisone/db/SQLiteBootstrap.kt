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

    fun openAndInitialize(path: String): Connection {
        val conn = DriverManager.getConnection("jdbc:sqlite:$path")
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode=WAL")
            stmt.execute("PRAGMA synchronous=FULL")
        }
        applySchema(conn)
        return conn
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
