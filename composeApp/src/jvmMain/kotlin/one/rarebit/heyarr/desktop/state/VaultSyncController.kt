package one.rarebit.heyarr.desktop.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.rarebit.heyarr.desktop.vault.PeriodicOnlyChanges
import one.rarebit.heyarr.desktop.vault.SyncChanges
import one.rarebit.heyarr.desktop.vault.VaultSync
import one.rarebit.heyarr.desktop.vault.VaultSyncEngine

/** What the vault daemon is doing right now — drives the settings screen's status line. */
sealed interface SyncStatus {
    /** Not running. */
    data object Off : SyncStatus

    /** Running, but not configured/enrolled yet — nothing to sync until custody is ready. */
    data object Waiting : SyncStatus

    /** Running and parked, waiting for the next local change or the periodic tick. */
    data object Idle : SyncStatus

    /** A reconcile-and-apply pass is in flight. */
    data object Syncing : SyncStatus

    /** The last pass failed; the loop keeps running and retries on the next tick. */
    data class Error(val message: String) : SyncStatus
}

/**
 * The desktop vault sync daemon: one session-scoped loop that drives [VaultSyncEngine.syncOnce]
 * on the designated folder. It is the piece that makes the (already unit-proven) engine actually
 * run — the mold is [AppSession.startHeartbeat]: a single cancellable [Job] on the session scope,
 * launched by [start] and torn down by [stop] (the desktop entry point cancels the scope on
 * teardown, so the loop dies with the app).
 *
 * Each iteration runs one pass off [Dispatchers.IO] via [runInterruptible] (so a cancel
 * interrupts a blocking upload/download), then parks on [SyncChanges.awaitChange] until a local
 * edit lands OR the [periodMs] safety-net tick fires — whichever comes first. A pass that throws
 * (offline, a refused credential, a mid-sync disconnect) becomes [SyncStatus.Error] and the loop
 * keeps going; it never dies on one bad pass.
 *
 * Everything the loop needs is injected as a seam, so it is unit-tested with a fake pass and a
 * fake [SyncChanges] and touches no crypto, HTTP or disk:
 *  - [engine] returns the configured engine, or null while custody/config are not ready yet
 *    (first run before the space key is unwrapped) — the loop reports [SyncStatus.Waiting] and
 *    retries, rather than spinning on a half-built engine.
 *  - [changes] builds the change source for the current folder (a [one.rarebit.heyarr.desktop.vault.WatchedFolder],
 *    or [PeriodicOnlyChanges] when the folder cannot be watched); null → periodic-only.
 */
class VaultSyncController(
    private val scope: CoroutineScope,
    private val engine: () -> VaultSync?,
    private val changes: () -> SyncChanges? = { null },
    private val periodMs: Long = 30_000,
    /** Debounce after a change wakes us, so a burst of edits settles into one pass. */
    private val settleMs: Long = 800,
) {
    var status: SyncStatus by mutableStateOf(SyncStatus.Off)
        private set
    var lastStats: VaultSyncEngine.Stats? by mutableStateOf(null)
        private set
    var lastError: String? by mutableStateOf(null)
        private set
    var lastSyncAtMs: Long? by mutableStateOf(null)
        private set

    private var job: Job? = null

    // Serialises passes: the loop tick and a manual [syncNow] never run syncOnce concurrently
    // (it mutates the on-disk index — one at a time, or the index races itself).
    private val passLock = Mutex()

    val isRunning: Boolean get() = job?.isActive == true

    /** Start (or restart) the daemon loop. Idempotent restart: cancels any prior loop first. */
    fun start() {
        job?.cancel()
        job = scope.launch {
            val watch = runCatching { changes() }.getOrNull() ?: PeriodicOnlyChanges
            status = SyncStatus.Idle
            try {
                while (isActive) {
                    runPass()
                    val woke = runCatching { watch.awaitChange(periodMs) }.getOrDefault(false)
                    if (woke) delay(settleMs)
                }
            } finally {
                runCatching { watch.close() }
                status = SyncStatus.Off
            }
        }
    }

    /** Stop the loop. The next [start] begins a fresh one. */
    fun stop() {
        job?.cancel()
        job = null
        status = SyncStatus.Off
    }

    /** Wake the loop for an immediate pass (e.g. a manual "Sync now" button) without waiting for the tick. */
    fun syncNow() {
        if (!isRunning) { start(); return }
        scope.launch { runPass() }
    }

    private suspend fun runPass() = passLock.withLock {
        val e = engine()
        if (e == null) {
            // Not configured/enrolled yet — say so and let the loop retry on the next tick.
            if (status !is SyncStatus.Error) status = SyncStatus.Waiting
            return@withLock
        }
        status = SyncStatus.Syncing
        try {
            val stats = runInterruptible(Dispatchers.IO) { e.syncOnce() }
            lastStats = stats
            lastSyncAtMs = System.currentTimeMillis()
            lastError = null
            status = SyncStatus.Idle
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            val msg = t.message ?: t::class.simpleName ?: "sync failed"
            lastError = msg
            status = SyncStatus.Error(msg)
        }
    }
}
