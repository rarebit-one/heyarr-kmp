package one.rarebit.heyarr.mobile.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.core.auth.ClientMode
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.auth.ProbeVerdict
import one.rarebit.heyarr.core.auth.mode
import one.rarebit.heyarr.core.heyarr.QualityProfile
import one.rarebit.heyarr.core.heyarr.QualityProfiles
import one.rarebit.heyarr.core.mcp.McpError
import one.rarebit.heyarr.core.mcp.McpRefusedException
import one.rarebit.heyarr.core.mcp.McpTransportException
import one.rarebit.heyarr.core.state.Availability
import one.rarebit.heyarr.core.state.DiscoveryAnswer
import one.rarebit.heyarr.core.state.DiscoveryBackend
import one.rarebit.heyarr.core.state.DiscoveryController
import one.rarebit.heyarr.core.state.ExternalMetadata
import one.rarebit.heyarr.core.state.LibraryIndex
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.heyarr.HeyarrApi
import one.rarebit.heyarr.mobile.heyarr.McpResult
import one.rarebit.heyarr.mobile.settings.SettingsStore
import one.rarebit.heyarr.ui.theme.Appearance
import one.rarebit.voidwhichbinds.AuthenticationRequiredException

/** Whether heyarr can be reached right now — drives the offline banner. */
enum class Connection {
    UNKNOWN,
    ONLINE,
    OFFLINE,
    UNAUTHORIZED,

    /**
     * The node refused a request that carried NO credential: it does not admit this phone
     * as a guest from where it is (its source is outside the node's guest trusted networks,
     * or guest access is off). Nothing was presented, so nothing is blamed — the honest
     * next step is to sign in (heyarr-kmp#185).
     */
    GUEST_REFUSED,

    /**
     * The device signing key's post-authentication window has lapsed (see `BiometricGate`),
     * so signing refused before any request left the phone. The user re-authenticates
     * locally (biometric / PIN) and retries. This is not a network problem.
     */
    SIGNING_FAILED,
    ;

    companion object {
        /**
         * Maps one liveness probe's HTTP status, sent in [mode], to a [Connection] state.
         * [status] is the HTTP status, or 0 when the transport could not connect at all.
         * A refused credential is not "offline", and a refused guest is not a refused
         * credential: the rule is `:core`'s [ProbeVerdict], shared with the desktop.
         */
        fun fromProbe(status: Int, mode: ClientMode): Connection = when (ProbeVerdict.of(status, mode)) {
            ProbeVerdict.ONLINE -> ONLINE
            ProbeVerdict.OFFLINE -> OFFLINE
            ProbeVerdict.CREDENTIAL_REFUSED -> UNAUTHORIZED
            ProbeVerdict.GUEST_REFUSED -> GUEST_REFUSED
        }
    }
}

/**
 * App-wide state every signed-in screen shares — ported from heyarr-desktop's
 * `state/AppSession.kt`: the [HeyarrApi] for this node + credential, connectivity, the
 * library-status index, quality profiles, appearance preferences and the toast queue.
 * Plain Compose state held inside a ViewModel keyed on the node + credential shape
 * (see `nav/SessionHolder`), so it survives rotation and is rebuilt on a sign-in,
 * an enrolment or a Settings change. Network work runs on [Dispatchers.IO] via [io].
 */
