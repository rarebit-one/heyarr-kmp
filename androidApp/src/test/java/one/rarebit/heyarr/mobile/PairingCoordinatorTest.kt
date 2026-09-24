package one.rarebit.heyarr.mobile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import one.rarebit.heyarr.mobile.device.CruciformAnnouncer
import one.rarebit.heyarr.mobile.device.EnrolClient
import one.rarebit.heyarr.mobile.device.InMemoryPendingPairingStore
import one.rarebit.heyarr.mobile.device.PairingCoordinator
import one.rarebit.heyarr.mobile.device.PairingFailure
import one.rarebit.heyarr.mobile.device.PairingState
import one.rarebit.heyarr.mobile.device.PairingSteps
import one.rarebit.heyarr.mobile.device.PendingPairing
import one.rarebit.voidbind.Invite
import one.rarebit.voidbind.flow.PairingFailureKind
import one.rarebit.voidbind.flow.PairingOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app-scoped pairing state machine: join → SAS → human gate → admission → enrol,
 * keyed by the relay session id, honouring the relay session TTL, persisting a
 * pending record for the life of the session, and honest after a process death.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingCoordinatorTest {

    private val usr = "ed25519:" + "ab".repeat(32)

    /** This phone's device key as the handshake renders it — what the one-tap report carries. */
    private val DEVICE_ID = "ed25519:" + "cd".repeat(32)
    private fun invite(session: String) =
        Invite.encode("http://192.168.16.5:8788", session, ByteArray(32) { it.toByte() }, usr)

    private val inviteA = invite("sessA")
    private val inviteB = invite("sessB")

    private var now = 1_000_000L
    private val ttl = PairingCoordinator.RELAY_SESSION_TTL_MILLIS

    /** Scripted steps: each step waits on its gate so a test can interleave the human. */
    private class Fake : PairingSteps {
        val handshakeGate = CompletableDeferred<PairingOutcome<PairingSteps.Handshaked>>()
        val receiveGate = CompletableDeferred<PairingOutcome<String>>()
        var registerOutcome: EnrolClient.Outcome = EnrolClient.Outcome.Registered("POST /enrol")
        var registerCalls = 0
        var handshakeDeadline = 0L
        var receiveDeadline = 0L
        var handshakeInvite: String? = null

        override suspend fun handshake(inviteQr: String, deadlineMillis: Long): PairingOutcome<PairingSteps.Handshaked> {
            handshakeInvite = inviteQr
            handshakeDeadline = deadlineMillis
            return handshakeGate.await()
        }

        override suspend fun receive(deadlineMillis: Long): PairingOutcome<String> {
            receiveDeadline = deadlineMillis
            return receiveGate.await()
        }

        override suspend fun register(op: String): EnrolClient.Outcome {
            registerCalls++
            return registerOutcome
        }
    }

    /**
     * The coordinator on an app-like scope driven by the test scheduler (a supervisor,
     * like `HeyarrApp.appScope`), so `advanceUntilIdle` runs its pipeline and a gate
     * left open at the end of a test does not hang `runTest`.
     */

    /** Records what was reported to Cruciform, and whether an activity "took" it. */
    private class RecordingAnnouncer(val taken: Boolean) : CruciformAnnouncer {
        val reports = ArrayList<Triple<String, String, String>>()
        override fun announceJoined(session: String, deviceId: String, sas: String): Boolean {
            reports += Triple(session, deviceId, sas)
            return taken
        }
    }

    private class Harness(
        scope: TestScope,
        now: () -> Long,
        val store: InMemoryPendingPairingStore = InMemoryPendingPairingStore(),
        val announcer: CruciformAnnouncer = CruciformAnnouncer.None,
    ) {
        val created = ArrayList<Pair<PendingPairing, Fake>>()
        val coordinator = PairingCoordinator(
            scope = CoroutineScope(StandardTestDispatcher(scope.testScheduler) + SupervisorJob()),
            store = store,
            steps = { p -> Fake().also { created += p to it } },
            clock = now,
            announcer = announcer,
        )
        val state get() = coordinator.state.value
        val last get() = created.last().second
    }

    private fun failed(kind: PairingFailureKind) = PairingOutcome.Failed(kind, "msg:$kind", "relay")

    @Test fun `the happy path - join, SAS, match, admission, enrol - lands Enrolled and clears the pending record`() = runTest(StandardTestDispatcher()) {
        val h = Harness(this, { now })
        val c = h.coordinator

        c.start(inviteA, sameDevice = true)
        advanceUntilIdle()
        val joining = h.state as PairingState.Joining
        assertEquals("sessA", joining.session)
        assertTrue(joining.sameDevice)
        assertEquals(now + ttl, joining.deadlineMillis)
        assertEquals(now + ttl, h.last.handshakeDeadline)
        assertEquals(inviteA, h.last.handshakeInvite)
        assertEquals(PendingPairing("sessA", inviteA, true, now), h.store.pending)

        h.last.handshakeGate.complete(PairingOutcome.Ready(PairingSteps.Handshaked("1234567", DEVICE_ID)))
        advanceUntilIdle()
        val compare = h.state as PairingState.CompareSas
        assertEquals("1234567", compare.sas)
        assertFalse(compare.awaitingAdmission)
        assertEquals(now + ttl, compare.deadlineMillis)

        c.confirmMatch()
        advanceUntilIdle()
        val awaiting = h.state as PairingState.CompareSas
        assertTrue("the SAS stays up while the admission is awaited", awaiting.awaitingAdmission)
        assertEquals("1234567", awaiting.sas)
        assertEquals(now + ttl, h.last.receiveDeadline)
        assertNotNull("still pending while the relay is waited on", h.store.pending)

        h.last.receiveGate.complete(PairingOutcome.Ready("op-token"))
        advanceUntilIdle()
        val enrolled = h.state as PairingState.Enrolled
        assertTrue(enrolled.registered)
        assertEquals("op-token", enrolled.op)
        assertEquals(1, h.last.registerCalls)
        assertNull("the admission is on disk — nothing to resume", h.store.pending)
    }

    @Test fun `Cruciform's refusal ends the wait now, with its wording, and only for the live session`() = runTest(StandardTestDispatcher()) {
        val h = Harness(this, { now }, announcer = RecordingAnnouncer(taken = true))
        h.coordinator.start(inviteA, sameDevice = true)
        advanceUntilIdle()
        h.last.handshakeGate.complete(PairingOutcome.Ready(PairingSteps.Handshaked("1234567", DEVICE_ID)))
        advanceUntilIdle()
        assertTrue((h.state as PairingState.CompareSas).handedOff)
        h.coordinator.refuse("sessB", "not ours")
        assertTrue(h.state is PairingState.CompareSas)
        h.coordinator.refuse("sessA", "the SAS differed.")
        val failed = h.state as PairingState.Failed
        assertEquals(PairingFailure.MISMATCH, failed.kind)
        assertEquals("Cruciform refused the pairing: the SAS differed. Nothing was exchanged.", failed.message)
        assertEquals(null, h.store.pending)
    }

    @Test fun `re-firing the same invite while it is live is a no-op and a different one supersedes it`() = runTest(StandardTestDispatcher()) {
        val h = Harness(this, { now })
        val c = h.coordinator
        c.start(inviteA, sameDevice = true)
        advanceUntilIdle()
        val first = h.last
        c.start(inviteA, sameDevice = true) // Android re-delivering the launching intent
        advanceUntilIdle()
        assertEquals(1, h.created.size)
        assertSame(first, h.last)

        c.start(inviteB, sameDevice = false)
        advanceUntilIdle()
        assertEquals(2, h.created.size)
        assertEquals("sessB", (h.state as PairingState.Joining).session)
        assertEquals("sessB", h.store.pending?.session)
        // The old session's late result must not resurface.
        first.handshakeGate.complete(PairingOutcome.Ready(PairingSteps.Handshaked("0000000", DEVICE_ID)))
        advanceUntilIdle()
        assertTrue(h.state is PairingState.Joining)
    }

    @Test fun `a relay timeout and an unreachable relay are told apart`() = runTest(StandardTestDispatcher()) {
        val h = Harness(this, { now })
        h.coordinator.start(inviteA, true)
        advanceUntilIdle()
        h.last.handshakeGate.complete(failed(PairingFailureKind.TIMEOUT))
        advanceUntilIdle()
        val timeout = h.state as PairingState.Failed
        assertEquals(PairingFailure.TIMEOUT, timeout.kind)
        assertEquals("sessA", timeout.session)
        assertNull(h.store.pending)

        h.coordinator.dismiss()
        assertEquals(PairingState.Idle, h.state)
        h.coordinator.start(inviteB, true)
        advanceUntilIdle()
        h.last.handshakeGate.complete(failed(PairingFailureKind.UNREACHABLE))
        advanceUntilIdle()
        assertEquals(PairingFailure.UNREACHABLE, (h.state as PairingState.Failed).kind)
    }

    @Test fun `they differ aborts before anything is received`() = runTest(StandardTestDispatcher()) {
        val h = Harness(this, { now })
        h.coordinator.start(inviteA, true)
        advanceUntilIdle()
        h.last.handshakeGate.complete(PairingOutcome.Ready(PairingSteps.Handshaked("7654321", DEVICE_ID)))
        advanceUntilIdle()
        h.coordinator.rejectMatch()
        advanceUntilIdle()
        val f = h.state as PairingState.Failed
        assertEquals(PairingFailure.MISMATCH, f.kind)
        assertFalse(h.last.receiveGate.isCompleted)
        assertEquals(0, h.last.registerCalls)
        assertNull(h.store.pending)
    }

    @Test fun `cancel during the relay wait returns to idle and clears the record`() = runTest(StandardTestDispatcher()) {
        val h = Harness(this, { now })
        h.coordinator.start(inviteA, true)
        advanceUntilIdle()
        h.coordinator.cancel()
        advanceUntilIdle()
        assertEquals(PairingState.Idle, h.state)
        assertNull(h.store.pending)
        h.last.handshakeGate.complete(PairingOutcome.Ready(PairingSteps.Handshaked("1111111", DEVICE_ID)))
        advanceUntilIdle()
        assertEquals("a cancelled session's late result is dropped", PairingState.Idle, h.state)
    }

    @Test fun `a failed registration is retriable but an admin-gated one is not`() = runTest(StandardTestDispatcher()) {
        val h = Harness(this, { now })
        h.coordinator.start(inviteA, true)
        advanceUntilIdle()
        h.last.handshakeGate.complete(PairingOutcome.Ready(PairingSteps.Handshaked("1234567", DEVICE_ID)))
        advanceUntilIdle()
        h.coordinator.confirmMatch()
        advanceUntilIdle()
        h.last.registerOutcome = EnrolClient.Outcome.Failed("could not sign with the device key")
        h.last.receiveGate.complete(PairingOutcome.Ready("op-token"))
        advanceUntilIdle()
        val stored = h.state as PairingState.Enrolled
        assertFalse(stored.registered)
        assertTrue(stored.retriable)
        assertFalse(stored.needsAdmin)

        h.last.registerOutcome = EnrolClient.Outcome.NeedsAdmin("no /enrol route")
        h.coordinator.retryRegister()
        advanceUntilIdle()
        val admin = h.state as PairingState.Enrolled
        assertEquals(2, h.last.registerCalls)
        assertTrue(admin.needsAdmin)
        assertFalse(admin.retriable)
        h.coordinator.retryRegister()
        advanceUntilIdle()
        assertEquals("not retriable: no third call", 2, h.last.registerCalls)
    }

    @Test fun `a pending record from a previous process reports interrupted, and expired past the TTL`() = runTest(StandardTestDispatcher()) {
        val store = InMemoryPendingPairingStore().apply { pending = PendingPairing("sessA", inviteA, true, now - 60_000) }
        val h = Harness(this, { now }, store)
        val f = h.state as PairingState.Failed
        assertEquals(PairingFailure.INTERRUPTED, f.kind)
        assertEquals("sessA", f.session)
        assertTrue(f.sameDevice)
        assertNull("reported once, then forgotten", store.pending)
        // The re-delivered launching intent for that same session must NOT re-join it
        // (the relay's slots are write-once; it would only be refused).
        h.coordinator.start(inviteA, true)
        advanceUntilIdle()
        assertEquals(0, h.created.size)
        assertEquals(PairingFailure.INTERRUPTED, (h.state as PairingState.Failed).kind)
        // A fresh invite is fine.
        h.coordinator.start(inviteB, true)
        advanceUntilIdle()
        assertEquals("sessB", (h.state as PairingState.Joining).session)

        val old = InMemoryPendingPairingStore().apply { pending = PendingPairing("sessA", inviteA, false, now - ttl) }
        assertEquals(PairingFailure.EXPIRED, (Harness(this, { now }, old).state as PairingState.Failed).kind)
    }

    @Test fun `something that is not an invite fails as INVALID without a session`() = runTest(StandardTestDispatcher()) {
        val h = Harness(this, { now })
        h.coordinator.start("voidbind:login?id=x&rp=http%3A%2F%2Fh", true)
        advanceUntilIdle()
        val f = h.state as PairingState.Failed
        assertEquals(PairingFailure.INVALID, f.kind)
        assertNull(f.session)
        assertEquals(0, h.created.size)
        assertNull(h.store.pending)
    }

    // ── the same-phone one-tap channel (voidbind-kmp ADR-0008) ──────────────────

    @Test fun `a deep-linked invite reports its key and SAS to Cruciform and skips the human comparison`() = runTest(StandardTestDispatcher()) {
        val announcer = RecordingAnnouncer(taken = true)
        val h = Harness(this, { now }, announcer = announcer)
        val c = h.coordinator

        c.start(inviteA, sameDevice = true)
        advanceUntilIdle()
        h.last.handshakeGate.complete(PairingOutcome.Ready(PairingSteps.Handshaked("1234567", DEVICE_ID)))
        advanceUntilIdle()

        // Exactly what Cruciform compares against the relay: our session, our key, our SAS.
        assertEquals(listOf(Triple("sessA", DEVICE_ID, "1234567")), announcer.reports)
        val compare = h.state as PairingState.CompareSas
        assertTrue("the apps compared — no human gate on this side", compare.handedOff)
        assertTrue("straight to awaiting Cruciform's admission", compare.awaitingAdmission)
        // The SAS is still carried: the screen reveals it if Cruciform never comes back.
        assertEquals("1234567", compare.sas)
        // And the pipeline really did advance — receive() is running against the deadline.
        assertEquals(now + ttl, h.last.receiveDeadline)

        h.last.receiveGate.complete(PairingOutcome.Ready("op-token"))
        advanceUntilIdle()
        assertTrue(h.state is PairingState.Enrolled)
    }

    @Test fun `a scanned invite never reports to Cruciform - the human comparison is the only channel there is`() = runTest(StandardTestDispatcher()) {
        // sameDevice=false is a QR from another phone or the Mac: there IS no local
        // channel, and firing one would be reporting to an app that is not the peer.
        val announcer = RecordingAnnouncer(taken = true)
        val h = Harness(this, { now }, announcer = announcer)
        h.coordinator.start(inviteA, sameDevice = false)
        advanceUntilIdle()
        h.last.handshakeGate.complete(PairingOutcome.Ready(PairingSteps.Handshaked("1234567", DEVICE_ID)))
        advanceUntilIdle()

        assertTrue(announcer.reports.isEmpty())
        val compare = h.state as PairingState.CompareSas
        assertFalse(compare.handedOff)
        assertFalse("the human still has to compare", compare.awaitingAdmission)
    }

    @Test fun `a report nothing takes falls back to the human comparison`() = runTest(StandardTestDispatcher()) {
        // Cruciform absent, or an older build with no `pair-joined` filter: the launch
        // resolves to nothing and this phone behaves exactly as it did before ADR-0008.
        val announcer = RecordingAnnouncer(taken = false)
        val h = Harness(this, { now }, announcer = announcer)
        val c = h.coordinator
        c.start(inviteA, sameDevice = true)
        advanceUntilIdle()
        h.last.handshakeGate.complete(PairingOutcome.Ready(PairingSteps.Handshaked("1234567", DEVICE_ID)))
        advanceUntilIdle()

        assertEquals(1, announcer.reports.size)
        val compare = h.state as PairingState.CompareSas
        assertFalse(compare.handedOff)
        assertFalse(compare.awaitingAdmission)

        // …and the ordinary human gate still drives it to the end.
        c.confirmMatch()
        advanceUntilIdle()
        h.last.receiveGate.complete(PairingOutcome.Ready("op-token"))
        advanceUntilIdle()
        assertTrue(h.state is PairingState.Enrolled)
    }

    @Test fun `a handshake with no device key never reports`() = runTest(StandardTestDispatcher()) {
        // Defensive: an empty key would have Cruciform compare against nothing.
        val announcer = RecordingAnnouncer(taken = true)
        val h = Harness(this, { now }, announcer = announcer)
        h.coordinator.start(inviteA, sameDevice = true)
        advanceUntilIdle()
        h.last.handshakeGate.complete(PairingOutcome.Ready(PairingSteps.Handshaked("1234567", "")))
        advanceUntilIdle()

        assertTrue(announcer.reports.isEmpty())
        assertFalse((h.state as PairingState.CompareSas).handedOff)
    }

    @Test fun `a stray codes-match tap on the handed-off path changes nothing`() = runTest(StandardTestDispatcher()) {
        val h = Harness(this, { now }, announcer = RecordingAnnouncer(taken = true))
        val c = h.coordinator
        c.start(inviteA, sameDevice = true)
        advanceUntilIdle()
        h.last.handshakeGate.complete(PairingOutcome.Ready(PairingSteps.Handshaked("1234567", DEVICE_ID)))
        advanceUntilIdle()

        c.confirmMatch()
        c.rejectMatch()
        advanceUntilIdle()
        val compare = h.state as PairingState.CompareSas
        assertTrue("already awaiting Cruciform; the buttons are not even shown", compare.awaitingAdmission)
        assertTrue(compare.handedOff)
    }
}
