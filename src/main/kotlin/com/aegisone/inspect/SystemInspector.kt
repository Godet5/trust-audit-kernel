package com.aegisone.inspect

import java.sql.Connection

/**
 * Read-only operator view of system state.
 *
 * All methods are non-mutating. No row is written, updated, or deleted.
 * The inspector does not validate state — it reports it faithfully, including
 * anomalies, violations, and ghost references.
 *
 * [receiptConn] is required: it provides access to receipts, authority_decisions,
 * system_events, audit_failure_records, and receipt_summaries.
 *
 * [reviewConn] is optional: when provided, enables sessions(), currentLocks(),
 * artifacts(), and the cross-table consistency fields in recoverySummary().
 * When null, those methods return empty lists and the summary shows -1 for fields
 * that require the review DB.
 *
 * Intended for:
 *   - operator inspection ("why was this grant denied?")
 *   - post-recovery audit ("what did the reconciler repair?")
 *   - debugging ("which sessions are holding which artifact locks?")
 *
 * Not intended for:
 *   - policy enforcement (CapabilityBroker owns that)
 *   - recovery actions (StartupRecovery owns that)
 *   - any write path (use the production channel classes)
 *
 * Source: implementationMap-trust-and-audit-v1.md §1–4 (read side)
 */
class SystemInspector(
    private val receiptConn: Connection,
    private val reviewConn: Connection? = null
) {
    // -------------------------------------------------------------------------
    // Authority decisions
    // -------------------------------------------------------------------------

    /**
     * Returns the most recent authority decisions (grant attempts), newest first.
     * Each row represents one grant issuance or denial, with full attribution.
     */
    fun recentDecisions(limit: Int = 50): List<DecisionRow> {
        val rows = mutableListOf<DecisionRow>()
        receiptConn.prepareStatement(
            "SELECT id, decision_type, timestamp_ms, capability_name, target_role, " +
            "authority, manifest_version, floor_version, reason " +
            "FROM authority_decisions ORDER BY id DESC LIMIT ?"
        ).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                while (rs.next()) rows.add(DecisionRow(
                    id              = rs.getLong("id"),
                    decisionType    = rs.getString("decision_type"),
                    timestampMs     = rs.getLong("timestamp_ms"),
                    capabilityName  = rs.getString("capability_name"),
                    targetRole      = rs.getString("target_role"),
                    authority       = rs.getString("authority"),
                    manifestVersion = rs.getObject("manifest_version")?.let { (it as Number).toInt() },
                    floorVersion    = rs.getObject("floor_version")?.let { (it as Number).toInt() },
                    reason          = rs.getString("reason")
                ))
            }
        }
        return rows
    }

    // -------------------------------------------------------------------------
    // Receipts
    // -------------------------------------------------------------------------

    /**
     * Returns the most recent receipt records, newest first.
     * Includes all receipt subtypes: ActionReceipt, PolicyViolation, ManifestFailure,
     * Anomaly, ProposalStatusReceipt.
     */
    fun recentReceipts(limit: Int = 50): List<ReceiptRow> {
        val rows = mutableListOf<ReceiptRow>()
        receiptConn.prepareStatement(
            "SELECT id, receipt_type, timestamp_ms, receipt_id, status, " +
            "capability_name, agent_id, session_id, sequence_number, " +
            "violation, detail, reason " +
            "FROM receipts ORDER BY id DESC LIMIT ?"
        ).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                while (rs.next()) rows.add(ReceiptRow(
                    id             = rs.getLong("id"),
                    receiptType    = rs.getString("receipt_type"),
                    timestampMs    = rs.getLong("timestamp_ms"),
                    receiptId      = rs.getString("receipt_id"),
                    status         = rs.getString("status"),
                    capabilityName = rs.getString("capability_name"),
                    agentId        = rs.getString("agent_id"),
                    sessionId      = rs.getString("session_id"),
                    sequenceNumber = rs.getObject("sequence_number")?.let { (it as Number).toInt() },
                    violation      = rs.getString("violation"),
                    detail         = rs.getString("detail"),
                    reason         = rs.getString("reason")
                ))
            }
        }
        return rows
    }

    // -------------------------------------------------------------------------
    // Audit failure records
    // -------------------------------------------------------------------------

    /**
     * Returns recent audit failure records, newest first.
     * Includes Pending, Failed, UnauditedIrreversible, and SummaryRegenerated rows.
     * Pending rows without a corresponding Failed/ActionReceipt row are orphans (W1/W2 window).
     */
    fun recentAuditFailures(limit: Int = 50): List<AuditFailureRow> {
        val rows = mutableListOf<AuditFailureRow>()
        receiptConn.prepareStatement(
            "SELECT id, record_type, receipt_id, timestamp_ms, " +
            "capability_name, agent_id, session_id, sequence_number, reason, detail " +
            "FROM audit_failure_records ORDER BY id DESC LIMIT ?"
        ).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                while (rs.next()) rows.add(AuditFailureRow(
                    id             = rs.getLong("id"),
                    recordType     = rs.getString("record_type"),
                    receiptId      = rs.getString("receipt_id"),
                    timestampMs    = rs.getLong("timestamp_ms"),
                    capabilityName = rs.getString("capability_name"),
                    agentId        = rs.getString("agent_id"),
                    sessionId      = rs.getString("session_id"),
                    sequenceNumber = rs.getObject("sequence_number")?.let { (it as Number).toInt() },
                    reason         = rs.getString("reason"),
                    detail         = rs.getString("detail")
                ))
            }
        }
        return rows
    }

    // -------------------------------------------------------------------------
    // System events
    // -------------------------------------------------------------------------

    /**
     * Returns recent system lifecycle events, newest first.
     * Includes BootVerified, BootFailed, and RecoveryCompleted events.
     */
    fun recentSystemEvents(limit: Int = 50): List<SystemEventRow> {
        val rows = mutableListOf<SystemEventRow>()
        receiptConn.prepareStatement(
            "SELECT id, event_type, timestamp_ms, manifest_version, step, reason, " +
            "reconciliation_status, expired_sessions, ready_for_active " +
            "FROM system_events ORDER BY id DESC LIMIT ?"
        ).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                while (rs.next()) rows.add(SystemEventRow(
                    id                   = rs.getLong("id"),
                    eventType            = rs.getString("event_type"),
                    timestampMs          = rs.getLong("timestamp_ms"),
                    manifestVersion      = rs.getObject("manifest_version")?.let { (it as Number).toInt() },
                    step                 = rs.getString("step"),
                    reason               = rs.getString("reason"),
                    reconciliationStatus = rs.getString("reconciliation_status"),
                    expiredSessions      = rs.getObject("expired_sessions")?.let { (it as Number).toInt() },
                    readyForActive       = rs.getObject("ready_for_active")?.let { (it as Number).toInt() == 1 }
                ))
            }
        }
        return rows
    }

    // -------------------------------------------------------------------------
    // Sessions (requires reviewConn)
    // -------------------------------------------------------------------------

    /**
     * Returns all sessions with their current state.
     * Shows ACTIVE, EXPIRED, and CLOSED sessions.
     * Returns empty list if reviewConn is not provided.
     */
    fun sessions(): List<SessionRow> {
        val conn = reviewConn ?: return emptyList()
        val rows = mutableListOf<SessionRow>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT session_id, cert_fingerprint, state, created_at_ms, last_heartbeat_ms, expiry_ms " +
                "FROM sessions ORDER BY created_at_ms DESC"
            ).use { rs ->
                while (rs.next()) rows.add(SessionRow(
                    sessionId        = rs.getString("session_id"),
                    certFingerprint  = rs.getString("cert_fingerprint"),
                    state            = rs.getString("state"),
                    isActive         = rs.getString("state") == "ACTIVE",
                    createdAtMs      = rs.getLong("created_at_ms"),
                    lastHeartbeatMs  = rs.getLong("last_heartbeat_ms"),
                    expiryMs         = rs.getLong("expiry_ms")
                ))
            }
        }
        return rows
    }

    // -------------------------------------------------------------------------
    // Locks (requires reviewConn)
    // -------------------------------------------------------------------------

    /**
     * Returns current lock state. Each row is one held lock (artifact → session).
     * Joins to sessions to show whether the lock holder is still active.
     * If sessionState is null, the lock is a ghost (session row absent).
     * Returns empty list if reviewConn is not provided.
     */
    fun currentLocks(): List<LockRow> {
        val conn = reviewConn ?: return emptyList()
        val rows = mutableListOf<LockRow>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT l.artifact_id, l.session_id, l.acquired_at_ms, s.state AS session_state " +
                "FROM artifact_locks l " +
                "LEFT JOIN sessions s ON l.session_id = s.session_id " +
                "ORDER BY l.acquired_at_ms DESC"
            ).use { rs ->
                while (rs.next()) rows.add(LockRow(
                    artifactId    = rs.getString("artifact_id"),
                    sessionId     = rs.getString("session_id"),
                    acquiredAtMs  = rs.getLong("acquired_at_ms"),
                    sessionState  = rs.getString("session_state")  // null = ghost lock
                ))
            }
        }
        return rows
    }

    // -------------------------------------------------------------------------
    // Artifacts (requires reviewConn)
    // -------------------------------------------------------------------------

    /**
     * Returns all artifact records with their current state and lock holder.
     * lockHolder is null when the artifact is not locked.
     * Returns empty list if reviewConn is not provided.
     */
    fun artifacts(): List<ArtifactRow> {
        val conn = reviewConn ?: return emptyList()
        val rows = mutableListOf<ArtifactRow>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT a.artifact_id, a.state, a.updated_at_ms, l.session_id AS lock_holder " +
                "FROM artifacts a " +
                "LEFT JOIN artifact_locks l ON a.artifact_id = l.artifact_id " +
                "ORDER BY a.updated_at_ms DESC"
            ).use { rs ->
                while (rs.next()) rows.add(ArtifactRow(
                    artifactId   = rs.getString("artifact_id"),
                    state        = rs.getString("state"),
                    updatedAtMs  = rs.getLong("updated_at_ms"),
                    lockHolder   = rs.getString("lock_holder")
                ))
            }
        }
        return rows
    }

    // -------------------------------------------------------------------------
    // Recovery summary
    // -------------------------------------------------------------------------

    /**
     * Returns a point-in-time recovery status summary.
     *
     * [lastBootResult] — the most recent boot event (BootVerified or BootFailed).
     * [unresolvedIrreversible] — count of UnauditedIrreversible records; non-zero
     *   means the coordinator MUST NOT go ACTIVE until human review completes.
     * [orphanedPending] — Pending records with no corresponding ActionReceipt (W1/W2 window).
     *   Non-zero after reconciliation means new unrepaired orphans arrived since last run.
     * [activeSessions] — count of currently ACTIVE sessions (-1 if reviewConn absent).
     * [violationA] — UNDER_REVIEW artifacts with no lock (-1 if reviewConn absent).
     * [violationB] — Locks with ghost session_id (-1 if reviewConn absent).
     * [violationC] — EXPIRED/CLOSED sessions with outstanding locks (-1 if reviewConn absent).
     */
    fun recoverySummary(): RecoverySummary {
        val lastBoot = lastBootEvent()
        val unresolvedIrreversible = countUnresolvedIrreversible()
        val orphanedPending = countOrphanedPending()

        val conn = reviewConn
        val activeSessions = if (conn != null) countActiveSessions(conn) else -1
        val (vA, vB, vC) = if (conn != null) crossTableViolations(conn) else Triple(-1, -1, -1)

        return RecoverySummary(
            lastBootResult         = lastBoot,
            unresolvedIrreversible = unresolvedIrreversible,
            orphanedPending        = orphanedPending,
            activeSessions         = activeSessions,
            violationA             = vA,
            violationB             = vB,
            violationC             = vC
        )
    }

    private fun lastBootEvent(): SystemEventRow? {
        receiptConn.prepareStatement(
            "SELECT id, event_type, timestamp_ms, manifest_version, step, reason, " +
            "reconciliation_status, expired_sessions, ready_for_active " +
            "FROM system_events WHERE event_type IN ('BootVerified', 'BootFailed') " +
            "ORDER BY id DESC LIMIT 1"
        ).use { ps ->
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return SystemEventRow(
                    id                   = rs.getLong("id"),
                    eventType            = rs.getString("event_type"),
                    timestampMs          = rs.getLong("timestamp_ms"),
                    manifestVersion      = rs.getObject("manifest_version")?.let { (it as Number).toInt() },
                    step                 = rs.getString("step"),
                    reason               = rs.getString("reason"),
                    reconciliationStatus = rs.getString("reconciliation_status"),
                    expiredSessions      = rs.getObject("expired_sessions")?.let { (it as Number).toInt() },
                    readyForActive       = rs.getObject("ready_for_active")?.let { (it as Number).toInt() == 1 }
                )
            }
        }
    }

    private fun countUnresolvedIrreversible(): Int =
        receiptConn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT COUNT(*) FROM audit_failure_records WHERE record_type = 'UnauditedIrreversible'"
            ).use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    private fun countOrphanedPending(): Int =
        receiptConn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT COUNT(*) FROM audit_failure_records afr " +
                "WHERE afr.record_type = 'Pending' " +
                "AND NOT EXISTS (SELECT 1 FROM receipts r WHERE r.receipt_id = afr.receipt_id " +
                "AND r.receipt_type = 'ActionReceipt')"
            ).use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    private fun countActiveSessions(conn: Connection): Int =
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM sessions WHERE state = 'ACTIVE'")
                .use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    private fun crossTableViolations(conn: Connection): Triple<Int, Int, Int> {
        val vA = conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT COUNT(*) FROM artifacts a LEFT JOIN artifact_locks l " +
                "ON a.artifact_id = l.artifact_id " +
                "WHERE a.state = 'UNDER_REVIEW' AND l.artifact_id IS NULL"
            ).use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        val vB = conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT COUNT(*) FROM artifact_locks l LEFT JOIN sessions s " +
                "ON l.session_id = s.session_id WHERE s.session_id IS NULL"
            ).use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        val vC = conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT COUNT(DISTINCT l.session_id) FROM artifact_locks l " +
                "JOIN sessions s ON l.session_id = s.session_id " +
                "WHERE s.state IN ('EXPIRED', 'CLOSED')"
            ).use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        return Triple(vA, vB, vC)
    }
}