class AppSession(
    val api: HeyarrApi,
    /** The profile a Want defaults to (Settings → quality profile). */
    val defaultProfile: String,
    private val settings: SettingsStore,
    val external: ExternalMetadata,
    val recent: RecentSearches,
    private val scope: CoroutineScope,
    /**
     * The credential this session's [api] presents — [Credential.Guest] when the phone
     * is browsing anonymously (guest-as-default), else the enrolled Bearer/Device.
     * Drives all UI gating through [mode]; defaults to Guest so previews/tests that
     * don't care get the browse-only surface.
     */
    val credential: Credential = Credential.Guest,
) {
    val baseUrl: String get() = api.baseUrl

    /** Shared provider-discovery request state for Search and the standalone Discover surface. */
    val discoveryController = DiscoveryController(
        scope = scope,
        backend = { MobileDiscoveryBackend(api) },
        onTransportFailure = ::noteTransportFailure,
        ioDispatcher = Dispatchers.IO,
    )

    /** GUEST (anonymous lease) vs ENROLLED (a real credential) — the single gate every screen consults. */
    val mode: ClientMode get() = credential.mode()

    /** True while this session is browsing as an anonymous guest (no login). */
    val isGuest: Boolean get() = mode == ClientMode.GUEST

    var appearance: Appearance by mutableStateOf(Appearance(settings.adaptiveAccents, settings.reduceMotion))
        private set

    /** Public cover art & synopses from keyless sources where the node holds none (Settings → Appearance). */
    var externalMetadata: Boolean by mutableStateOf(settings.externalMetadata)
        private set

    fun updateAppearance(next: Appearance) {
        settings.adaptiveAccents = next.adaptiveAccents
        settings.reduceMotion = next.reduceMotion
        appearance = next
    }

    fun updateExternalMetadata(on: Boolean) {
        settings.externalMetadata = on
        externalMetadata = on
    }

    var connection: Connection by mutableStateOf(Connection.UNKNOWN)
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

    /** True once the want index has answered at least once; before that, every work reads as untracked. */
    var indexLoaded: Boolean by mutableStateOf(false)
        private set

    var profiles: List<QualityProfile> by mutableStateOf(emptyList())
        private set

    val toasts = mutableStateListOf<Toast>()
    private var toastSeq = 0L
    private var heartbeat: Job? = null

    // ── connectivity ─────────────────────────────────────────────────────────────

    fun startHeartbeat() {
        if (heartbeat?.isActive == true) return
        heartbeat = scope.launch {
            while (isActive) {
                probe()
                delay(if (connection == Connection.ONLINE) 30_000 else 8_000)
            }
        }
    }

    suspend fun probe() {
        val t0 = System.nanoTime()
        val result = withContext(Dispatchers.IO) {
            try {
                // The heartbeat (`GET /works`) is a read a guest may make, so a 401 to a
                // guest probe is the node declining this network, not a credential refusal.
                Connection.fromProbe(api.ping(), mode)
            } catch (_: AuthenticationRequiredException) {
                // Signing refused before the request was made: tell the user to
                // re-authenticate, not to check their network.
                Connection.SIGNING_FAILED
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Connection.OFFLINE
            }
        }
        probes++
        lastLatencyMs = (System.nanoTime() - t0) / 1_000_000
        if (result == Connection.ONLINE) {
            lastOkAt = System.currentTimeMillis()
        } else {
            failures++
        }
        connection = result
    }

    /**
     * Called by any screen whose call died on the transport — flips the banner immediately.
     * A guest's 401/403 here is left to the heartbeat: the call may have been an
     * enrolled-only read (expected, see [io]), so only the heartbeat's guest-allowed probe
     * can tell a node that declines this network as a guest network ([Connection.GUEST_REFUSED]).
     */
    fun noteTransportFailure(e: McpTransportException) {
        failures++
        lastFailure = e.message
        val refused = e.status == 401 || e.status == 403
        if (refused && isGuest) return
        connection = if (refused) Connection.UNAUTHORIZED else Connection.OFFLINE
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
        scope.launch {
            indexLoading = true
            val result = io { api.desired() }
            indexLoading = false
            result.onSuccess {
                index = LibraryIndex(it)
                indexLoaded = true
            }
        }
        if (profiles.isEmpty()) scope.launch { io { api.qualityProfiles() }.onSuccess { profiles = it } }
    }

    /** Optimistic want: the card flips to Wanted at once and rolls back with the refusal on failure. */
    fun want(workId: String, title: String, profile: String, onDone: (McpResult<*>?) -> Unit = {}) {
        // Wanting is an enrolled surface; the UI routes a guest to "Sign in to save" first,
        // but guard here too so a stray call never fires an unauthenticated write at the node.
        if (isGuest) {
            onDone(null)
            return
        }
        val before = index
        index = index.withPendingWant(workId, profiles.firstOrNull { it.name == profile }?.id)
        scope.launch {
            val result = io { api.wantWork(workId, profile) }
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

    // ── the consumer "Get it" ────────────────────────────────────────────────────

    /**
     * A catalogued work's availability with no file information to hand, once this client's want
     * index has answered. A guest has no want index by design, and before it loads every work
     * reads as untracked — which [Availability.ofCatalogued] would take for "scanned, so held" —
     * so both stay neutral (null) rather than claim a file.
     */
    fun cataloguedAvailability(workId: String): Availability? =
        if (!isGuest && indexLoaded) Availability.ofCatalogued(index.statusOf(workId)) else null

    /**
     * Titles asked for by "Get it" this session: false while the request is in flight, true once
     * the node took it. A discovery hit has no work id the want index could report, so this is
     * what keeps its button from sending the same want twice. The session is rebuilt for a new
     * node or credential, so it never carries over.
     */
    val requestedTitles = mutableStateMapOf<String, Boolean>()

    fun titleKey(title: String, year: Int?, type: MediaType): String =
        "${type.name}|${title.trim().lowercase()}|${year ?: ""}"

    /**
     * The profile a "Get it" wants at: this phone's chosen default when it fits the kind, else the
     * one the Want sheet would pre-select; null when nothing fits, so the caller opens the sheet.
     */
    private fun getItProfile(type: MediaType): String? {
        if (type.apiName == null) return null
        val offered = QualityProfiles.offeredFor(type, profiles)
        return offered.firstOrNull { it.name == defaultProfile }?.name ?: QualityProfiles.defaultFor(type, profiles)
    }

    /**
     * The consumer "Get it": a monitored want at [getItProfile], without the sheet. By id for a work
     * the library knows, by title for a discovery hit. Returns false and does nothing when no
     * profile fits (or for a guest), so the caller can open the sheet or the sign-in prompt.
     */
    fun getIt(workId: String?, title: String, type: MediaType, year: Int? = null): Boolean {
        val profile = getItProfile(type)?.takeUnless { isGuest } ?: return false
        val key = titleKey(title, year, type)
        if (workId != null || key !in requestedTitles) {
            if (workId != null) {
                index = index.withPendingWant(workId, profiles.firstOrNull { it.name == profile }?.id)
            } else {
                requestedTitles[key] = false
            }
            scope.launch { sendGetIt(GetItRequest(workId, title, type, year, profile)) }
        }
        return true
    }

    /** One "Get it": the work (by id, or by title for a discovery hit) and the profile it is wanted at. */
    private class GetItRequest(
        val workId: String?,
        val title: String,
        val type: MediaType,
        val year: Int?,
        val profile: String,
    )

    private suspend fun sendGetIt(req: GetItRequest) {
        val workId = req.workId
        val result = io {
            if (workId != null) {
                api.wantWork(workId, req.profile)
            } else {
                api.wantTitle(req.title, req.type, req.profile, req.year)
            }
        }
        val ok = result.getOrNull() is McpResult.Ok
        when (val r = result.getOrNull()) {
            is McpResult.Ok -> toast(Toast.Kind.SUCCESS, "On its way: “${req.title}”", "heyarr is looking for a copy.")
            is McpResult.Refused -> refused(r)
            null -> Unit
        }
        if (workId == null) {
            val key = titleKey(req.title, req.year, req.type)
            if (ok) requestedTitles[key] = true else requestedTitles.remove(key)
        } else if (!ok) {
            // Withdraw this request's optimistic want now: the reload below can fail the same way.
            index = index.withoutPendingWant(workId)
        }
        // Reload rather than restore a snapshot: another want may have landed meanwhile.
        refreshIndex()
    }

    // ── errors → toasts ──────────────────────────────────────────────────────────

    /** Run [block] on IO, turning a transport failure into the offline banner + an error toast. */
    suspend fun <T> io(block: () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching(block) }.also { r ->
        r.onSuccess { noteSuccess() }
        r.onFailure { e ->
            when {
                // A guest reaching an enrolled-only endpoint gets a 401/403 — expected, not a
                // broken connection. Swallow it quietly so the node stays "online" and browse/
                // play keep working; the UI hides those surfaces for guests, this guards the
                // ones that slip through (the existing "Guest → 403" call sites).
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

    fun toast(kind: Toast.Kind, title: String, detail: String? = null, tool: String? = null) {
        val t = Toast(++toastSeq, kind, title, detail, tool)
        toasts.add(t)
        scope.launch {
            delay(if (kind == Toast.Kind.REFUSED || kind == Toast.Kind.ERROR) 9_000 else 4_500)
            toasts.remove(t)
        }
    }

    fun dismiss(toast: Toast) {
        toasts.remove(toast)
    }
}

private class MobileDiscoveryBackend(private val api: HeyarrApi) : DiscoveryBackend {
    override fun providers() = api.providers()

    override fun discover(query: String): DiscoveryAnswer = when (val result = api.discover(query)) {
        is McpResult.Ok -> DiscoveryAnswer.Hits(result.value)
        is McpResult.Refused -> DiscoveryAnswer.Refused(McpError(result.code, result.message, result.tool))
    }
}
