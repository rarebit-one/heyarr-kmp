package one.rarebit.heyarr.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.rarebit.heyarr.desktop.device.EnrolClient
import one.rarebit.heyarr.desktop.device.PairingCoordinator
import one.rarebit.heyarr.desktop.device.PairingFailure
import one.rarebit.heyarr.desktop.device.PairingState
import one.rarebit.heyarr.desktop.device.PairingSteps
import one.rarebit.voidwhichbinds.Invite
import one.rarebit.voidwhichbinds.KeyRef
import one.rarebit.voidwhichbinds.Pairing
import one.rarebit.voidwhichbinds.flow.PairingFailureKind
import one.rarebit.voidwhichbinds.flow.PairingOutcome
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PairingCoordinatorTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest fun tearDown() = scope.cancel()

    /** A valid `voidbind:pair?v=3…` invite the FAKE steps ignore — it only has to decode. */
    private fun validInvite(): String {
        val usr = KeyRef.ed25519(ByteArray(32) { (it + 1).toByte() }).render()
        return Invite.encode(
            relay = "https://relay.example.test:7777",
            session = "sess-abc",
            salt = ByteArray(Pairing.MIN_SALT_LEN) { 7 },
            usr = usr,
        )
    }

    private class FakeSteps(
        val handshakeOut: PairingOutcome<PairingSteps.Handshaked>,
        val receiveOut: PairingOutcome<String> = PairingOutcome.Ready("op-token"),
        val registerOut: EnrolClient.Outcome = EnrolClient.Outcome.Registered("POST /enrol"),
    ) : PairingSteps {
        override fun handshake(inviteQr: String, deadlineMillis: Long) = handshakeOut
        override fun receive(deadlineMillis: Long) = receiveOut
        override fun register(op: String) = registerOut
    }

    private suspend fun PairingCoordinator.await(timeoutMs: Long = 5_000, p: (PairingState) -> Boolean): PairingState =
        withTimeout(timeoutMs) { state.first(p) }

    @Test fun happy_path_reaches_enrolled() = runBlocking {
        val steps = FakeSteps(PairingOutcome.Ready(PairingSteps.Handshaked("1234567", "ed25519:aa")))
        val c = PairingCoordinator(scope, steps = { steps })

        c.start(validInvite())
        val compare = c.await { it is PairingState.CompareSas && !it.awaitingAdmission }
        assertEquals("1234567", (compare as PairingState.CompareSas).sas)

        c.confirmMatch()
        val enrolled = c.await { it is PairingState.Enrolled }
        assertTrue((enrolled as PairingState.Enrolled).registered)
    }

    @Test fun mismatch_aborts_before_any_exchange() = runBlocking {
        val steps = FakeSteps(PairingOutcome.Ready(PairingSteps.Handshaked("7654321", "ed25519:bb")))
        val c = PairingCoordinator(scope, steps = { steps })

        c.start(validInvite())
        c.await { it is PairingState.CompareSas }
        c.rejectMatch()
        val failed = c.await { it is PairingState.Failed }
        assertEquals(PairingFailure.MISMATCH, (failed as PairingState.Failed).kind)
    }

    @Test fun an_unreachable_relay_fails_the_pairing() = runBlocking {
        val steps = FakeSteps(PairingOutcome.Failed(PairingFailureKind.UNREACHABLE, "Can't reach the relay.", "relay"))
        val c = PairingCoordinator(scope, steps = { steps })

        c.start(validInvite())
        val failed = c.await { it is PairingState.Failed }
        assertEquals(PairingFailure.UNREACHABLE, (failed as PairingState.Failed).kind)
    }

    @Test fun a_signed_refusal_from_cruciform_is_its_own_failure() = runBlocking {
        val steps = FakeSteps(PairingOutcome.Failed(PairingFailureKind.REFUSED, "Cruciform refused.", "refuse"))
        val c = PairingCoordinator(scope, steps = { steps })

        c.start(validInvite())
        val failed = c.await { it is PairingState.Failed }
        assertEquals(PairingFailure.REFUSED, (failed as PairingState.Failed).kind)
    }

    @Test fun a_node_that_needs_an_admin_still_stores_the_admission() = runBlocking {
        val steps = FakeSteps(
            handshakeOut = PairingOutcome.Ready(PairingSteps.Handshaked("1112223", "ed25519:cc")),
            registerOut = EnrolClient.Outcome.NeedsAdmin("this node has no /enrol route"),
        )
        val c = PairingCoordinator(scope, steps = { steps })

        c.start(validInvite())
        c.await { it is PairingState.CompareSas }
        c.confirmMatch()
        val enrolled = c.await { it is PairingState.Enrolled } as PairingState.Enrolled
        assertTrue(!enrolled.registered)
        assertTrue(enrolled.needsAdmin)
    }

    @Test fun a_garbage_invite_fails_fast_as_invalid() {
        val c = PairingCoordinator(scope, steps = { error("steps must not be built for an invalid invite") })
        c.start("not-an-invite")
        val s = c.state.value
        assertTrue(s is PairingState.Failed && s.kind == PairingFailure.INVALID)
    }
}
