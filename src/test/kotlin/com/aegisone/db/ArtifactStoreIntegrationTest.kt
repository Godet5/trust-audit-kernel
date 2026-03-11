package com.aegisone.db

import com.aegisone.review.ArtifactState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for SQLiteArtifactStore and SQLiteArtifactLockManager.
 *
 * These tests prove the real implementations satisfy the ArtifactStore and
 * ArtifactLockManager interfaces with durable SQLite WAL backing.
 *
 * The I-4 invariant tests remain unchanged and continue to use in-memory
 * test doubles to prove structural invariants. These tests prove the real
 * implementations are correct against the same interface contracts.
 *
 * ArtifactStore tests (AS-1..8):
 *   AS-1: setState and getState round-trip
 *   AS-2: setState update overwrites previous state
 *   AS-3: getState returns null for unknown artifact
 *   AS-4: writeField and readField round-trip
 *   AS-5: writeField update overwrites previous value
 *   AS-6: readField returns null for unknown field
 *   AS-7: multiple fields per artifact stored independently
 *   AS-8: multiple artifacts stored independently
 *
 * ArtifactLockManager tests (ALM-1..7):
 *   ALM-1: lock and lockHolder round-trip
 *   ALM-2: release removes lock — lockHolder returns null
 *   ALM-3: lockHolder returns null for unknown artifact
 *   ALM-4: lockedBy returns all artifacts for a session
 *   ALM-5: release is no-op for unlocked artifact (does not throw)
 *   ALM-6: lock transfer — second lock() replaces holder
 *   ALM-7: lockedBy returns empty list for session with no locks
 */
class ArtifactStoreIntegrationTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var shared: SharedConnection
    private lateinit var store: SQLiteArtifactStore
    private lateinit var lockMgr: SQLiteArtifactLockManager

    @BeforeEach
    fun setup() {
        val path = File(tempDir, "review.db").absolutePath
        shared = ReviewDbBootstrap.openAndInitialize(path)
        store = SQLiteArtifactStore(shared)
        lockMgr = SQLiteArtifactLockManager(shared)
    }

    @AfterEach
    fun teardown() {
        runCatching { shared.close() }
    }

    // === ArtifactStore ===

    @Test
    fun `AS-1 setState and getState round-trip`() {
        store.setState("art-001", ArtifactState.SUBMITTED)
        assertEquals(ArtifactState.SUBMITTED, store.getState("art-001"))
    }

    @Test
    fun `AS-2 setState update overwrites previous state`() {
        store.setState("art-002", ArtifactState.SUBMITTED)
        store.setState("art-002", ArtifactState.UNDER_REVIEW)
        assertEquals(ArtifactState.UNDER_REVIEW, store.getState("art-002"))
    }

    @Test
    fun `AS-3 getState returns null for unknown artifact`() {
        assertNull(store.getState("no-such-artifact"))
    }

    @Test
    fun `AS-4 writeField and readField round-trip`() {
        store.writeField("art-003", "verdict", "approved pending edits")
        assertEquals("approved pending edits", store.readField("art-003", "verdict"))
    }

    @Test
    fun `AS-5 writeField update overwrites previous value`() {
        store.writeField("art-004", "notes", "first note")
        store.writeField("art-004", "notes", "revised note")
        assertEquals("revised note", store.readField("art-004", "notes"))
    }

    @Test
    fun `AS-6 readField returns null for unknown field`() {
        store.setState("art-005", ArtifactState.SUBMITTED)
        assertNull(store.readField("art-005", "nonexistent_field"))
    }

    @Test
    fun `AS-7 multiple fields per artifact stored independently`() {
        store.writeField("art-006", "verdict", "approved")
        store.writeField("art-006", "reviewer", "reviewer-x")
        store.writeField("art-006", "comment", "looks good")

        assertEquals("approved", store.readField("art-006", "verdict"))
        assertEquals("reviewer-x", store.readField("art-006", "reviewer"))
        assertEquals("looks good", store.readField("art-006", "comment"))
    }

    @Test
    fun `AS-8 multiple artifacts stored independently`() {
        store.setState("art-007", ArtifactState.APPROVED)
        store.setState("art-008", ArtifactState.REJECTED)
        store.writeField("art-007", "f", "v-007")
        store.writeField("art-008", "f", "v-008")

        assertEquals(ArtifactState.APPROVED, store.getState("art-007"))
        assertEquals(ArtifactState.REJECTED, store.getState("art-008"))
        assertEquals("v-007", store.readField("art-007", "f"))
        assertEquals("v-008", store.readField("art-008", "f"))
    }

    // === ArtifactLockManager ===

    @Test
    fun `ALM-1 lock and lockHolder round-trip`() {
        lockMgr.lock("art-010", "sess-a")
        assertEquals("sess-a", lockMgr.lockHolder("art-010"))
    }

    @Test
    fun `ALM-2 release removes lock — lockHolder returns null`() {
        lockMgr.lock("art-011", "sess-b")
        lockMgr.release("art-011")
        assertNull(lockMgr.lockHolder("art-011"))
    }

    @Test
    fun `ALM-3 lockHolder returns null for unknown artifact`() {
        assertNull(lockMgr.lockHolder("no-lock-here"))
    }

    @Test
    fun `ALM-4 lockedBy returns all artifacts for a session`() {
        lockMgr.lock("art-012", "sess-c")
        lockMgr.lock("art-013", "sess-c")
        lockMgr.lock("art-014", "sess-d")  // different session

        val lockedBySessC = lockMgr.lockedBy("sess-c").toSet()
        assertEquals(setOf("art-012", "art-013"), lockedBySessC)
    }

    @Test
    fun `ALM-5 release is no-op for unlocked artifact`() {
        // Must not throw
        lockMgr.release("never-locked")
    }

    @Test
    fun `ALM-6 lock conflict — second session cannot steal lock`() {
        assertTrue(lockMgr.lock("art-015", "sess-e"),
            "First lock() must succeed on an unlocked artifact")
        assertFalse(lockMgr.lock("art-015", "sess-f"),
            "Conflicting lock() from a different session must be rejected")
        assertEquals("sess-e", lockMgr.lockHolder("art-015"),
            "Original holder must not be displaced by a conflicting lock attempt")
    }

    @Test
    fun `ALM-7 lockedBy returns empty list for session with no locks`() {
        assertTrue(lockMgr.lockedBy("sess-nobody").isEmpty())
    }

    @Test
    fun `ALM-8 same-session re-lock is idempotent — returns true`() {
        assertTrue(lockMgr.lock("art-016", "sess-i"))
        assertTrue(lockMgr.lock("art-016", "sess-i"),
            "Re-lock by the same session must return true")
        assertEquals("sess-i", lockMgr.lockHolder("art-016"),
            "Holder must remain unchanged after idempotent re-lock")
    }

    // === Cross-component: store + lock manager share the same connection ===

    @Test
    fun `CROSS-1 artifact state and lock both readable after writes on shared connection`() {
        store.setState("art-020", ArtifactState.UNDER_REVIEW)
        lockMgr.lock("art-020", "sess-g")
        store.writeField("art-020", "field-a", "value-a")

        assertEquals(ArtifactState.UNDER_REVIEW, store.getState("art-020"))
        assertEquals("sess-g", lockMgr.lockHolder("art-020"))
        assertEquals("value-a", store.readField("art-020", "field-a"))
    }

    @Test
    fun `CROSS-2 release lock then setState models session expiry reversion`() {
        // Models MemorySteward.onSessionExpired: release lock, set state to SUBMITTED
        store.setState("art-021", ArtifactState.UNDER_REVIEW)
        lockMgr.lock("art-021", "sess-h")

        lockMgr.release("art-021")
        store.setState("art-021", ArtifactState.SUBMITTED)

        assertNull(lockMgr.lockHolder("art-021"), "Lock must be released")
        assertEquals(ArtifactState.SUBMITTED, store.getState("art-021"),
            "State must revert to SUBMITTED after expiry")
    }
}
