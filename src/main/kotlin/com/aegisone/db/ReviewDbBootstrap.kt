package com.aegisone.db

import java.sql.Connection
import java.sql.DriverManager

/**
 * Opens a SQLite connection for the review system backend and applies the
 * same WAL + synchronous=FULL pragmas used by the receipt channel backing.
 *
 * Three tables:
 *   artifacts       — artifact_id → current ArtifactState
 *   artifact_fields — (artifact_id, field_name) → field_value (mutable review fields)
 *   artifact_locks  — artifact_id → session_id (exclusive locks)
 *
 * In production these would live in a separate Zone D storage partition from
 * the receipt channels. For Phase 0, a separate DB file is sufficient.
 *
 * Both SQLiteArtifactStore and SQLiteArtifactLockManager take a connection
 * opened here. They must share the same connection to ensure that lock state
 * and artifact state are consistent within a single transaction boundary.
 *
 * Source: reviewSystemSpec-v1.md §3, implementationMap-trust-and-audit-v1.md §4.4
 */
object ReviewDbBootstrap {

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

            // Artifact lifecycle state. One row per artifact; state is mutable.
            // ArtifactState values stored as their enum name strings.
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS artifacts (
                    artifact_id    TEXT    PRIMARY KEY,
                    state          TEXT    NOT NULL,
                    updated_at_ms  INTEGER NOT NULL
                )
                """.trimIndent()
            )

            // Mutable review fields. One row per (artifact_id, field_name) pair.
            // Writes are REPLACE operations — no field history at Phase 0.
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS artifact_fields (
                    artifact_id    TEXT    NOT NULL,
                    field_name     TEXT    NOT NULL,
                    field_value    TEXT    NOT NULL,
                    updated_at_ms  INTEGER NOT NULL,
                    PRIMARY KEY (artifact_id, field_name)
                )
                """.trimIndent()
            )

            // Exclusive session locks. One row per locked artifact.
            // Release = DELETE. Re-lock = REPLACE (updates session_id).
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS artifact_locks (
                    artifact_id     TEXT    PRIMARY KEY,
                    session_id      TEXT    NOT NULL,
                    acquired_at_ms  INTEGER NOT NULL
                )
                """.trimIndent()
            )

            // Sessions. Managed by SQLiteSessionRegistry.
            // state: ACTIVE | EXPIRED | CLOSED
            // active field on SessionEntry is derived: state == 'ACTIVE'
            // expireSessions() marks ACTIVE rows EXPIRED when expiry_ms <= now.
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS sessions (
                    session_id          TEXT    PRIMARY KEY,
                    cert_fingerprint    TEXT    NOT NULL,
                    state               TEXT    NOT NULL,
                    created_at_ms       INTEGER NOT NULL,
                    last_heartbeat_ms   INTEGER NOT NULL,
                    expiry_ms           INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }
}
