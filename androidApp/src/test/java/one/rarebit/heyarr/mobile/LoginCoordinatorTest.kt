package one.rarebit.heyarr.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import one.rarebit.heyarr.mobile.login.InMemoryPendingLoginStore
import one.rarebit.heyarr.mobile.login.LoginCoordinator
import one.rarebit.heyarr.mobile.login.LoginFlow
import one.rarebit.heyarr.mobile.login.LoginTuple
import one.rarebit.heyarr.mobile.login.PendingLogin
import one.rarebit.heyarr.mobile.login.VoidbindLogin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app-scoped QR login (#172): the poll outlives the ViewModel, the open login is
 * persisted while it is open, and a restart after a process death resumes the poll for
 * what is left of its window instead of losing an approval made in Cruciform.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginCoordinatorTest {

    private val rp = "https://node.example"
    private val timeout = LoginCoordinator.LOGIN_TIMEOUT_MILLIS
    private var now = 1_000_000L

    private fun qr(id: String) = "void-which-binds:login?rp=https%3A%2F%2Fnode.example&id=$id"

    /** A scripted login: begin hands out [id]; each poll returns [result]. */
    private inner class Fake(val id: String = "login-1") : VoidbindLogin {
        var begins = 0
        val polls = mutableListOf<Pair<String, Long>>()
        var result: VoidbindLogin.Result = VoidbindLogin.Result.Approved("session-token", "usr")
        var beginError: RuntimeException? = null

        /** Run during the poll: what the store held then, or a side effect (a cancel). */
        var onPoll: () -> Unit = {}

        override fun begin(): VoidbindLogin.Pending {
            begins++
            beginError?.let { throw it }
            return VoidbindLogin.Pending(id, LoginTuple.decode(qr(id)), qr(id))
        }

        override fun awaitApproval(pending: VoidbindLogin.Pending, timeoutMs: Long): VoidbindLogin.Result {
            polls += pending.loginId to timeoutMs
            onPoll()
            return result
        }
    }

    private fun coordinator(scope: TestScope, store: InMemoryPendingLoginStore, fake: Fake): LoginCoordinator {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        return LoginCoordinator(
            // Foreground work on the test scheduler, as in PairingCoordinatorTest:
            // advanceUntilIdle does not run backgroundScope's.
            scope = CoroutineScope(dispatcher + SupervisorJob()),
            store = store,
            login = { fake },
            clock = { now },
            io = dispatcher,
        )
    }

    @Test
    fun startOpensPersistsPollsAndApproves() = runTest {
        val store = InMemoryPendingLoginStore()
        val fake = Fake()
        val c = coordinator(this, store, fake)
        var heldWhilePolling: PendingLogin? = null
        fake.onPoll = { heldWhilePolling = store.pending }

        c.start(rp)
        advanceUntilIdle()

        assertEquals("one login opened", 1, fake.begins)
        assertEquals("polled the opened id for the whole window", listOf("login-1" to timeout), fake.polls)
        assertEquals(
            "the open login is persisted while it is polled",
            PendingLogin(rp, "login-1", qr("login-1"), now),
            heldWhilePolling,
        )
        assertEquals(LoginFlow.Approved(rp, "session-token", "usr"), c.state.value)
        assertEquals("kept until the approval is applied", "login-1", store.pending?.loginId)
        c.consume()
        assertNull("cleared once consumed", store.pending)
    }

    /** The #172 case: the process died behind Cruciform; the restart collects the approval. */
    @Test
    fun aRestartResumesThePollForWhatIsLeftOfTheWindow() = runTest {
        val store = InMemoryPendingLoginStore()
        store.pending = PendingLogin(rp, "login-9", qr("login-9"), startedAtMillis = now - 30_000, handedOff = true)
        val fake = Fake()
        val c = coordinator(this, store, fake)
        assertTrue("resumed, still handed off", (c.state.value as LoginFlow.Awaiting).pending.handedOff)

        advanceUntilIdle()

        assertEquals("no new login opened", 0, fake.begins)
        assertEquals("the persisted id, for the rest of the window", listOf("login-9" to timeout - 30_000), fake.polls)
        assertEquals(LoginFlow.Approved(rp, "session-token", "usr", handedOff = true), c.state.value)
        c.consume()
        assertNull(store.pending)
    }

    @Test
    fun aRestartAfterTheWindowSaysItExpired() = runTest {
        val store = InMemoryPendingLoginStore()
        store.pending = PendingLogin(rp, "login-9", qr("login-9"), startedAtMillis = now - timeout)
        val fake = Fake()
        val c = coordinator(this, store, fake)
        advanceUntilIdle()

        assertTrue("expired", c.state.value is LoginFlow.Failed)
        assertTrue("nothing polled", fake.polls.isEmpty())
        assertNull(store.pending)
    }

    @Test
    fun aSecondStartOnTheSameNodeIsANoOp() = runTest {
        val fake = Fake()
        val c = coordinator(this, InMemoryPendingLoginStore(), fake)
        c.start(rp)
        c.start(rp)
        advanceUntilIdle()
        assertEquals(1, fake.begins)
    }

    @Test
    fun aCancelledLoginDropsItsLateOutcome() = runTest {
        val store = InMemoryPendingLoginStore()
        val fake = Fake()
        val c = coordinator(this, store, fake)
        // Signed out while the poll is in flight: its approval must not sign anyone in.
        fake.onPoll = { c.cancel() }
        c.start(rp)
        advanceUntilIdle()

        assertEquals(LoginFlow.Idle, c.state.value)
        assertNull(store.pending)
    }

    @Test
    fun handedOffIsPersisted() = runTest {
        val store = InMemoryPendingLoginStore()
        store.pending = PendingLogin(rp, "login-9", qr("login-9"), startedAtMillis = now)
        val c = coordinator(this, store, Fake())

        c.handedOff()

        assertTrue((c.state.value as LoginFlow.Awaiting).pending.handedOff)
        assertTrue("survives a restart", store.pending!!.handedOff)

        // Cruciform did not open after all: back to waiting on a scan, no service held.
        c.handOffFailed()
        assertFalse((c.state.value as LoginFlow.Awaiting).pending.handedOff)
        assertFalse(store.pending!!.handedOff)
    }

    @Test
    fun aDeclinedOrFailedLoginIsReportedThenConsumed() = runTest {
        val fake = Fake().apply { result = VoidbindLogin.Result.Denied("declined on the authenticator") }
        val c = coordinator(this, InMemoryPendingLoginStore(), fake)
        c.start(rp)
        advanceUntilIdle()
        assertEquals(LoginFlow.Failed("declined on the authenticator"), c.state.value)
        c.consume()
        assertEquals(LoginFlow.Idle, c.state.value)

        fake.beginError = IllegalArgumentException("node unreachable")
        c.start(rp)
        advanceUntilIdle()
        assertEquals(LoginFlow.Failed("node unreachable"), c.state.value)
    }
}
