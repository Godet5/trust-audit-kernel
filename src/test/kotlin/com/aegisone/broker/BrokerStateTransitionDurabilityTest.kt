package com.aegisone.broker

import com.aegisone.boot.SystemEvent
import com.aegisone.boot.SystemEventChannel
import com.aegisone.invariants.MutableVersionFloorProvider
import com.aegisone.invariants.TestAuthorityDecisionChannel
import com.aegisone.invariants.TestReceiptChannel
import com.aegisone.trust.Manifest
import com.aegisone.trust.TrustInit
import com.aegisone.invariants.TestZoneAStore
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P6 durable broker state transition tests.
 *
 * Proves that broker authority state changes are durably observable.
 * Target sentence: "broker authority state survives restart without
 * rewriting history."
 *
 * BST-1: ACTIVE → RESTRICTED on floor mismatch emits BrokerStateChanged
 *         with correct from/to/reason/manifestVersion.
 *
 * BST-2: No BrokerStateChanged emitted on normal grant (state unchanged).
 *
 * BST-3: BrokerStateChanged is emitted BEFORE the grant denial is returned,
 *         proving the event is recorded during the same call.
 *
 * BST-4: systemEventChannel is optional — broker works without it (no NPE).
 *
 * BST-5: BrokerStateChanged carries the correct manifest version at the
 *         time of transition, not the floor version.
 */
class BrokerStateTransitionDurabilityTest {

    private val KEY = byteArrayOf(1, 2, 3, 4)

    private fun testManifest(version: Int = 1) = Manifest(
        version = version,
        createdAt = System.currentTimeMillis() - 1000,
        schemaValid = true,
        signature = KEY.copyOf(),
        grants = listOf(CapabilityGrant("WRITE_NOTE", AgentRole.EXECUTOR, GrantAuthority.SYSTEM_POLICY)),
        agents = mapOf("exec-01" to AgentRole.EXECUTOR)
    )

    /** Collects all emitted SystemEvents for assertion. */
    private class CollectingEventChannel : SystemEventChannel {
        val events = mutableListOf<SystemEvent>()
        override fun emit(event: SystemEvent): Boolean {
            events.add(event)
            return true
        }
    }

    private fun setupBroker(
        manifestVersion: Int = 1,
        floorProvider: MutableVersionFloorProvider = MutableVersionFloorProvider(1),
        eventChannel: SystemEventChannel? = null
    ): Triple<CapabilityBroker, TestReceiptChannel, TestAuthorityDecisionChannel> {
        val store = TestZoneAStore(manifest = testManifest(manifestVersion))
        val trustInit = TrustInit(store)
        val signal = trustInit.verifyManifest()
        val receiptChannel = TestReceiptChannel()
        val decisionChannel = TestAuthorityDecisionChannel()

        val broker = CapabilityBroker(
            receiptChannel = receiptChannel,
            trustedIssuer = trustInit.identity,
            versionFloorProvider = floorProvider,
            authorityDecisionChannel = decisionChannel,
            systemEventChannel = eventChannel
        )
        assertTrue(broker.initialize(signal), "Broker must initialize successfully")
        assertEquals(BrokerState.ACTIVE, broker.state)
        return Triple(broker, receiptChannel, decisionChannel)
    }

    // --- BST-1: ACTIVE → RESTRICTED on floor mismatch emits BrokerStateChanged ---

    @Test
    fun `BST-1 ACTIVE to RESTRICTED on floor mismatch — BrokerStateChanged emitted`() {
        val eventChannel = CollectingEventChannel()
        val floorProvider = MutableVersionFloorProvider(1)
        val (broker, _, _) = setupBroker(
            manifestVersion = 1,
            floorProvider = floorProvider,
            eventChannel = eventChannel
        )

        // Raise the floor above manifest version → next grant triggers ACTIVE→RESTRICTED
        floorProvider.currentFloor = 5

        val result = broker.issueGrant("WRITE_NOTE", AgentRole.EXECUTOR, GrantAuthority.SYSTEM_POLICY)
        assertNull(result, "Grant must be denied on floor mismatch")
        assertEquals(BrokerState.RESTRICTED, broker.state)

        // Verify BrokerStateChanged event
        val stateChanges = eventChannel.events.filterIsInstance<SystemEvent.BrokerStateChanged>()
        assertEquals(1, stateChanges.size, "Exactly one BrokerStateChanged must be emitted")

        val event = stateChanges[0]
        assertEquals("ACTIVE", event.fromState)
        assertEquals("RESTRICTED", event.toState)
        assertEquals(1, event.manifestVersion, "Event must carry the manifest version at transition time")
        assertTrue(event.reason.contains("MANIFEST_VERSION_BELOW_FLOOR"),
            "Reason must identify the floor mismatch trigger")
    }

