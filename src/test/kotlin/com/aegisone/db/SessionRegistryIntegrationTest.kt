package com.aegisone.db

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for SQLiteSessionRegistry.
 *
 * SRG-1:  openSession creates an ACTIVE session readable via lookup
 * SRG-2:  lookup returns null for unknown session_id
 * SRG-3:  heartbeat extends expiry_ms; updated value visible via lookup
 * SRG-4:  expireSessions marks stale ACTIVE sessions EXPIRED — lookup shows active=false
 * SRG-5:  expireSessions does not affect sessions not yet expired
 * SRG-6:  closeSession marks session CLOSED — lookup shows active=false
 * SRG-7:  closed session is not affected by expireSessions
 * SRG-8:  certFingerprint round-trips correctly through open/lookup
 * SRG-9:  openSession returns false (no duplicate) if session_id already exists
 * SRG-10: activeSessions returns only ACTIVE session IDs
 */
class SessionRegistryIntegrationTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var conn: Connection
    private lateinit var registry: SQLiteSessionRegistry

    @BeforeEach
    fun setup() {
        conn = ReviewDbBootstrap.openAndInitialize(File(tempDir, "review.db").absolutePath)
        registry = SQLiteSessionRegistry(conn)
    }

    @AfterEach
    fun teardown() {
        runCatching { conn.close() }
    }

    private val CERT = "cert-fingerprint-abc123"
    private val FAR_FUTURE = System.currentTimeMillis() + 3_600_000L   // +1h
    private val FAR_PAST   = System.currentTimeMillis() - 3_600_000L   // -1h

    @Test
    fun `SRG-1 openSession creates an ACTIVE session readable via lookup`() {
        assertTrue(registry.openSession("sess-01", CERT, FAR_FUTURE))

        val entry = registry.lookup("sess-01")
        assertNotNull(entry)
        assertEquals("sess-01", entry.sessionId)
        assertTrue(entry.active, "Newly opened session must be active")
        assertEquals(FAR_FUTURE, entry.expiryTime)
        assertEquals(CERT, entry.certFingerprint)
    }

    @Test
    fun `SRG-2 lookup returns null for unknown session_id`() {
        assertNull(registry.lookup("no-such-session"))
    }

    @Test
    fun `SRG-3 heartbeat extends expiry_ms — updated value visible via lookup`() {
        registry.openSession("sess-02", CERT, FAR_FUTURE)
        val extended = FAR_FUTURE + 3_600_000L

        assertTrue(registry.heartbeat("sess-02", extended))

        val entry = registry.lookup("sess-02")!!
        assertEquals(extended, entry.expiryTime, "Expiry must reflect the heartbeat update")
        assertTrue(entry.active)
    }

    @Test
    fun `SRG-4 expireSessions marks stale ACTIVE sessions EXPIRED — lookup shows active=false`() {
        registry.openSession("sess-03", CERT, FAR_PAST)  // already expired

        val count = registry.expireSessions()
        assertEquals(1, count, "One stale session must be transitioned")

        val entry = registry.lookup("sess-03")!!
        assertFalse(entry.active, "Expired session must show active=false")
    }

    @Test
    fun `SRG-5 expireSessions does not affect sessions not yet expired`() {
        registry.openSession("sess-04", CERT, FAR_FUTURE)  // not yet expired

        val count = registry.expireSessions()
        assertEquals(0, count)

        assertTrue(registry.lookup("sess-04")!!.active, "Non-stale session must remain active")
    }

    @Test
    fun `SRG-6 closeSession marks session CLOSED — lookup shows active=false`() {
        registry.openSession("sess-05", CERT, FAR_FUTURE)

        assertTrue(registry.closeSession("sess-05"))

        val entry = registry.lookup("sess-05")!!
        assertFalse(entry.active, "Closed session must show active=false")
    }

    @Test
    fun `SRG-7 closed session is not affected by expireSessions`() {
        // Session is past expiry but already CLOSED
        registry.openSession("sess-06", CERT, FAR_PAST)
        registry.closeSession("sess-06")

        val count = registry.expireSessions()
        assertEquals(0, count, "CLOSED session must not be transitioned by expireSessions")
    }

    @Test
    fun `SRG-8 certFingerprint round-trips correctly`() {
        val fingerprint = "sha256:deadbeef0102030405060708"
        registry.openSession("sess-07", fingerprint, FAR_FUTURE)
        assertEquals(fingerprint, registry.lookup("sess-07")!!.certFingerprint)
    }

    @Test
    fun `SRG-9 openSession returns false if session_id already exists`() {
        assertTrue(registry.openSession("sess-08", CERT, FAR_FUTURE))
        assertFalse(registry.openSession("sess-08", CERT, FAR_FUTURE),
            "Duplicate openSession must return false without overwriting")
    }

    @Test
    fun `SRG-10 activeSessions returns only ACTIVE session IDs`() {
        registry.openSession("sess-09", CERT, FAR_FUTURE)   // ACTIVE
        registry.openSession("sess-10", CERT, FAR_PAST)     // will be expired
        registry.openSession("sess-11", CERT, FAR_FUTURE)   // ACTIVE
        registry.openSession("sess-12", CERT, FAR_FUTURE)   // will be closed

        registry.expireSessions()
        registry.closeSession("sess-12")

        val active = registry.activeSessions().toSet()
        assertEquals(setOf("sess-09", "sess-11"), active,
            "Only ACTIVE sessions must be returned by activeSessions()")
    }
}
