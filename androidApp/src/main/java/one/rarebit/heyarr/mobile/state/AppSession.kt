package one.rarebit.heyarr.mobile.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.core.auth.ClientMode
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.auth.mode
import one.rarebit.heyarr.core.heyarr.QualityProfile
import one.rarebit.heyarr.core.mcp.McpRefusedException
import one.rarebit.heyarr.core.mcp.McpTransportException
import one.rarebit.heyarr.core.state.ExternalMetadata
import one.rarebit.heyarr.core.state.LibraryIndex
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.mobile.heyarr.HeyarrApi
import one.rarebit.heyarr.mobile.heyarr.McpResult
import one.rarebit.heyarr.mobile.settings.SettingsStore
import one.rarebit.heyarr.ui.theme.Appearance

/** Whether heyarr can be reached right now — drives the offline banner. */
enum class Connection { UNKNOWN, ONLINE, OFFLINE, UNAUTHORIZED }

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
        val ok = withContext(Dispatchers.IO) { runCatching { api.ping() }.getOrDefault(false) }
        probes++
        lastLatencyMs = (System.nanoTime() - t0) / 1_000_000
        if (ok) {
            lastOkAt = System.currentTimeMillis()
            connection = Connection.ONLINE
        } else {
            failures++
            connection = Connection.OFFLINE
        }
    }

    /** Called by any screen whose call died on the transport — flips the banner immediately. */
    fun noteTransportFailure(e: McpTransportException) {
        failures++
        lastFailure = e.message
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
        scope.launch {
            indexLoading = true
            val result = io { api.desired() }
            indexLoading = false
            result.onSuccess { index = LibraryIndex(it) }
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
