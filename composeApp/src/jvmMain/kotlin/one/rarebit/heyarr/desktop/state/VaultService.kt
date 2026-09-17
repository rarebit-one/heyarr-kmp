package one.rarebit.heyarr.desktop.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.desktop.settings.DesktopConfig
import one.rarebit.heyarr.desktop.vault.OpenedVault
import one.rarebit.heyarr.desktop.vault.PeriodicOnlyChanges
import one.rarebit.heyarr.desktop.vault.SyncChanges
import one.rarebit.heyarr.desktop.vault.VaultCustody
import one.rarebit.heyarr.desktop.vault.VaultSync

/**
 * Getting-ready state, distinct from the running loop's per-pass [SyncStatus]: before the daemon
 * can run, custody has to resolve (this device enrolled, the node reachable, the space opened or
 * minted). [SyncStatus] then reports each pass once [VaultPhase.Running].
 */
sealed interface VaultPhase {
    /** Sync is off (no folder configured). */
    data object Off : VaultPhase

    /** A folder is configured but custody isn't ready yet — [reason] is the last obstacle (e.g. "not enrolled"). */
    data class Preparing(val reason: String) : VaultPhase

    /** Custody resolved; the [VaultSyncController] is driving the sync. */
    data object Running : VaultPhase
}

/**
 * Owns the desktop vault sync for the session: it RESOLVES custody (open the configured space, or
 * self-bootstrap one) off the UI thread — retrying while the device isn't enrolled or the node is
 * unreachable — then hands the built engine + folder watch to a [VaultSyncController] and starts it.
 * The controller (already unit-proven) does the running; this class is the async "get ready and
 * wire it up" layer, and the single thing the settings screen and app lifecycle talk to.
 *
 * Every dependency that touches the network, disk or device keys is injected as a seam
 * ([openCustody], [engineFor], [watchFor]), so the resolve/retry/phase logic is unit-tested with
 * fakes. [one.rarebit.heyarr.desktop.state.AppSession] wires the real implementations.
 */
class VaultService(
    private val scope: CoroutineScope,
    private val config: () -> DesktopConfig,
    private val rememberFolder: (String?) -> Unit,
    private val rememberSpaceId: (String) -> Unit,
    /** Open the configured space or self-bootstrap one (network + device keys). */
    private val openCustody: (configuredSpaceId: String?) -> VaultCustody.Result,
    /** Build the sync engine for an opened vault + folder. */
    private val engineFor: (OpenedVault, folder: String) -> VaultSync,
    /** Build the folder change source (falls back to periodic when it throws). */
    private val watchFor: (folder: String) -> SyncChanges,
    private val retryMs: Long = 15_000,
) {
    @Volatile private var engine: VaultSync? = null
    @Volatile private var watch: SyncChanges? = null
    private var resolveJob: Job? = null

    /** The running loop — starts once custody resolves. Its status/lastStats feed the settings UI. */
    val controller = VaultSyncController(scope, engine = { engine }, changes = { watch })

    var phase: VaultPhase by mutableStateOf(VaultPhase.Off)
        private set

    /** True when a folder is configured — sync is meant to be on. */
    val enabled: Boolean get() = !config().vaultFolder.isNullOrBlank()

    /** Turn sync on for [folder] (persisted) and begin resolving custody. */
    fun enable(folder: String) {
        rememberFolder(folder)
        begin()
    }

    /** Resume a previously-configured folder on launch; a no-op when none is set. */
    fun resume() {
        if (enabled) begin() else phase = VaultPhase.Off
    }

    /** Turn sync off: stop the loop and forget the folder. */
    fun disable() {
        resolveJob?.cancel()
        controller.stop()
        engine = null
        watch = null
        rememberFolder(null)
        phase = VaultPhase.Off
    }

    /** App teardown: stop the loop and close the watch, but keep the config so [resume] works next launch. */
    fun shutdown() {
        resolveJob?.cancel()
        controller.stop()
        engine = null
        watch = null
    }

    private fun begin() {
        resolveJob?.cancel()
        engine = null
        watch = null
        phase = VaultPhase.Preparing("starting")
        resolveJob = scope.launch {
            while (isActive) {
                val folder = config().vaultFolder
                if (folder.isNullOrBlank()) { phase = VaultPhase.Off; return@launch }
                val resolved = withContext(Dispatchers.IO) {
                    runCatching {
                        val result = openCustody(config().vaultSpaceId)
                        val opened = result.opened ?: error(result.error ?: "vault not ready")
                        if (result.minted) rememberSpaceId(opened.spaceId)
                        engine = engineFor(opened, folder)
                        watch = runCatching { watchFor(folder) }.getOrDefault(PeriodicOnlyChanges)
                    }
                }
                resolved
                    .onSuccess { phase = VaultPhase.Running; controller.start(); return@launch }
                    .onFailure { phase = VaultPhase.Preparing(it.message ?: "not ready") }
                delay(retryMs)
            }
        }
    }
}
