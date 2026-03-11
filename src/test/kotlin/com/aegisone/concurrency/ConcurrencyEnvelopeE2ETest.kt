package com.aegisone.concurrency

import com.aegisone.db.SQLiteAgentRegistry
import com.aegisone.db.SQLiteAuthorityDecisionChannel
import com.aegisone.db.SQLiteBootstrap
import com.aegisone.execution.AgentSlot
import com.aegisone.execution.DenialReason
import com.aegisone.execution.RegistrationResult
import com.aegisone.db.SharedConnection
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Adversarial concurrency envelope tests for AgentRegistry.
 *
 * Proves the Phase 0 ceiling (D-NC3) holds under actual thread contention —
 * not just nominal sequential calls. The ceiling is global: max 2 agents,
 * max 1 per slot. The tests use real threads racing a real SQLite registry.
 *
 * CE-1: Two concurrent HELPER spawns for one open slot.
 *         Exactly one wins, one is denied. Final count = 1. (Repeated 10×)
 *
 * CE-2: PRIMARY + HELPER already active; third spawn races in.
 *         Denied every time. Count never exceeds 2. (Repeated 10×)
 *
 * CE-3: Concurrent termination + spawn against a full ceiling.
 *         After both complete, count is 1 or 2 — never 3.
 *         The spawn either wins the race (count = 2) or sees the old ceiling (count = 1).
 *         Neither outcome is wrong; count = 3 is the only forbidden outcome.
 *
 * CE-4: Duplicate agent_id replay under concurrent pressure.
 *         Two threads race to register the same agent_id.
 *         Exactly one wins; second is denied DUPLICATE_AGENT_ID. Count = 1.
 *
 * CE-5: Recursive spawn — HELPER tries to spawn another agent.
 *         Denied RECURSIVE_SPAWN_DENIED regardless of available ceiling slots.
 *
 * CE-6: Denial observability — every denial produces a SpawnDenied record
 *         in the authority_decisions table via the decision channel.
 *
 * CE-7: Sequential ceiling: 1 PRIMARY + 1 HELPER fills the ceiling;
 *         any further attempt (PRIMARY or HELPER) is denied.
 */
