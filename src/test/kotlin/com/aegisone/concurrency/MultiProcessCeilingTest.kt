package com.aegisone.concurrency

import com.aegisone.db.SQLiteAgentRegistry
import com.aegisone.db.SQLiteBootstrap
import com.aegisone.db.SharedConnection
import com.aegisone.execution.AgentSlot
import com.aegisone.execution.DenialReason
import com.aegisone.execution.RegistrationResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Multi-process ceiling enforcement tests (G-1 closure).
 *
 * Proves the Phase 0 2-agent ceiling holds across process boundaries, not just
 * thread boundaries. Simulated by opening separate SharedConnection instances to
 * the same SQLite file — from SQLite's perspective, each connection has its own
 * lock state, identical to separate OS processes.
 *
 * MP-1: Two registries on separate connections race to fill the ceiling.
 *        Exactly 2 agents registered, never 3. (Repeated 10×)
 *
 * MP-2: Per-slot ceiling holds across separate connections.
 *        Two connections both try to register HELPER — exactly 1 wins.
 *
 * MP-3: Deregistration on one connection, registration on another — ceiling
 *        transitions correctly across connection boundaries.
 *
 * MP-4: Stress test — 4 connections × 5 threads = 20 concurrent register()
 *        calls. Final count never exceeds 2. (Repeated 5×)
 *
 * MP-5: Cross-connection duplicate agent_id — rejected by both JVM logic
 *        and DB PRIMARY KEY regardless of which connection registers first.
 */
class MultiProcessCeilingTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var dbPath: String
    private val connections = mutableListOf<SharedConnection>()

    @BeforeEach
    fun setup() {
        dbPath = File(tempDir, "agents.db").absolutePath
        // Initialize schema via the first connection
        val bootstrap = SQLiteBootstrap.openAndInitialize(dbPath)
        connections.add(bootstrap)
    }

    @AfterEach
    fun teardown() {
        connections.forEach { runCatching { it.close() } }
    }

    /** Open a new independent connection to the same DB (simulates another process). */
    private fun openConnection(): SharedConnection {
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode=WAL")
            stmt.execute("PRAGMA synchronous=FULL")
            // WAL mode + busy_timeout ensures we wait for locks rather than fail immediately
            stmt.execute("PRAGMA busy_timeout=5000")
        }
        val shared = SharedConnection(conn)
        connections.add(shared)
        return shared
    }

    /** Count agents directly on a fresh statement (ground truth). */
    private fun countAgentsDirect(): Int {
        return connections[0].conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM active_agents").use { rs ->
                rs.next(); rs.getInt(1)
            }
        }
    }

    // --- MP-1: Two connections race to fill ceiling ---

    @RepeatedTest(10)
    fun `MP-1 two connections race to fill ceiling — count never exceeds 2`() {
        val conn1 = connections[0]
        val conn2 = openConnection()
        val reg1 = SQLiteAgentRegistry(conn1)
        val reg2 = SQLiteAgentRegistry(conn2)

        // Pre-fill one slot so ceiling is at 1/2
        assertEquals(RegistrationResult.Registered,
            reg1.register("primary-mp1", AgentSlot.PRIMARY))

        // Race: two connections each try to register a HELPER with different agent_ids
        val latch = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<RegistrationResult>()
        val pool = Executors.newFixedThreadPool(2)

        pool.submit {
            latch.await()
            results.add(reg1.register("helper-A-mp1", AgentSlot.HELPER))
        }
        pool.submit {
            latch.await()
            results.add(reg2.register("helper-B-mp1", AgentSlot.HELPER))
        }

        latch.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

        // Exactly one must win, one must be denied
        val registered = results.count { it is RegistrationResult.Registered }
        val denied = results.count { it is RegistrationResult.Denied }
        assertEquals(1, registered, "Exactly one HELPER must win")
        assertEquals(1, denied, "Exactly one HELPER must be denied")

        // Ground truth: exactly 2 agents in the DB
        val count = countAgentsDirect()
        assertEquals(2, count, "Ceiling must be exactly 2 — never 3")
    }

    // --- MP-2: Per-slot ceiling across connections ---

    @Test
    fun `MP-2 per-slot ceiling holds across separate connections`() {
        val conn1 = connections[0]
        val conn2 = openConnection()
        val reg1 = SQLiteAgentRegistry(conn1)
        val reg2 = SQLiteAgentRegistry(conn2)

        // Connection 1 registers a HELPER
        assertEquals(RegistrationResult.Registered,
            reg1.register("helper-1-mp2", AgentSlot.HELPER))

        // Connection 2 tries another HELPER — must be denied SLOT_OCCUPIED
        val result = reg2.register("helper-2-mp2", AgentSlot.HELPER)
        assertTrue(result is RegistrationResult.Denied,
            "Second HELPER must be denied")
        assertEquals(DenialReason.SLOT_OCCUPIED,
            (result as RegistrationResult.Denied).reason,
            "Denial must be SLOT_OCCUPIED")
    }

    // --- MP-3: Deregistration on one connection, registration on another ---

    @Test
    fun `MP-3 deregister on one connection frees slot for registration on another`() {
        val conn1 = connections[0]
        val conn2 = openConnection()
        val reg1 = SQLiteAgentRegistry(conn1)
        val reg2 = SQLiteAgentRegistry(conn2)

        // Fill ceiling via connection 1
        assertEquals(RegistrationResult.Registered,
            reg1.register("primary-mp3", AgentSlot.PRIMARY))
        assertEquals(RegistrationResult.Registered,
            reg1.register("helper-mp3", AgentSlot.HELPER))

        // Connection 2 tries — denied (ceiling full)
        val denied = reg2.register("helper-2-mp3", AgentSlot.HELPER)
        assertTrue(denied is RegistrationResult.Denied)

        // Connection 1 deregisters the HELPER
        reg1.deregister("helper-mp3")

        // Connection 2 tries again — must succeed now
        val result = reg2.register("helper-2-mp3", AgentSlot.HELPER)
        assertEquals(RegistrationResult.Registered, result,
            "Registration must succeed after deregistration freed the slot")
        assertEquals(2, countAgentsDirect())
    }

    // --- MP-4: Stress test — 4 connections × 5 threads ---

    @RepeatedTest(5)
    fun `MP-4 stress — 20 concurrent registrations across 4 connections — ceiling holds`() {
        val conns = (0 until 3).map { openConnection() } + connections[0]
        val registries = conns.map { SQLiteAgentRegistry(it) }
        val agentCounter = AtomicInteger(0)

        val threadCount = 20
        val latch = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<RegistrationResult>()
        val pool = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) { i ->
            val reg = registries[i % registries.size]
            pool.submit {
                latch.await()
                val id = "agent-${agentCounter.getAndIncrement()}"
                val slot = if (i % 2 == 0) AgentSlot.PRIMARY else AgentSlot.HELPER
                results.add(reg.register(id, slot))
            }
        }

        latch.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))

        val registered = results.count { it is RegistrationResult.Registered }
        val finalCount = countAgentsDirect()

        assertTrue(registered <= 2,
            "At most 2 registrations may succeed across all connections; got $registered")
        assertTrue(finalCount <= 2,
            "DB count must never exceed 2; got $finalCount")
        assertEquals(registered, finalCount,
            "Registered count must match DB count")
    }

    // --- MP-5: Cross-connection duplicate agent_id ---

    @Test
    fun `MP-5 duplicate agent_id rejected across connections`() {
        val conn1 = connections[0]
        val conn2 = openConnection()
        val reg1 = SQLiteAgentRegistry(conn1)
        val reg2 = SQLiteAgentRegistry(conn2)

        // Connection 1 registers
        assertEquals(RegistrationResult.Registered,
            reg1.register("dup-mp5", AgentSlot.PRIMARY))

        // Connection 2 tries the same agent_id
        val result = reg2.register("dup-mp5", AgentSlot.HELPER)
        assertTrue(result is RegistrationResult.Denied,
            "Duplicate agent_id must be denied across connections")
        assertEquals(DenialReason.DUPLICATE_AGENT_ID,
            (result as RegistrationResult.Denied).reason)
    }
}