// -------------------------------------------------------------------------
// Row types — flat, no nesting, all fields nullable except structural ids
// -------------------------------------------------------------------------

data class DecisionRow(
    val id: Long,
    val decisionType: String,
    val timestampMs: Long,
    val capabilityName: String,
    val targetRole: String,
    val authority: String,
    val manifestVersion: Int?,
    val floorVersion: Int?,
    val reason: String?
)

data class ReceiptRow(
    val id: Long,
    val receiptType: String,
    val timestampMs: Long,
    val receiptId: String?,
    val status: String?,
    val capabilityName: String?,
    val agentId: String?,
    val sessionId: String?,
    val sequenceNumber: Int?,
    val violation: String?,
    val detail: String?,
    val reason: String?
)

data class AuditFailureRow(
    val id: Long,
    val recordType: String,
    val receiptId: String,
    val timestampMs: Long,
    val capabilityName: String?,
    val agentId: String?,
    val sessionId: String?,
    val sequenceNumber: Int?,
    val reason: String?,
    val detail: String?
)

data class SystemEventRow(
    val id: Long,
    val eventType: String,
    val timestampMs: Long,
    val manifestVersion: Int?,
    val step: String?,
    val reason: String?,
    val reconciliationStatus: String?,
    val expiredSessions: Int?,
    val readyForActive: Boolean?
)