class ConcurrencyEnvelopeE2ETest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var shared: SharedConnection
    private lateinit var registry: SQLiteAgentRegistry
    private lateinit var decisionChannel: SQLiteAuthorityDecisionChannel

    @BeforeEach
    fun setup() {
        shared          = SQLiteBootstrap.openAndInitialize(File(tempDir, "receipts.db").absolutePath)
        decisionChannel = SQLiteAuthorityDecisionChannel(shared)
        registry        = SQLiteAgentRegistry(shared, decisionChannel)
    }

    @AfterEach
    fun teardown() {
        runCatching { shared.close() }
    }

    // -------------------------------------------------------------------------
    // CE-1: Two concurrent HELPER spawns — exactly one wins
    // -------------------------------------------------------------------------

    @RepeatedTest(10)
    fun `CE-1 two concurrent HELPER spawns — exactly one wins one denied count stays at 1`() {
        val startGate = CountDownLatch(1)
        val results   = ConcurrentLinkedQueue<RegistrationResult>()
        val pool      = Executors.newFixedThreadPool(2)

        repeat(2) { i ->
            pool.submit {
                startGate.await()
                results.add(registry.register("helper-ce1-$i", AgentSlot.HELPER))
            }
        }

        startGate.countDown()
        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.SECONDS)

        val registered = results.count { it is RegistrationResult.Registered }
        val denied     = results.count { it is RegistrationResult.Denied }

        assertEquals(1, registered, "Exactly one HELPER spawn must succeed")
        assertEquals(1, denied,     "Exactly one HELPER spawn must be denied")
        assertEquals(1, registry.activeCount(), "Active count must be exactly 1")
    }

    // -------------------------------------------------------------------------
    // CE-2: Full ceiling — third spawn never slips through
    // -------------------------------------------------------------------------

    @RepeatedTest(10)
    fun `CE-2 ceiling full — concurrent third spawn denied every time count never exceeds 2`() {
        // Fill the ceiling sequentially (this is deterministic)
        assertEquals(RegistrationResult.Registered, registry.register("primary-ce2", AgentSlot.PRIMARY))
        assertEquals(RegistrationResult.Registered, registry.register("helper-ce2", AgentSlot.HELPER))
        assertEquals(2, registry.activeCount())

        // Now race 4 more spawn attempts against the full ceiling
        val startGate = CountDownLatch(1)
        val results   = ConcurrentLinkedQueue<RegistrationResult>()
        val pool      = Executors.newFixedThreadPool(4)

        repeat(4) { i ->
            pool.submit {
                startGate.await()
                results.add(registry.register("extra-ce2-$i", AgentSlot.HELPER))
            }
        }

        startGate.countDown()
        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.SECONDS)

        // Every racing spawn must be denied
        assertTrue(results.all { it is RegistrationResult.Denied },
            "All spawns against full ceiling must be denied")
        assertEquals(2, registry.activeCount(),
            "Active count must remain exactly 2 — ceiling must not be breached")
    }

    // -------------------------------------------------------------------------
    // CE-3: Concurrent deregister + spawn — never overshoots ceiling
    // -------------------------------------------------------------------------

    @RepeatedTest(10)
    fun `CE-3 concurrent deregister and spawn — count is 1 or 2 never 3`() {
        registry.register("primary-ce3", AgentSlot.PRIMARY)
        registry.register("helper-ce3-a", AgentSlot.HELPER)
        // Ceiling is full. Now race a deregister against a new spawn.

        val startGate    = CountDownLatch(1)
        val spawnResult  = ConcurrentLinkedQueue<RegistrationResult>()
        val pool         = Executors.newFixedThreadPool(2)

        pool.submit {
            startGate.await()
            registry.deregister("helper-ce3-a")
        }
        pool.submit {
            startGate.await()
            spawnResult.add(registry.register("helper-ce3-b", AgentSlot.HELPER))
        }

        startGate.countDown()
        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.SECONDS)

        val count = registry.activeCount()
        assertTrue(count <= 2, "Active count must never exceed 2 (was $count)")
        assertTrue(count >= 1, "At least primary-ce3 must still be active")

        // Outcome is non-deterministic but both are valid:
        // If deregister won: helper-ce3-b registered successfully → count = 2
        // If spawn saw old ceiling: spawn denied → count = 1 (after deregister)
        // If spawn checked before deregister committed: spawn denied → count still 1
        val spawnRes = spawnResult.single()
        if (spawnRes is RegistrationResult.Registered) {
            assertEquals(2, count, "If spawn registered, count must be 2")
        } else {
            // Spawn denied: primary still active, helper-ce3-a may have been deregistered
            assertTrue(count in 1..2, "Denied spawn must leave count in [1, 2]")
        }
    }

    // -------------------------------------------------------------------------
    // CE-4: Duplicate agent_id replay under concurrent pressure
    // -------------------------------------------------------------------------

    @RepeatedTest(10)
    fun `CE-4 duplicate agent_id race — exactly one wins second is denied DUPLICATE_AGENT_ID`() {
        val startGate = CountDownLatch(1)
        val results   = ConcurrentLinkedQueue<RegistrationResult>()
        val pool      = Executors.newFixedThreadPool(2)

        // Both threads try to register the exact same agent_id
        repeat(2) {
            pool.submit {
                startGate.await()
                results.add(registry.register("agent-ce4-shared", AgentSlot.HELPER))
            }
        }

        startGate.countDown()
        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.SECONDS)

        val registered = results.count { it is RegistrationResult.Registered }
        val denied     = results.count { it is RegistrationResult.Denied }

        assertEquals(1, registered, "Only one registration of the same agent_id must succeed")
        assertEquals(1, denied,     "The second attempt must be denied")
        assertEquals(1, registry.activeCount(), "DB must contain exactly one row for this agent_id")

        // The denial reason for a duplicate must be DUPLICATE_AGENT_ID
        val deniedResult = results.filterIsInstance<RegistrationResult.Denied>().single()
        assertEquals(DenialReason.DUPLICATE_AGENT_ID, deniedResult.reason)
    }

    // -------------------------------------------------------------------------
    // CE-5: Recursive spawn — HELPER cannot spawn
    // -------------------------------------------------------------------------

    @Test
    fun `CE-5 recursive spawn — HELPER as requesting agent is denied regardless of ceiling availability`() {
        // Register a HELPER agent
        assertEquals(RegistrationResult.Registered,
            registry.register("helper-ce5", AgentSlot.HELPER))
        assertEquals(1, registry.activeCount())

        // The PRIMARY slot is open — there IS room for a new agent.
        // But the requesting agent is a HELPER → recursive spawn → must be denied.
        val result = registry.register(
            agentId          = "primary-ce5-attempted",
            slot             = AgentSlot.PRIMARY,
            requestingAgentId = "helper-ce5"
        )

        assertTrue(result is RegistrationResult.Denied,
            "HELPER attempting to spawn must be denied")
        assertEquals(DenialReason.RECURSIVE_SPAWN_DENIED,
            (result as RegistrationResult.Denied).reason)
        assertEquals(1, registry.activeCount(),
            "No new agent must be registered after recursive spawn denial")
    }

    @Test
    fun `CE-5b recursive spawn — HELPER cannot spawn even when ceiling would allow it`() {
        // Empty registry. HELPER could fit. But requester is a HELPER.
        // Inject a HELPER directly, then have it try to spawn another.
        assertEquals(RegistrationResult.Registered,
            registry.register("helper-ce5b-parent", AgentSlot.HELPER))

        // Deregister everything except the helper — so there's clearly room
        // The PRIMARY slot is entirely free. Ceiling is 1/2.
        // Requester = helper-ce5b-parent (a HELPER). Must be denied.
        val result = registry.register(
            agentId          = "helper-ce5b-child",
            slot             = AgentSlot.HELPER,
            requestingAgentId = "helper-ce5b-parent"
        )

        assertTrue(result is RegistrationResult.Denied)
        assertEquals(DenialReason.RECURSIVE_SPAWN_DENIED,
            (result as RegistrationResult.Denied).reason,
            "Recursive spawn must be denied even when slots are available")
    }

    // -------------------------------------------------------------------------
    // CE-6: Denial observability
    // -------------------------------------------------------------------------

    @Test
    fun `CE-6 denials are observable — every denial produces SpawnDenied record in authority_decisions`() {
        // Fill ceiling
        registry.register("primary-ce6", AgentSlot.PRIMARY)
        registry.register("helper-ce6", AgentSlot.HELPER)

        // Attempt 3 more spawns — all must be denied
        registry.register("extra-ce6-a", AgentSlot.HELPER)
        registry.register("extra-ce6-b", AgentSlot.PRIMARY)
        registry.register("extra-ce6-c", AgentSlot.HELPER)

        // Recursive spawn — also denied
        registry.register("recursive-ce6", AgentSlot.HELPER, requestingAgentId = "helper-ce6")

        // All four denials must appear in authority_decisions
        val deniedCount = shared.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT COUNT(*) FROM authority_decisions WHERE decision_type = 'SpawnDenied'"
            ).use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        assertEquals(4, deniedCount,
            "All 4 denied spawn attempts must produce SpawnDenied records")

        // The recursive denial must carry its specific reason
        val recursiveDenial = shared.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT reason FROM authority_decisions " +
                "WHERE decision_type = 'SpawnDenied' AND reason = 'RECURSIVE_SPAWN_DENIED'"
            ).use { rs -> if (rs.next()) rs.getString("reason") else null }
        }
        assertNotNull(recursiveDenial, "Recursive spawn denial must be observable in authority_decisions")
        assertEquals("RECURSIVE_SPAWN_DENIED", recursiveDenial)

        // The successful spawns must also appear as SpawnIssued
        val issuedCount = shared.conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT COUNT(*) FROM authority_decisions WHERE decision_type = 'SpawnIssued'"
            ).use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        assertEquals(2, issuedCount, "The 2 successful registrations must appear as SpawnIssued")
    }

    // -------------------------------------------------------------------------
    // CE-7: Sequential ceiling — 1+1 fills it; anything further denied
    // -------------------------------------------------------------------------

    @Test
    fun `CE-7 sequential ceiling — 1 PRIMARY plus 1 HELPER fills ceiling any further spawn denied`() {
        assertEquals(RegistrationResult.Registered,
            registry.register("primary-ce7", AgentSlot.PRIMARY))
        assertEquals(RegistrationResult.Registered,
            registry.register("helper-ce7", AgentSlot.HELPER))
        assertEquals(2, registry.activeCount())

        // Attempt a third spawn of either slot
        val deniedHelper  = registry.register("extra-helper-ce7", AgentSlot.HELPER)
        val deniedPrimary = registry.register("extra-primary-ce7", AgentSlot.PRIMARY)

        assertTrue(deniedHelper  is RegistrationResult.Denied)
        assertTrue(deniedPrimary is RegistrationResult.Denied)
        assertEquals(2, registry.activeCount(), "Ceiling must hold after both denials")

        // Deregister one — now a spawn should succeed
        registry.deregister("helper-ce7")
        assertEquals(1, registry.activeCount())
        assertEquals(RegistrationResult.Registered,
            registry.register("helper-ce7-replacement", AgentSlot.HELPER),
            "After deregistering, a new HELPER must be permitted")
        assertEquals(2, registry.activeCount())
    }

    // -------------------------------------------------------------------------
    // CE-8: High-concurrency stress — 20 threads race for 2 slots
    // -------------------------------------------------------------------------

    @RepeatedTest(5)
    fun `CE-8 20 concurrent spawn attempts — final count never exceeds 2`() {
        val THREAD_COUNT = 20
        val startGate    = CountDownLatch(1)
        val pool         = Executors.newFixedThreadPool(THREAD_COUNT)
        val successCount = AtomicInteger(0)
        val failCount    = AtomicInteger(0)

        repeat(THREAD_COUNT) { i ->
            val slot = if (i % 2 == 0) AgentSlot.PRIMARY else AgentSlot.HELPER
            pool.submit {
                startGate.await()
                val r = registry.register("stress-ce8-$i", slot)
                if (r is RegistrationResult.Registered) successCount.incrementAndGet()
                else failCount.incrementAndGet()
            }
        }

        startGate.countDown()
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)

        val finalCount = registry.activeCount()
        assertTrue(finalCount <= 2,
            "Final active count must never exceed 2 (was $finalCount)")
        assertEquals(successCount.get(), finalCount,
            "DB active_agents count must match the number of Registered results")
        assertEquals(THREAD_COUNT, successCount.get() + failCount.get(),
            "Every thread must have received a result")
    }
}
