package one.rarebit.heyarr.desktop.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import one.rarebit.heyarr.desktop.vault.SyncChanges
import one.rarebit.heyarr.desktop.vault.VaultSync
import one.rarebit.heyarr.desktop.vault.VaultSyncEngine
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The daemon loop's behaviour, isolated from crypto/HTTP/disk: a fake [VaultSync] pass and a
 * fake [SyncChanges] the test drives by hand. A large [periodMs] disarms the safety-net tick so
 * every pass after the first is caused by an explicit change signal, keeping the tests
 * deterministic rather than timing-dependent.
 */
class VaultSyncControllerTest {

    private val noStats = VaultSyncEngine.Stats(0, 0, 0, 0)

    /** A [SyncChanges] the test wakes on demand; a large timeout never fires on its own. */
    private class FakeChanges : SyncChanges {
        private val ch = Channel<Unit>(Channel.UNLIMITED)
        val closed = AtomicBoolean(false)
        fun signal() {
            ch.trySend(Unit)
        }
        override suspend fun awaitChange(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
            ch.receive()
            true
        } ?: false
        override fun close() {
            closed.set(true)
        }
    }

    private class FakeSync(private val body: () -> VaultSyncEngine.Stats) : VaultSync {
        val calls = AtomicInteger(0)
        override fun syncOnce(): VaultSyncEngine.Stats {
            calls.incrementAndGet()
            return body()
        }
    }

    private fun awaitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(10)
        }
        fail("condition not met within $timeoutMs ms")
    }

    private fun scope() = CoroutineScope(Dispatchers.Default + Job())

    @Test
    fun startRunsAnInitialPassThenParksIdle() {
        val scope = scope()
        try {
            val sync = FakeSync { VaultSyncEngine.Stats(3, 1, 0, 0) }
            val c = VaultSyncController(scope, engine = { sync }, changes = { FakeChanges() }, periodMs = 600_000, settleMs = 0)
            c.start()
            awaitUntil { sync.calls.get() == 1 && c.status is SyncStatus.Idle }
            assertEquals(VaultSyncEngine.Stats(3, 1, 0, 0), c.lastStats)
            assertNotNull(c.lastSyncAtMs)
            assertNull(c.lastError)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aLocalChangeWakesAnotherPass() {
        val scope = scope()
        try {
            val sync = FakeSync { noStats }
            val changes = FakeChanges()
            val c = VaultSyncController(scope, engine = { sync }, changes = { changes }, periodMs = 600_000, settleMs = 0)
            c.start()
            awaitUntil { sync.calls.get() == 1 }
            changes.signal()
            awaitUntil { sync.calls.get() == 2 }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aFailedPassBecomesErrorButTheLoopKeepsGoing() {
        val scope = scope()
        try {
            val boom = AtomicBoolean(true)
            val sync = FakeSync { if (boom.get()) throw RuntimeException("boom") else noStats }
            val changes = FakeChanges()
            val c = VaultSyncController(scope, engine = { sync }, changes = { changes }, periodMs = 600_000, settleMs = 0)
            c.start()
            awaitUntil { c.status is SyncStatus.Error }
            assertEquals("boom", (c.status as SyncStatus.Error).message)
            // The loop is still alive: fix the fault, poke it, and the next pass recovers.
            boom.set(false)
            changes.signal()
            awaitUntil { c.status is SyncStatus.Idle }
            assertNull(c.lastError)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aNullEngineReportsWaitingAndDoesNotCrash() {
        val scope = scope()
        try {
            val c = VaultSyncController(scope, engine = { null }, changes = { FakeChanges() }, periodMs = 600_000, settleMs = 0)
            c.start()
            awaitUntil { c.status is SyncStatus.Waiting }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun stopEndsTheLoopAndClosesTheWatch() {
        val scope = scope()
        try {
            val sync = FakeSync { noStats }
            val changes = FakeChanges()
            val c = VaultSyncController(scope, engine = { sync }, changes = { changes }, periodMs = 600_000, settleMs = 0)
            c.start()
            awaitUntil { sync.calls.get() == 1 }
            c.stop()
            assertEquals(SyncStatus.Off, c.status)
            awaitUntil { changes.closed.get() }
            // A change after stop must not run another pass.
            val before = sync.calls.get()
            changes.signal()
            Thread.sleep(300)
            assertEquals(before, sync.calls.get())
            assertTrue(!c.isRunning)
        } finally {
            scope.cancel()
        }
    }
}
