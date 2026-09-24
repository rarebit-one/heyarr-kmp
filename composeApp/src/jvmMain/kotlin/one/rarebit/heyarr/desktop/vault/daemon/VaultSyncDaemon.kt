package one.rarebit.heyarr.desktop.vault.daemon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.core.mcp.JsonWrite
import one.rarebit.heyarr.core.net.JsonScan
import one.rarebit.heyarr.desktop.state.SyncStatus
import one.rarebit.heyarr.desktop.state.VaultSyncController
import one.rarebit.heyarr.desktop.vault.SyncChanges
import one.rarebit.heyarr.desktop.vault.VaultSync
import one.rarebit.heyarr.desktop.vault.VaultSyncEngine

/**
 * The HEADLESS vault-sync daemon (W4, JVM box) — a Dropbox-style background sync process with **no
 * Compose GUI**. It owns the same already-merged [VaultSyncController] the desktop app drives, but
 * exposes it over a machine contract instead of a settings screen: an atomically-written status
 * file and a unix control socket ([StatusSnapshot], [ControlSocket]), both at the exact paths the
 * merged homelab-ops MCP + Omarchy plugin consumes.
 *
 * Everything that touches custody, crypto, HTTP or disk is behind the injected [resolve] seam
 * ([Resolved]), so the whole lifecycle — resolve/retry, the running loop, the status file, the
 * socket protocol, pause/resume — is unit-tested with a fake engine and no real node. PR2 wires the
 * real Go-store custody + credential shim + engine into [resolve]; this class never changes.
 *
 * Lifecycle mirrors [one.rarebit.heyarr.desktop.state.VaultService]: [start] brings the socket + the
 * status poller up immediately (so the plugin can talk to a `preparing` daemon), then a resolve loop
 * retries custody until it succeeds and hands the built engine + folder watch to the controller.
 */
