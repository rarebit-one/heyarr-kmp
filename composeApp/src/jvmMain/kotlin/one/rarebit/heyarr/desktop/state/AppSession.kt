package one.rarebit.heyarr.desktop.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import one.rarebit.heyarr.core.auth.ClientMode
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.auth.mode
import one.rarebit.heyarr.core.discovery.DiscoveredServer
import one.rarebit.heyarr.core.discovery.MdnsResolver
import one.rarebit.heyarr.core.discovery.NoMdnsResolver
import one.rarebit.heyarr.core.discovery.NodeDiscovery
import one.rarebit.heyarr.core.heyarr.QualityProfile
import one.rarebit.heyarr.core.mcp.McpRefusedException
import one.rarebit.heyarr.core.mcp.McpTransportException
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.core.state.*
import one.rarebit.heyarr.desktop.device.DesktopDeviceEnroller
import one.rarebit.heyarr.desktop.device.DesktopDeviceKeyring
import one.rarebit.heyarr.desktop.device.DevicePairingSteps
import one.rarebit.heyarr.desktop.device.PairingCoordinator
import one.rarebit.heyarr.desktop.heyarr.HeyarrApi
import one.rarebit.heyarr.desktop.heyarr.McpResult
import one.rarebit.heyarr.desktop.login.BearerTokenLogin
import one.rarebit.heyarr.desktop.login.DeviceEnroller
import one.rarebit.heyarr.desktop.login.DeviceLogin
import one.rarebit.heyarr.desktop.login.EnrolUpgrade
import one.rarebit.heyarr.desktop.open.OpenExternally
import one.rarebit.heyarr.desktop.playback.Player
import one.rarebit.heyarr.desktop.settings.DesktopConfig
import one.rarebit.heyarr.desktop.settings.SettingsStore
import one.rarebit.heyarr.desktop.vault.FileSyncIndexStore
import one.rarebit.heyarr.desktop.vault.JdkVaultBlobStore
import one.rarebit.heyarr.desktop.vault.RealVaultFolder
import one.rarebit.heyarr.desktop.vault.VaultCustody
import one.rarebit.heyarr.desktop.vault.VaultSpaceClient
import one.rarebit.heyarr.desktop.vault.VaultSyncEngine
import one.rarebit.heyarr.desktop.vault.WatchedFolder
import one.rarebit.heyarr.ui.theme.Appearance

/** Whether heyarr can be reached right now — drives the offline banner. */
enum class Connection {
    UNKNOWN,
    ONLINE,
    OFFLINE,
    UNAUTHORIZED,
    UNCONFIGURED,
    ;

    companion object {
        /** What one liveness probe says: its HTTP status, or 0 when the transport failed. A refused credential is not "offline". */
        fun fromProbe(status: Int): Connection = when (status) {
            200 -> ONLINE
            401, 403 -> UNAUTHORIZED
            else -> OFFLINE
        }
    }
}

/**
 * App-wide state every screen shares: the saved connection, the [HeyarrApi] built from
 * it, connectivity, the library-status index, quality profiles, and the toast queue.
 * Plain Compose state (the org's stance for this app; a ViewModel layer comes with the
 * shared module). Network work is launched on [Dispatchers.IO] through [io].
 */

/** How long one liveness probe may take before it counts as no answer. */
private const val PROBE_TIMEOUT_MS = 6_000L

