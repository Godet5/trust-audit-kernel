package com.aegisone.boot

import com.aegisone.receipt.Receipt
import com.aegisone.receipt.ReceiptChannel
import com.aegisone.review.ArtifactLockManager
import com.aegisone.review.ArtifactState
import com.aegisone.review.ArtifactStore
import java.sql.Connection

/**
 * Repairs cross-table consistency violations in the review subsystem at startup.
 *
 * Three violation classes, each with an independent repair action and a distinct
 * ProposalStatusReceipt changedBy tag for attribution:
 *
 *   Violation A — UNDER_REVIEW artifact with no lock (SYSTEM_RECOVERY_NO_LOCK):
 *     artifact.state = UNDER_REVIEW but artifact_id absent from artifact_locks.
 *     Crash window: after setState(UNDER_REVIEW) but before lock was written, or
 *     lock was released without reverting state.
 *     Repair: setState(SUBMITTED) + ProposalStatusReceipt.
 *
 *   Violation B — Ghost lock (SYSTEM_RECOVERY_GHOST_LOCK):
 *     artifact_locks.session_id has no row in the sessions table.
 *     Crash window: session row deleted while lock persisted, or provisioning error.
 *     Repair: release lock; if artifact is UNDER_REVIEW, setState(SUBMITTED) + receipt.
 *
 *   Violation C — EXPIRED/CLOSED session with outstanding lock (SYSTEM_RECOVERY_EXPIRED_SESSION):
 *     sessions.state is EXPIRED or CLOSED but artifact_locks still references the session_id.
 *     Crash window: session transitioned to EXPIRED before onSessionExpired() completed.
 *     Repair: for each locked artifact, release lock; if UNDER_REVIEW, setState(SUBMITTED) + receipt.
 *
 * Violation A and B may overlap (an UNDER_REVIEW artifact with a ghost lock satisfies both
 * predicates). The queries are structured to avoid double repair:
 *   - repairUnlockedReviews() selects artifacts with NO lock entry at all
 *   - repairGhostLocks() selects artifacts whose lock points to a missing session row
 *   These two predicates are mutually exclusive for any given artifact_id.
 *   Violation C selects session_ids that DO exist in sessions but are EXPIRED/CLOSED,
 *   which is also mutually exclusive with B (ghost = absent from sessions).
 *
 * [reviewConn] must be the connection for the review DB (artifacts, artifact_locks, sessions).
 * [receiptChannel] writes to the receipt DB (may be a different connection).
 *
 * Source: implementationMap-trust-and-audit-v1.md §4.4; reviewSystemSpec-v1.md §7
 */
class ReviewCrossTableReconciliation(
    private val reviewConn: Connection,
    private val artifactStore: ArtifactStore,
    private val lockManager: ArtifactLockManager,
    private val receiptChannel: ReceiptChannel
) {
    data class Result(
        val repairedUnlockedReviews: Int,
        val repairedGhostLocks: Int,
        val repairedExpiredSessionLocks: Int
    ) {
        val totalRepaired: Int
            get() = repairedUnlockedReviews + repairedGhostLocks + repairedExpiredSessionLocks
        val isClean: Boolean
            get() = totalRepaired == 0
    }

    fun run(): Result {
        val unlockedReviews      = repairUnlockedReviews()
        val ghostLocks           = repairGhostLocks()
        val expiredSessionLocks  = repairExpiredSessionLocks()
        return Result(unlockedReviews, ghostLocks, expiredSessionLocks)
    }

    /** Violation A: UNDER_REVIEW artifacts with no entry in artifact_locks. */
    private fun repairUnlockedReviews(): Int {
        val stranded = reviewConn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT a.artifact_id FROM artifacts a " +
                "LEFT JOIN artifact_locks l ON a.artifact_id = l.artifact_id " +
                "WHERE a.state = 'UNDER_REVIEW' AND l.artifact_id IS NULL"
            ).use { rs ->
                val ids = mutableListOf<String>()
                while (rs.next()) ids.add(rs.getString("artifact_id"))
                ids
            }
        }
        stranded.forEach { artifactId ->
            artifactStore.setState(artifactId, ArtifactState.SUBMITTED)
            receiptChannel.write(Receipt.ProposalStatusReceipt(
                artifactId = artifactId,
                fromState  = "UNDER_REVIEW",
                toState    = "SUBMITTED",
                changedBy  = "SYSTEM_RECOVERY_NO_LOCK"
            ))
        }
        return stranded.size
    }

    /** Violation B: Lock entries whose session_id has no sessions table row. */
    private fun repairGhostLocks(): Int {
        val ghosted = reviewConn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT l.artifact_id FROM artifact_locks l " +
                "LEFT JOIN sessions s ON l.session_id = s.session_id " +
                "WHERE s.session_id IS NULL"
            ).use { rs ->
                val ids = mutableListOf<String>()
                while (rs.next()) ids.add(rs.getString("artifact_id"))
                ids
            }
        }
        ghosted.forEach { artifactId ->
            lockManager.release(artifactId)
            if (artifactStore.getState(artifactId) == ArtifactState.UNDER_REVIEW) {
                artifactStore.setState(artifactId, ArtifactState.SUBMITTED)
                receiptChannel.write(Receipt.ProposalStatusReceipt(
                    artifactId = artifactId,
                    fromState  = "UNDER_REVIEW",
                    toState    = "SUBMITTED",
                    changedBy  = "SYSTEM_RECOVERY_GHOST_LOCK"
                ))
            }
        }
        return ghosted.size
    }

    /** Violation C: EXPIRED/CLOSED sessions with outstanding lock entries. */
    private fun repairExpiredSessionLocks(): Int {
        val expiredWithLocks = reviewConn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT DISTINCT l.session_id FROM artifact_locks l " +
                "JOIN sessions s ON l.session_id = s.session_id " +
                "WHERE s.state IN ('EXPIRED', 'CLOSED')"
            ).use { rs ->
                val ids = mutableListOf<String>()
                while (rs.next()) ids.add(rs.getString("session_id"))
                ids
            }
        }
        var repaired = 0
        expiredWithLocks.forEach { sessionId ->
            lockManager.lockedBy(sessionId).forEach { artifactId ->
                lockManager.release(artifactId)
                if (artifactStore.getState(artifactId) == ArtifactState.UNDER_REVIEW) {
                    artifactStore.setState(artifactId, ArtifactState.SUBMITTED)
                    receiptChannel.write(Receipt.ProposalStatusReceipt(
                        artifactId = artifactId,
                        fromState  = "UNDER_REVIEW",
                        toState    = "SUBMITTED",
                        changedBy  = "SYSTEM_RECOVERY_EXPIRED_SESSION"
                    ))
                }
                repaired++
            }
        }
        return repaired
    }
}