class VaultSyncDaemon(
    private val config: DaemonConfig,
    private val scope: CoroutineScope,
    private val resolve: () -> Resolved,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : AutoCloseable {

    /** What custody resolution produces: a ready engine, its folder watch, this device, and whether the watch is real. */
    class Resolved(
        val engine: VaultSync,
        val changes: SyncChanges,
        /** `ed25519:…` — this device's signing key, for the status file's `device` field. */
        val device: String,
        /** True when [changes] is a real filesystem watch; false when it degraded to periodic-only. */
        val watching: Boolean,
    )

    @Volatile private var recorder: RecordingSync? = null

    @Volatile private var watch: SyncChanges? = null

    @Volatile private var device: String = ""

    @Volatile private var watching: Boolean = false

    @Volatile private var paused: Boolean = false

    @Volatile private var resolveError: String? = null

    @Volatile private var started: Boolean = false

    // The engine seam gates on [paused]: a paused daemon feeds the controller a null engine, so its
    // loop parks harmlessly instead of syncing. The daemon's own reported phase overrides to "paused".
    private val controller = VaultSyncController(
        scope,
        engine = { if (paused) null else recorder },
        changes = { watch },
        periodMs = config.pollMs,
    )

    private var resolveJob: Job? = null
    private var statusJob: Job? = null
    private var socket: ControlSocket? = null

    /** Bring the control socket + status writer up, then begin resolving custody. */
    fun start() {
        started = true
        writeStatus()
        socket = ControlSocket(config.socketPath, scope, ::handle).also { it.start() }
        // The status file is re-emitted on a steady tick so "on every state change" holds even for
        // changes with no explicit hook (the controller's Compose state), plus on demand elsewhere.
        statusJob = scope.launch {
            while (isActive) {
                writeStatus()
                delay(1_000)
            }
        }
        beginResolve()
    }

    private fun beginResolve() {
        resolveJob?.cancel()
        recorder = null
        watch = null
        watching = false
        if (config.folder.isNullOrBlank()) {
            // Nothing to sync yet — sit in `preparing` and say so, never guess a folder.
            resolveError = "no folder configured"
            writeStatus()
            return
        }
        resolveError = null
        resolveJob = scope.launch {
            while (isActive) {
                val r = withContext(Dispatchers.IO) { runCatching { resolve() } }
                r.onSuccess { res ->
                    recorder = RecordingSync(res.engine, clock)
                    watch = res.changes
                    device = res.device
                    watching = res.watching
                    resolveError = null
                    controller.start()
                    writeStatus()
                    return@launch
                }.onFailure {
                    resolveError = it.message ?: "vault not ready"
                    writeStatus()
                }
                delay(config.retryMs)
            }
        }
    }

    // ── control protocol ─────────────────────────────────────────────────────────

    /** One control request → one JSON response. Unknown/malformed → `{"ok":false,"error":…}`. */
    fun handle(request: String): String {
        val cmd = runCatching { JsonScan.stringField(request, "cmd") }.getOrNull()
            ?: return err("malformed request")
        return when (cmd) {
            "status" -> statusSnapshot().toJson()

            "sync-now" -> {
                controller.syncNow()
                JsonWrite.obj(linkedMapOf("ok" to true))
            }

            "pause" -> {
                paused = true
                writeStatus()
                JsonWrite.obj(linkedMapOf("ok" to true, "phase" to "paused"))
            }

            "resume" -> {
                paused = false
                controller.syncNow()
                writeStatus()
                JsonWrite.obj(linkedMapOf("ok" to true, "phase" to "running"))
            }

            else -> err("unknown cmd: $cmd")
        }
    }

    private fun err(message: String) = JsonWrite.obj(linkedMapOf("ok" to false, "error" to message))

    // ── status ───────────────────────────────────────────────────────────────────

    /** The live status object, as written to the status file and returned to a `status` command. */
    fun statusSnapshot(): StatusSnapshot {
        val rec = recorder
        val stats = rec?.lastStats
        return StatusSnapshot(
            phase = phase(),
            folder = config.folder ?: "",
            spaceId = config.spaceId,
            controller = config.controller,
            device = device,
            watching = watching,
            lastSyncAtMs = rec?.lastAtMs,
            lastPass = stats?.let {
                PassStats(
                    pushed = it.uploaded,
                    pulled = it.downloaded,
                    // The engine writes conflicted copies to disk but its Stats do not count them
                    // yet, so `conflicts` is reported as 0 / [] until the engine exposes them.
                    conflicts = 0,
                    deleted = it.deletedRemote + it.deletedLocal,
                    ms = rec.lastMs,
                )
            },
            conflicts = emptyList(),
            lastError = controller.lastError ?: resolveError,
            nowMs = clock(),
        )
    }

    private fun phase(): String = when {
        !started -> "off"
        paused -> "paused"
        recorder == null -> "preparing"
        controller.status is SyncStatus.Error -> "error"
        else -> "running"
    }

    private fun writeStatus() {
        runCatching { StatusSnapshot.writeAtomically(config.statusFile, statusSnapshot().toJson()) }
    }

    override fun close() {
        resolveJob?.cancel()
        statusJob?.cancel()
        controller.stop()
        runCatching { socket?.close() }
        recorder = null
        watch = null
    }
}

/**
 * A [VaultSync] decorator that records the last pass's stats, duration and end-time so the daemon
 * can report `last_pass.ms` / `last_sync_at` — data the [VaultSyncController]'s Compose state does
 * not carry (it has no per-pass duration). Wrapping is transparent: it delegates and re-throws (so a
 * failing pass never records a bogus success), and the controller keeps owning error state.
 */
private class RecordingSync(
    private val delegate: VaultSync,
    private val clock: () -> Long,
) : VaultSync {
    @Volatile var lastStats: VaultSyncEngine.Stats? = null

    @Volatile var lastMs: Long = 0

    @Volatile var lastAtMs: Long? = null

    override fun syncOnce(): VaultSyncEngine.Stats {
        val t0 = clock()
        val stats = delegate.syncOnce()
        lastMs = clock() - t0
        lastAtMs = clock()
        lastStats = stats
        return stats
    }
}
