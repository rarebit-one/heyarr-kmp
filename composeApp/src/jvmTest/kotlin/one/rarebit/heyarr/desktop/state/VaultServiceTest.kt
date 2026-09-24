package one.rarebit.heyarr.desktop.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import one.rarebit.heyarr.desktop.settings.DesktopConfig
import one.rarebit.heyarr.desktop.vault.OpenedVault
import one.rarebit.heyarr.desktop.vault.PeriodicOnlyChanges
import one.rarebit.heyarr.desktop.vault.VaultCustody
import one.rarebit.heyarr.desktop.vault.VaultSync
import one.rarebit.heyarr.desktop.vault.VaultSyncEngine
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The async "get ready then run" glue, with the network/keyring/disk seams faked: it resolves
 * custody off-thread, retries while not-ready, and only once it succeeds hands the engine to the
 * controller and starts it. The controller and custody themselves are proven in their own tests.
 */
class VaultServiceTest {

    private class FakeSync : VaultSync {
        val calls = AtomicInteger(0)
        override fun syncOnce(): VaultSyncEngine.Stats {
            calls.incrementAndGet()
            return VaultSyncEngine.Stats(0, 0, 0, 0)
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

    private fun opened(id: String = "space-1") = OpenedVault(id, ByteArray(32) { it.toByte() })

    @Test
    fun resumeWithNoFolderStaysOff() {
        val scope = scope()
        try {
            val service = VaultService(
                scope,
                config = { DesktopConfig(vaultFolder = null) },
                rememberFolder = {},
                rememberSpaceId = {},
                openCustody = { fail("must not resolve custody when no folder is configured") },
                engineFor = { _, _ -> FakeSync() },
                watchFor = { PeriodicOnlyChanges },
            )
            service.resume()
            assertEquals(VaultPhase.Off, service.phase)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun enableResolvesToRunningAndDrivesTheEngine() {
        val scope = scope()
        try {
            var cfg = DesktopConfig(vaultFolder = null, vaultSpaceId = null)
            var savedSpaceId: String? = null
            val sync = FakeSync()
            val service = VaultService(
                scope,
                config = { cfg },
                rememberFolder = { cfg = cfg.copy(vaultFolder = it) },
                rememberSpaceId = {
                    savedSpaceId = it
                    cfg = cfg.copy(vaultSpaceId = it)
                },
                openCustody = { VaultCustody.Result(opened("s"), minted = true) },
                engineFor = { _, _ -> sync },
                watchFor = { PeriodicOnlyChanges },
                retryMs = 50,
            )
            service.enable("/tmp/vault")

            awaitUntil { service.phase == VaultPhase.Running && sync.calls.get() >= 1 }
            assertEquals("s", savedSpaceId) // a minted space id was persisted
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aNotReadyCustodyStaysPreparingThenResolvesOnRetry() {
        val scope = scope()
        try {
            var cfg = DesktopConfig(vaultFolder = "/tmp/vault")
            val attempts = AtomicInteger(0)
            val sync = FakeSync()
            val service = VaultService(
                scope,
                config = { cfg },
                rememberFolder = { cfg = cfg.copy(vaultFolder = it) },
                rememberSpaceId = { cfg = cfg.copy(vaultSpaceId = it) },
                openCustody = {
                    // Not enrolled the first two attempts, then it comes good.
                    if (attempts.incrementAndGet() < 3) {
                        VaultCustody.Result(null, minted = false, error = "not enrolled")
                    } else {
                        VaultCustody.Result(opened("s"), minted = true)
                    }
                },
                engineFor = { _, _ -> sync },
                watchFor = { PeriodicOnlyChanges },
                retryMs = 50,
            )
            service.resume()

            awaitUntil { service.phase == VaultPhase.Running }
            assertTrue(attempts.get() >= 3, "should have retried until ready, got ${attempts.get()}")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun disableStopsAndForgetsTheFolder() {
        val scope = scope()
        try {
            var cfg = DesktopConfig(vaultFolder = null)
            val service = VaultService(
                scope,
                config = { cfg },
                rememberFolder = { cfg = cfg.copy(vaultFolder = it) },
                rememberSpaceId = { cfg = cfg.copy(vaultSpaceId = it) },
                openCustody = { VaultCustody.Result(opened("s"), minted = true) },
                engineFor = { _, _ -> FakeSync() },
                watchFor = { PeriodicOnlyChanges },
                retryMs = 50,
            )
            service.enable("/tmp/vault")
            awaitUntil { service.phase == VaultPhase.Running }

            service.disable()
            assertEquals(VaultPhase.Off, service.phase)
            assertTrue(cfg.vaultFolder == null, "folder should be forgotten on disable")
            assertTrue(!service.controller.isRunning)
        } finally {
            scope.cancel()
        }
    }
}
