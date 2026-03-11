package com.aegisone.concurrency

import com.aegisone.db.SQLiteAgentRegistry
import com.aegisone.db.SQLiteAuditFailureChannel
import com.aegisone.db.SQLiteAuthorityDecisionChannel
import com.aegisone.db.SQLiteBootstrap
import com.aegisone.db.SQLiteReceiptChannel
import com.aegisone.db.SharedConnection
import com.aegisone.execution.ActionExecutor
import com.aegisone.execution.ActionRequest
import com.aegisone.execution.AgentSlot
import com.aegisone.execution.ConflictAlert
import com.aegisone.execution.ConflictChannel
import com.aegisone.execution.CoordinatorResult
import com.aegisone.execution.ExecutionCoordinator
import com.aegisone.execution.RegistrationResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Stale capability revocation tests — closes G-5.
 *
 * G-5 (from README.md gap section):
 *   "A deregistered agent that retains an IssuedCapability object can still
 *    submit ActionRequests to an ExecutionCoordinator."
 *
 * This file proves that coupling is closed: the coordinator consults live
 * registry state on every execute() call. Possession of a capability name
 * and agent_id is insufficient — the agent must be currently registered.
 *
 * SC-1: Register → [use capability] → deregister → execute → AGENT_NOT_REGISTERED.
 *         The action is blocked. The executor is never called.
 *         A PolicyViolation receipt is written.
 *
 * SC-2: Never-registered agent → execute → AGENT_NOT_REGISTERED.
 *         An agent with no registration history cannot execute.
 *
 * SC-3: Registered agent executes successfully. Deregistered. Same agent_id
 *         then blocked on next attempt. Proves the check is live, not cached.
 *
 * SC-4: Denial is durable — PolicyViolation record is observable in receipts.
 *         The rejection reason identifies the specific violation.
 *
 * SC-5: Coordinator with no registry wired → nominal behavior unchanged.
 *         agentRegistry=null preserves backward compatibility for all 182
 *         existing tests that do not wire a registry.
 */
class StaleCapabilityRevocationTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var shared: SharedConnection
    private lateinit var registry: SQLiteAgentRegistry
    private lateinit var decisionChannel: SQLiteAuthorityDecisionChannel
    private lateinit var receiptChannel: SQLiteReceiptChannel
    private lateinit var auditChannel: SQLiteAuditFailureChannel

    private var executorCallCount = 0
    private val noOpConflict = object : ConflictChannel { override fun alert(alert: ConflictAlert) = true }

    @BeforeEach
    fun setup() {
        shared          = SQLiteBootstrap.openAndInitialize(File(tempDir, "receipts.db").absolutePath)
        decisionChannel = SQLiteAuthorityDecisionChannel(shared)
        registry        = SQLiteAgentRegistry(shared, decisionChannel)
        receiptChannel  = SQLiteReceiptChannel(shared)
        auditChannel    = SQLiteAuditFailureChannel(shared)
        executorCallCount = 0
    }

    @AfterEach
    fun teardown() {
        runCatching { shared.close() }
    }

    private fun coordinator() = ExecutionCoordinator(
        auditFailureChannel = auditChannel,
        receiptChannel      = receiptChannel,
        executor            = object : ActionExecutor {
            override fun execute(request: ActionRequest): Boolean {
                executorCallCount++
                return true
            }
        },
        conflictChannel = noOpConflict,
        agentRegistry = registry
    )

    // -------------------------------------------------------------------------
    // SC-1: Deregistered agent cannot use a previously issued capability
    // -------------------------------------------------------------------------

    @Test
    fun `SC-1 deregistered agent cannot execute — AGENT_NOT_REGISTERED executor never called`() {
        val registered = registry.register("agent-sc1", AgentSlot.PRIMARY)
        assertEquals(RegistrationResult.Registered, registered,
            "Agent must register successfully")

        // Simulate using the capability — agent is registered at this point.
        // Now deregister: the agent is gone from the registry.
        registry.deregister("agent-sc1")

        // Attempt to execute with the now-stale identity.
        val result = coordinator().execute(ActionRequest(
            capabilityName = "WRITE_NOTE",
            agentId        = "agent-sc1",
            sessionId      = "sess-sc1"
        ))

        assertEquals(CoordinatorResult.AGENT_NOT_REGISTERED, result,
            "Deregistered agent must be denied with AGENT_NOT_REGISTERED")
        assertEquals(0, executorCallCount,
            "Executor must never be called for a deregistered agent")
    }

    // -------------------------------------------------------------------------
    // SC-2: Never-registered agent cannot execute
    // -------------------------------------------------------------------------

    @Test
    fun `SC-2 never-registered agent cannot execute — AGENT_NOT_REGISTERED`() {
        // No register() call. Coordinator registry is empty.
        val result = coordinator().execute(ActionRequest(
            capabilityName = "WRITE_NOTE",
            agentId        = "ghost-agent-sc2",
            sessionId      = "sess-sc2"
        ))

        assertEquals(CoordinatorResult.AGENT_NOT_REGISTERED, result,
            "Agent with no registration history must be denied")
        assertEquals(0, executorCallCount,
            "Executor must never be called for a never-registered agent")
    }

    // -------------------------------------------------------------------------
    // SC-3: Live check — registered succeeds, deregistered then blocked
    // -------------------------------------------------------------------------

    @Test
    fun `SC-3 live registry check — same agent succeeds then is blocked after deregistration`() {
        val registered = registry.register("agent-sc3", AgentSlot.HELPER)
        assertEquals(RegistrationResult.Registered, registered)

        val coord = coordinator()

        // First call: agent is registered — must succeed.
        val firstResult = coord.execute(ActionRequest("WRITE_NOTE", "agent-sc3", "sess-sc3"))
        assertEquals(CoordinatorResult.SUCCESS, firstResult,
            "Registered agent must execute successfully")
        assertEquals(1, executorCallCount, "Executor must be called for registered agent")

        // Deregister the agent.
        registry.deregister("agent-sc3")

        // Second call: same coordinator, same agent_id, now deregistered — must be blocked.
        val secondResult = coord.execute(ActionRequest("WRITE_NOTE", "agent-sc3", "sess-sc3"))
        assertEquals(CoordinatorResult.AGENT_NOT_REGISTERED, secondResult,
            "Same agent_id must be blocked after deregistration")
        assertEquals(1, executorCallCount,
            "Executor must not have been called a second time")
    }

    // -------------------------------------------------------------------------
    // SC-4: Denial is durable — PolicyViolation in receipts
    // -------------------------------------------------------------------------

    @Test
    fun `SC-4 AGENT_NOT_REGISTERED denial is observable as PolicyViolation in receipt channel`() {
        // Do not register any agent — guaranteed denial.
        coordinator().execute(ActionRequest("WRITE_NOTE", "nobody-sc4", "sess-sc4"))

        // A PolicyViolation receipt must be durably written identifying the denial.
        val violationRow = shared.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT violation, detail FROM receipts " +
                "WHERE receipt_type = 'PolicyViolation' " +
                "AND violation = 'AGENT_NOT_REGISTERED'"
            ).use { rs ->
                if (rs.next()) Pair(rs.getString("violation"), rs.getString("detail"))
                else null
            }
        }

        assertNotNull(violationRow,
            "PolicyViolation with violation='AGENT_NOT_REGISTERED' must be written to receipts")
        assertTrue(violationRow!!.second.contains("nobody-sc4"),
            "PolicyViolation detail must identify the requesting agent_id")
    }

    // -------------------------------------------------------------------------
    // SC-5: Backward compatibility — no registry wired → nominal behavior
    // -------------------------------------------------------------------------

    @Test
    fun `SC-5 coordinator without agentRegistry wired — nominal execution proceeds unchanged`() {
        // Coordinator with no registry: the registry check is skipped entirely.
        val unwiredCoord = ExecutionCoordinator(
            auditFailureChannel = auditChannel,
            receiptChannel      = receiptChannel,
            executor            = object : ActionExecutor {
                override fun execute(request: ActionRequest): Boolean {
                    executorCallCount++
                    return true
                }
            },
            conflictChannel     = noOpConflict
            // agentRegistry omitted — defaults to null
        )

        val result = unwiredCoord.execute(ActionRequest(
            capabilityName = "WRITE_NOTE",
            agentId        = "any-agent-sc5",
            sessionId      = "sess-sc5"
        ))

        assertEquals(CoordinatorResult.SUCCESS, result,
            "Coordinator with no registry wired must execute normally (backward compat)")
        assertEquals(1, executorCallCount,
            "Executor must be called when no registry check is in effect")
    }
}