class AppSession(
    private val settings: SettingsStore,
    private val transport: HttpTransport,
    val player: Player,
    val openExternally: OpenExternally,
    private val scope: CoroutineScope,
    artworkLoader: ArtworkLoader? = null,
    externalMetadata: ExternalMetadata? = null,
    /** LAN mDNS browser for auto-discovery; the no-op default keeps previews/tests network-free. */
    mdns: MdnsResolver = NoMdnsResolver,
    /**
     * Turn on the REAL desktop device-enrol stack (a filesystem-backed [DesktopDeviceKeyring],
     * its [PairingCoordinator] and the device-credential enroller). Off by default so previews
     * and tests stay guest and touch no disk / no device key store; the desktop entry point
     * ([one.rarebit.heyarr.desktop.MainKt]) turns it on.
     */
    enableDeviceEnrol: Boolean = false,
) {
    /** This desktop's device keys (sealed signing seed + enc key + admission), or null in previews/tests. */
    val deviceKeyring: DesktopDeviceKeyring? = if (enableDeviceEnrol) DesktopDeviceKeyring() else null

    /** The device-credential enrol upgrade — real when enrolment is enabled, else inert. */
    private val enroller: DeviceEnroller =
        deviceKeyring?.let { DesktopDeviceEnroller(it) } ?: DeviceEnroller.NotEnrolled

    /** The pairing coordinator that drives "Sign in to save"; null when enrolment is disabled. */
    val pairing: PairingCoordinator? = deviceKeyring?.let { ring ->
        PairingCoordinator(
            scope,
            steps = {
                DevicePairingSteps(
                    keyring = ring,
                    nodeTransport = transport,
                    baseUrl = { config.baseUrl },
                    deviceName = { defaultDeviceName() },
                    // The register lane's fallback credential: a pasted bearer if there is one,
                    // never the device credential we are in the middle of enrolling.
                    credential = { BearerTokenLogin { config.bearerToken }.credential() },
                )
            },
        )
    }
    var config: DesktopConfig by mutableStateOf(settings.load())
        private set

    var appearance: Appearance by mutableStateOf(Appearance())

    // The "Sign in to save" enrol upgrade over the guest default: a device credential wins,
    // else a pasted bearer token, else [Credential.Guest] (no header → trusted-network guest).
    private val enrol = EnrolUpgrade(
        device = DeviceLogin(enroller),
        bearer = BearerTokenLogin { config.bearerToken },
    )

    /** The credential the client presents right now — never null; guest when nothing is enrolled. */
    val credential: Credential get() = enrol.credential()

    /** GUEST (anonymous lease) vs ENROLLED (a real credential) — drives all UI gating. */
    val mode: ClientMode get() = credential.mode()

    /** True while the client is browsing as an anonymous guest (no login). */
    val isGuest: Boolean get() = mode == ClientMode.GUEST

    /** The auto-discovery fallback chain (mDNS → DNS name → manual), off `Dispatchers.IO`. */
    private val discovery = NodeDiscovery(mdns)

    /** Resolve the server to default the connection field to, using the current saved URL as the manual fallback. */
    suspend fun discoverServer(): DiscoveredServer = withContext(Dispatchers.IO) { discovery.discover(config.baseUrl) }

    // A configured base URL always yields an API — as a guest when there is no credential —
    // so the desktop browses on a trusted network before any login.
    val api: HeyarrApi? get() = config.baseUrl.trim().takeIf { it.isNotEmpty() }?.let {
        HeyarrApi(transport, config.baseUrl, credential)
    }

    /** App-wide playback: one mpv for the session, surface owned by the shell. */
    val playback = PlaybackSession()

    val artwork: ArtworkLoader = artworkLoader ?: ArtworkLoader({ config.baseUrl }, { config.bearerToken.trim() })
    val external: ExternalMetadata = externalMetadata ?: DesktopExternalMetadata.create(enabled = { config.externalMetadata }, movieLookup = { key ->
        val hits = api?.discover(key.title)?.getOrNull().orEmpty()
        one.rarebit.heyarr.desktop.state.MovieArtwork.select(key, hits)
    })
    val recent = RecentSearches(RecentSearches.defaultFile())

    /**
     * The desktop vault sync service (W4): resolves space-key custody and drives
     * [one.rarebit.heyarr.desktop.state.VaultSyncController] over the designated folder. Present
     * only with the real device stack (it needs the [deviceKeyring] to unwrap the space key);
     * null in previews/tests, which stay guest and touch no device key store.
     */
    val vault: VaultService? = deviceKeyring?.let { ring ->
        VaultService(
            scope = scope,
            config = { config },
            rememberFolder = ::setVaultFolder,
            rememberSpaceId = ::setVaultSpaceId,
            openCustody = { spaceId ->
                VaultCustody(ring, VaultSpaceClient(transport, config.baseUrl, credential)).openOrBootstrap(spaceId)
            },
            engineFor = { opened, folder ->
                VaultSyncEngine(
                    folder = RealVaultFolder(java.nio.file.Path.of(folder)),
                    blobs = JdkVaultBlobStore(),
                    space = VaultSpaceClient(transport, config.baseUrl, credential),
                    indexStore = FileSyncIndexStore(),
                    baseUrl = config.baseUrl,
                    credential = credential,
                    spaceId = opened.spaceId,
                    spaceKey = opened.spaceKey,
                )
            },
            watchFor = { folder -> WatchedFolder(java.nio.file.Path.of(folder)) },
        )
    }

    var connection: Connection by mutableStateOf(if (config.baseUrl.isBlank()) Connection.UNCONFIGURED else Connection.UNKNOWN)
        private set
    var lastLatencyMs: Long? by mutableStateOf(null)
        private set
    var lastOkAt: Long? by mutableStateOf(null)
        private set
    var lastFailure: String? by mutableStateOf(null)
        private set
    var probes: Int by mutableStateOf(0)
        private set
    var failures: Int by mutableStateOf(0)
        private set

    var index: LibraryIndex by mutableStateOf(LibraryIndex.EMPTY)
        private set
    var indexLoading: Boolean by mutableStateOf(false)
        private set

    var profiles: List<QualityProfile> by mutableStateOf(emptyList())
        private set

    val toasts = mutableStateListOf<Toast>()
    private var toastSeq = 0L
    private var heartbeat: Job? = null

    // ── config ───────────────────────────────────────────────────────────────────

    /** Bumped when the node (URL or token) changes; screens that cache a node's answers reload on it. */
    var generation: Int by mutableStateOf(0)
        private set

    fun catalogChanged() {
        generation++
        refreshIndex()
    }

    fun save(updated: DesktopConfig) {
        settings.save(updated)
        val otherNode = updated.baseUrl != config.baseUrl || updated.bearerToken != config.bearerToken
        config = updated
        artwork.reset()
        connection = if (updated.baseUrl.isBlank()) Connection.UNCONFIGURED else Connection.UNKNOWN
        if (otherNode) {
            profiles = emptyList()
            index = LibraryIndex.EMPTY
            generation++
        }
        refreshIndex()
        startHeartbeat()
    }

    /**
     * Persist the vault folder mapping (W4) without the heavyweight [save] side effects — the
     * library index and connection are unaffected by which folder syncs. Null forgets it.
     */
    fun setVaultFolder(folder: String?) {
        config = config.copy(vaultFolder = folder?.takeIf { it.isNotBlank() })
        settings.save(config)
    }

    /** Persist the minted vault space id (W4) so later launches open it rather than mint again. */
    fun setVaultSpaceId(id: String?) {
        config = config.copy(vaultSpaceId = id?.takeIf { it.isNotBlank() })
        settings.save(config)
    }

    /**
     * The presented credential changed out-of-band (a device enrolment just completed, or
     * was forgotten) — the client was a guest and is now enrolled, or vice-versa. Rebuild
     * the session as [save] does for a node change: drop the owner-only caches, bump the
     * generation so screens reload, and re-probe. The credential itself is recomputed
     * live by [credential]; this only refreshes what depends on being enrolled.
     */
    fun reauthenticated() {
        profiles = emptyList()
        index = LibraryIndex.EMPTY
        generation++
        connection = if (config.baseUrl.isBlank()) Connection.UNCONFIGURED else Connection.UNKNOWN
        refreshIndex()
        startHeartbeat()
    }

    /** The name this desktop presents at enrolment (`POST /enrol` `name`) — hostname, else the OS user. */
    private fun defaultDeviceName(): String {
        val host = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
        val user = System.getProperty("user.name")?.takeIf { it.isNotBlank() }
        return host ?: user?.let { "$it's desktop" } ?: "heyarr desktop"
    }

    // ── connectivity ─────────────────────────────────────────────────────────────

    fun startHeartbeat() {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (isActive) {
                probe()
                delay(if (connection == Connection.ONLINE) 30_000 else 8_000)
            }
        }
    }

    /**
     * One liveness probe. A transport failure is answered by dropping the transport's
     * connection pool and dialling once more before the node is called unreachable:
     * after the machine changes network the pool holds connections to a network that
     * is gone, and only a fresh dial can tell whether the node itself answers. Each
     * attempt is bounded, so a dead pool costs seconds, not the request timeout.
     */
    suspend fun probe() {
        val a = api
        if (a == null) {
            connection = Connection.UNCONFIGURED
            return
        }
        val t0 = System.nanoTime()
        var status = attempt(a)
        if (status == 0) {
            transport.reset()
            status = attempt(a)
        }
        probes++
        lastLatencyMs = (System.nanoTime() - t0) / 1_000_000
        connection = Connection.fromProbe(status)
        when (status) {
            200 -> {
                lastOkAt = System.currentTimeMillis()
                lastFailure = null
            }

            0 -> failures++

            else -> {
                failures++
                lastFailure = "HTTP $status from the node"
            }
        }
    }

    // A cancelled probe (the heartbeat restarting on a new configuration) is not a failure
    // and must not leave its message behind for the probe that replaced it: cancellation
    // propagates, everything else is the transport's answer.
    private suspend fun attempt(a: HeyarrApi): Int = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
        try {
            runInterruptible(Dispatchers.IO) { a.ping() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastFailure = e.message ?: e.javaClass.simpleName
            0
        }
    } ?: run {
        lastFailure = "no answer within ${PROBE_TIMEOUT_MS / 1000} s"
        0
    }

    /**
     * Called by any screen whose call died on the transport — flips the banner
     * immediately. A failure with no HTTP status is the transport itself, so its
     * pool is dropped too: the next call, and the next heartbeat, dial fresh.
     */
    fun noteTransportFailure(e: McpTransportException) {
        failures++
        lastFailure = e.message
        if (e.status == null) transport.reset()
        connection = if (e.status == 401 || e.status == 403) Connection.UNAUTHORIZED else Connection.OFFLINE
    }

    fun noteSuccess() {
        lastOkAt = System.currentTimeMillis()
        if (connection != Connection.ONLINE) connection = Connection.ONLINE
    }

    // ── library index + profiles ─────────────────────────────────────────────────

    fun refreshIndex() {
        // The want index (In library / Wanted / Missing) is owner state a guest cannot read;
        // asking for it would only earn a 403. Guests browse without it.
        if (isGuest) {
            index = LibraryIndex.EMPTY
            profiles = emptyList()
            return
        }
        val a = api ?: run {
            index = LibraryIndex.EMPTY
            return
        }
        scope.launch {
            indexLoading = true
            val result = io { a.desired() }
            indexLoading = false
            result.onSuccess { index = LibraryIndex(it) }
        }
        if (profiles.isEmpty()) scope.launch { io { a.qualityProfiles() }.onSuccess { profiles = it } }
    }

    /** Optimistic want: the row flips to Wanted at once and rolls back with the refusal on failure. */
    fun want(workId: String, title: String, profile: String, monitor: Boolean = true, reason: String? = null, onDone: (McpResult<*>?) -> Unit = {}) {
        // Wanting is an enrolled surface; the UI routes a guest to "Sign in to save" first,
        // but guard here too so a stray call never fires an unauthenticated write at the node.
        if (isGuest) {
            onDone(null)
            return
        }
        val a = api ?: return
        val before = index
        index = index.withPendingWant(workId, profiles.firstOrNull { it.name == profile }?.id)
        scope.launch {
            val result = io { a.wantWork(workId, profile, monitor, reason) }
            result.onFailure {
                index = before
                onDone(null)
            }
            result.onSuccess { r ->
                when (r) {
                    is McpResult.Ok -> {
                        toast(Toast.Kind.SUCCESS, "Wanted “$title”", "Measured against the $profile profile.")
                        refreshIndex()
                    }

                    is McpResult.Refused -> {
                        index = before
                        refused(r)
                    }
                }
                onDone(r)
            }
        }
    }

    // ── errors → toasts ──────────────────────────────────────────────────────────

    /** Run [block] on IO, turning a transport failure into the offline banner + an error toast. */
    suspend fun <T> io(block: () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching(block) }.also { r ->
        r.onSuccess { noteSuccess() }
        r.onFailure { e ->
            when {
                // A guest reaching an enrolled-only endpoint gets a 403 — that is expected, not
                // a broken connection. Swallow it quietly: browse/play still work, so the node
                // stays "online" and the caller just sees an empty result. The UI hides those
                // surfaces for guests anyway; this guards the ones that slip through.
                isGuest && e is McpTransportException && (e.status == 401 || e.status == 403) -> {}

                e is McpTransportException -> {
                    noteTransportFailure(e)
                    toast(Toast.Kind.ERROR, "Can't reach heyarr", e.message)
                }

                e is McpRefusedException -> toast(Toast.Kind.REFUSED, "heyarr refused", e.error.message, e.error.tool)

                else -> toast(Toast.Kind.ERROR, "Something went wrong", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun refused(r: McpResult.Refused) = toast(Toast.Kind.REFUSED, "Refused by ${r.tool}", r.message, r.tool)

    /**
     * A cast refusal, with an escape hatch. The client CANNOT reliably tell WHY a cast was
     * refused: the MCP layer masks a tool's reason as a generic "the tool failed" (it does not
     * leak internal detail), and the REST fallback can report a downstream error ("no renderer
     * answered") rather than the plan's codec verdict. So offer "Cast anyway" on any cast
     * refusal — [retry] re-casts with force_direct, which bypasses the codec plan-gate for a
     * device that decodes more than it declares (a TV that under-declares DD+ over DLNA). If the
     * device is genuinely off or truly cannot decode, the forced attempt simply refuses again —
     * no loop, since the retry path shows the plain refusal.
     */
    fun castRefused(r: McpResult.Refused, deviceName: String, retry: () -> Unit) {
        toast(
            Toast.Kind.REFUSED,
            "Couldn't cast to $deviceName",
            r.message,
            r.tool,
            action = ToastAction("Cast anyway") { retry() },
        )
    }

    fun toast(kind: Toast.Kind, title: String, detail: String? = null, tool: String? = null, action: ToastAction? = null) {
        val t = Toast(++toastSeq, kind, title, detail, tool, action)
        toasts.add(t)
        scope.launch {
            delay(if (kind == Toast.Kind.REFUSED || kind == Toast.Kind.ERROR) 9_000 else 4_500)
            toasts.remove(t)
        }
    }

    fun dismiss(toast: Toast) {
        toasts.remove(toast)
    }

    /**
     * Launch [block] on the session's own scope, so it outlives the screen or menu that
     * started it. A cast fired from a picker that closes on click must NOT be cancelled
     * when that picker leaves composition — a screen-local rememberCoroutineScope would
     * cancel it (and its toast) mid-flight, which is exactly the "nothing happened" bug.
     */
    fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(block = block)
}