    // --- BST-2: No BrokerStateChanged on normal grant ---

    @Test
    fun `BST-2 normal grant — no BrokerStateChanged emitted`() {
        val eventChannel = CollectingEventChannel()
        val floorProvider = MutableVersionFloorProvider(1)
        val (broker, _, _) = setupBroker(
            manifestVersion = 1,
            floorProvider = floorProvider,
            eventChannel = eventChannel
        )

        val result = broker.issueGrant("WRITE_NOTE", AgentRole.EXECUTOR, GrantAuthority.SYSTEM_POLICY)
        assertNotNull(result, "Grant must succeed on clean path")
        assertEquals(BrokerState.ACTIVE, broker.state)

        val stateChanges = eventChannel.events.filterIsInstance<SystemEvent.BrokerStateChanged>()
        assertTrue(stateChanges.isEmpty(), "No BrokerStateChanged on clean grant path")
    }

    // --- BST-3: BrokerStateChanged emitted during the grant call ---

    @Test
    fun `BST-3 BrokerStateChanged emitted before denial returns — same call proof`() {
        val emitOrder = mutableListOf<String>()

        val eventChannel = object : SystemEventChannel {
            override fun emit(event: SystemEvent): Boolean {
                if (event is SystemEvent.BrokerStateChanged) {
                    emitOrder.add("STATE_CHANGED")
                }
                return true
            }
        }

        val floorProvider = MutableVersionFloorProvider(1)
        val (broker, _, _) = setupBroker(
            manifestVersion = 1,
            floorProvider = floorProvider,
            eventChannel = eventChannel
        )

        floorProvider.currentFloor = 5

        val result = broker.issueGrant("WRITE_NOTE", AgentRole.EXECUTOR, GrantAuthority.SYSTEM_POLICY)
        emitOrder.add("GRANT_RETURNED")

        assertNull(result)
        assertEquals(listOf("STATE_CHANGED", "GRANT_RETURNED"), emitOrder,
            "BrokerStateChanged must be emitted before the grant denial returns")
    }

    // --- BST-4: systemEventChannel is optional ---

    @Test
    fun `BST-4 no systemEventChannel — broker works without NPE on state transition`() {
        val floorProvider = MutableVersionFloorProvider(1)
        val (broker, _, _) = setupBroker(
            manifestVersion = 1,
            floorProvider = floorProvider,
            eventChannel = null  // no event channel
        )

        floorProvider.currentFloor = 5

        val result = broker.issueGrant("WRITE_NOTE", AgentRole.EXECUTOR, GrantAuthority.SYSTEM_POLICY)
        assertNull(result, "Grant must be denied on floor mismatch even without event channel")
        assertEquals(BrokerState.RESTRICTED, broker.state)
    }

    // --- BST-5: manifest version attribution ---

    @Test
    fun `BST-5 BrokerStateChanged carries manifest version not floor version`() {
        val eventChannel = CollectingEventChannel()
        val floorProvider = MutableVersionFloorProvider(1)
        val (broker, _, _) = setupBroker(
            manifestVersion = 3,
            floorProvider = floorProvider,
            eventChannel = eventChannel
        )

        // Floor at 10, manifest at 3 → mismatch
        floorProvider.currentFloor = 10

        broker.issueGrant("WRITE_NOTE", AgentRole.EXECUTOR, GrantAuthority.SYSTEM_POLICY)

        val event = eventChannel.events.filterIsInstance<SystemEvent.BrokerStateChanged>().single()
        assertEquals(3, event.manifestVersion,
            "manifestVersion in event must be the broker's verified manifest version (3), not the floor (10)")
        assertTrue(event.reason.contains("3") && event.reason.contains("10"),
            "Reason must include both the manifest version and floor for diagnostics")
    }
}