data class SessionRow(
    val sessionId: String,
    val certFingerprint: String,
    val state: String,
    val isActive: Boolean,
    val createdAtMs: Long,
    val lastHeartbeatMs: Long,
    val expiryMs: Long
)

data class LockRow(
    val artifactId: String,
    val sessionId: String,
    val acquiredAtMs: Long,
    val sessionState: String?  // null = ghost lock (no sessions row)
)

data class ArtifactRow(
    val artifactId: String,
    val state: String,
    val updatedAtMs: Long,
    val lockHolder: String?  // null = not locked
)

data class RecoverySummary(
    val lastBootResult: SystemEventRow?,
    val unresolvedIrreversible: Int,
    val orphanedPending: Int,
    val activeSessions: Int,         // -1 if reviewConn absent
    val violationA: Int,             // UNDER_REVIEW with no lock; -1 if reviewConn absent
    val violationB: Int,             // ghost locks; -1 if reviewConn absent
    val violationC: Int              // expired session locks; -1 if reviewConn absent
) {
    /** True when the system is in a clean, fully consistent state. */
    val isClean: Boolean
        get() = unresolvedIrreversible == 0 && orphanedPending == 0 &&
                violationA <= 0 && violationB <= 0 && violationC <= 0

    /** True when human review is required before the coordinator may go ACTIVE. */
    val requiresHumanReview: Boolean
        get() = unresolvedIrreversible > 0
}
