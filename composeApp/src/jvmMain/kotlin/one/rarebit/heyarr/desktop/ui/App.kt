package one.rarebit.heyarr.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import one.rarebit.heyarr.desktop.ui.components.AudioDock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.discovery.MdnsResolver
import one.rarebit.heyarr.core.discovery.NoMdnsResolver
import one.rarebit.heyarr.desktop.heyarr.HeyarrApi
import one.rarebit.heyarr.desktop.heyarr.PlaybackTarget
import one.rarebit.heyarr.desktop.heyarr.McpResult
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.desktop.open.BlobDownloader
import one.rarebit.heyarr.desktop.open.ExternalOpener
import one.rarebit.heyarr.desktop.open.JdkBlobDownloader
import one.rarebit.heyarr.desktop.open.OpenExternally
import one.rarebit.heyarr.desktop.open.XdgOpen
import one.rarebit.heyarr.desktop.playback.MpvPlayer
import one.rarebit.heyarr.desktop.playback.Player
import one.rarebit.heyarr.desktop.settings.SettingsStore
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.state.ArtworkLoader
import one.rarebit.heyarr.desktop.state.Connection
import one.rarebit.heyarr.desktop.state.SearchController
import one.rarebit.heyarr.desktop.state.Toast
import one.rarebit.heyarr.desktop.theme.HeyarrTheme
import one.rarebit.heyarr.desktop.theme.LocalAppearance
import one.rarebit.heyarr.ui.theme.MediaThemes
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.ui.theme.Tokens
import one.rarebit.heyarr.desktop.ui.components.FilterChip
import one.rarebit.heyarr.desktop.ui.components.GhostButton
import one.rarebit.heyarr.desktop.ui.components.OfflineBanner
import one.rarebit.heyarr.desktop.ui.components.Panel
import one.rarebit.heyarr.desktop.ui.components.PrimaryButton
import one.rarebit.heyarr.desktop.ui.components.SideNav
import one.rarebit.heyarr.desktop.ui.components.ToastCard
import one.rarebit.heyarr.desktop.ui.components.NowPlayingBar
import one.rarebit.heyarr.desktop.ui.components.PlaybackHost
import one.rarebit.heyarr.desktop.ui.screens.accentHex
import one.rarebit.heyarr.desktop.ui.screens.DetailScreen
import one.rarebit.heyarr.desktop.ui.screens.DetailState
import one.rarebit.heyarr.desktop.ui.screens.Field
import one.rarebit.heyarr.desktop.ui.screens.DiscoverScreen
import one.rarebit.heyarr.desktop.ui.screens.DiscoverState
import one.rarebit.heyarr.desktop.ui.screens.HomeScreen
import one.rarebit.heyarr.desktop.ui.screens.WantByTitle
import one.rarebit.heyarr.desktop.ui.screens.HomeState
import one.rarebit.heyarr.desktop.ui.screens.LibraryScreen
import one.rarebit.heyarr.desktop.ui.screens.LibraryState
import one.rarebit.heyarr.desktop.ui.screens.MissingScreen
import one.rarebit.heyarr.desktop.ui.screens.MissingState
import one.rarebit.heyarr.desktop.ui.screens.NowPlayingScreen
import one.rarebit.heyarr.desktop.ui.screens.NowPlayingState
import one.rarebit.heyarr.desktop.ui.screens.PlayerKeys
import one.rarebit.heyarr.desktop.ui.screens.PlayerScreen
import one.rarebit.heyarr.desktop.ui.screens.PlayerScreenState
import one.rarebit.heyarr.desktop.ui.screens.ReaderScreen
import one.rarebit.heyarr.desktop.ui.screens.SearchScreen
import one.rarebit.heyarr.desktop.ui.screens.SettingsScreen
import one.rarebit.heyarr.desktop.ui.screens.SettingsState

/** A pending Want: either an existing work by id, or a title the library has never seen. */
data class WantRequest(val workId: String?, val title: String, val year: Int? = null, val type: MediaType = MediaType.MOVIE)

/**
 * The shell: left nav, offline banner, the routed screen, the toast stack and the Want
 * sheet. Global keys: ⌘K / Ctrl-K focuses search, Ctrl-1…5 jump sections, ⌘, opens
 * Settings, Esc goes back or closes the sheet. The accent in force follows the media
 * of the screen in focus (a series detail turns the nav violet), per the appearance
 * preference.
 */
@Composable
fun App(
    settings: SettingsStore,
    transport: HttpTransport,
    player: Player = MpvPlayer(),
    opener: ExternalOpener = XdgOpen(),
    downloader: BlobDownloader = JdkBlobDownloader(),
    initialRoute: Route = Route.Consume(Experience.WATCH),
    artworkLoader: ArtworkLoader? = null,
    externalMetadata: one.rarebit.heyarr.desktop.state.ExternalMetadata? = null,
    /** LAN mDNS browser for auto-discovery; the no-op default keeps previews/tests network-free. */
    mdns: MdnsResolver = NoMdnsResolver,
    /** Preview/test seam: a query typed into search on first composition. */
    initialQuery: String? = null,
    /** Preview/test seam: open the connection sheet at once. */
    initialConnectionSheet: Boolean = false,
    initialLibraryTab: Int = 0,
    /** Offscreen renderer seam: real transport remains disabled by PlaybackHost in headless mode. */
    initialAudioQueue: List<Route.Player> = emptyList(),
    /** Called when the player wants the window fullscreen (Main flips the WindowState placement). */
    onFullscreen: (Boolean) -> Unit = {},
    /**
     * Turn on the real desktop device-enrol stack ("Sign in to save" over guest). The
     * desktop entry point ([one.rarebit.heyarr.desktop.MainKt]) sets this true; previews /
     * tests leave it false so they stay guest and touch no device key store.
     */
    enableDeviceEnrol: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val session = remember { AppSession(settings, transport, player, OpenExternally(downloader, opener), scope, artworkLoader, externalMetadata, mdns, enableDeviceEnrol).also { if (initialAudioQueue.isNotEmpty()) it.playback.queueAudio(initialAudioQueue) } }
    val nav = remember { Nav(initialRoute) }
    val search = remember { SearchController(scope, { session.api }, session::noteTransportFailure) }
    val home = remember { HomeState() }
    val discover = remember { DiscoverState() }
    val library = remember { LibraryState().apply { tab = initialLibraryTab } }
    val shelves = remember { Experience.entries.associateWith { LibraryState() } }
    var consuming by remember { mutableStateOf(initialRoute is Route.Consume) }
    val missing = remember { MissingState() }
    val nowPlaying = remember { NowPlayingState() }
    val settingsState = remember { SettingsState() }
    val details = remember { mutableStateMapOf<String, DetailState>() }
    val playerScreen = remember { PlayerScreenState() }
    val playback = session.playback
    val fullscreen = playback.fullscreen
    fun setFullscreen(on: Boolean) { playback.fullscreen = on; onFullscreen(on) }
    val searchFocus = remember { FocusRequester() }
    var focusSearchTick by remember { mutableStateOf(0) }
    var want by remember { mutableStateOf<WantRequest?>(null) }
    // The "Sign in to save" prompt a guest sees when they reach for an enrolled-only action.
    var showSignIn by remember { mutableStateOf(false) }
    var showConnection by remember { mutableStateOf(initialConnectionSheet) }
    val connectionState = remember { ConnectionState() }

    LaunchedEffect(Unit) { session.startHeartbeat(); session.refreshIndex(); initialQuery?.let { search.updateQuery(it) } }
    // Resume vault sync (W4) if a folder was configured, and stop the daemon (closing its folder
    // watch) when the shell leaves composition — the session scope dies with it, but closing the
    // WatchService explicitly frees the OS handle.
    DisposableEffect(Unit) {
        session.vault?.resume()
        onDispose { session.vault?.shutdown() }
    }
    // A different node means different work ids: the per-work detail caches are worthless.
    LaunchedEffect(session.generation) { if (session.generation > 0) details.clear() }
    LaunchedEffect(focusSearchTick) { if (focusSearchTick > 0) runCatching { searchFocus.requestFocus() } }

    fun openSearch() { consuming = false; nav.go(Route.Search); focusSearchTick++ }
    // A Player route hands its item to the session before the screen composes, so the first
    // frame already has something to show and nothing bounces back to the previous screen.
    fun go(route: Route) {
        if (route is Route.Player) {
            playback.play(route)
            // Music stays in the shell while the reader/catalog retains focus.
            if (route.typeHint.isListening()) return
        }
        nav.go(route)
    }
    val current = nav.current
    LaunchedEffect(current) {
        when (current) {
            is Route.Consume -> consuming = true
            Route.Home, Route.Discover, Route.Search, Route.Library, Route.Missing -> consuming = false
            else -> Unit // Detail, reader and settings retain the section they were opened from.
        }
    }
    // The session player streams with the saved connection; the pop-out OSC takes the media accent.
    LaunchedEffect(session.config, playback.current?.assetId) {
        playback.baseUrl = session.config.baseUrl; playback.token = session.config.bearerToken.trim()
        // Resolve each play URL through the server's playback plan, so a 4K/HEVC asset
        // is transcoded down to a smoothly-decodable stream; falls back to the direct
        // blob when there is no connection.
        playback.resolvePlaybackTarget = { item ->
            session.api?.playbackTarget(item.assetId, item.blobHash)
                ?: PlaybackTarget(HeyarrApi.blobUrl(playback.baseUrl, item.blobHash))
        }
        playback.accentHex = accentHex(MediaThemes.of(playback.type).accent)
    }
    // Player keys are handled at the window level: a KeyEventDispatcher runs before
    // focus-owner dispatch (even with no owner at all), so the player answers whenever its
    // screen is up and no sheet is open, whatever Compose node happens to hold focus.
    DisposableEffect(Unit) {
        val kfm = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = java.awt.KeyEventDispatcher { e ->
            if (e.id != java.awt.event.KeyEvent.KEY_PRESSED || e.isControlDown || e.isMetaDown || e.isAltDown) return@KeyEventDispatcher false
            if (!playback.onPlayerScreen || playback.popout || want != null || showConnection || showSignIn) return@KeyEventDispatcher false
            val key = PlayerKeys.fromAwt(e.keyCode) ?: return@KeyEventDispatcher false
            playback.wakeControls()
            val handled = PlayerKeys.handle(
                key, playback.player,
                onFullscreen = { setFullscreen(!playback.fullscreen) },
                onBack = { if (playback.fullscreen) setFullscreen(false); nav.back() },
                fullscreen = playback.fullscreen,
            )
            if (handled) e.consume()
            handled
        }
        kfm.addKeyEventDispatcher(dispatcher)
        onDispose { kfm.removeKeyEventDispatcher(dispatcher) }
    }
    // Navigating to a Player route hands the item to the session; the surface host does the rest.
    LaunchedEffect(current) { (current as? Route.Player)?.let { r -> if (playback.current?.assetId != r.assetId) playback.play(r) } }
    val focusType = when (current) {
        is Route.Detail -> details[current.workId]?.detail?.work?.kind?.let { MediaType.from(it) } ?: current.typeHint
        is Route.Player -> current.typeHint
        else -> MediaType.MOVIE
    }
    val shellTheme = if (session.appearance.adaptiveAccents) MediaThemes.of(focusType) else MediaThemes.default

    val uiScale = session.config.effectiveUiScale()
    CompositionLocalProvider(LocalAppearance provides session.appearance, LocalDensity provides Density(uiScale, fontScale = 1f)) {
        HeyarrTheme(shellTheme) {
            Box(
                Modifier.fillMaxSize().background(Tokens.bgBase).onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val mod = e.isCtrlPressed || e.isMetaPressed
                    if (current is Route.Player && !mod && want == null) {
                        if (PlayerKeys.handle(e.key, playback.player, { setFullscreen(!fullscreen) }, { if (fullscreen) setFullscreen(false); nav.back() }, fullscreen)) return@onPreviewKeyEvent true
                    }
                    when {
                        mod && e.key == Key.K -> { openSearch(); true }
                        mod && e.key == Key.Comma -> { nav.go(Route.Settings); true }
                        mod && e.key == Key.One -> { consuming = true; nav.go(Route.Consume(Experience.WATCH)); true }
                        mod && e.key == Key.Two -> { consuming = true; nav.go(Route.Consume(Experience.LISTEN)); true }
                        mod && e.key == Key.Three -> { consuming = true; nav.go(Route.Consume(Experience.READ)); true }
                        mod && e.key == Key.Four -> { nav.go(Route.Missing); true }
                        mod && e.key == Key.Five -> { nav.go(Route.NowPlaying); true }
                        e.key == Key.Escape && showConnection -> { showConnection = false; true }
                        e.key == Key.Escape && showSignIn -> { showSignIn = false; true }
                        e.key == Key.Escape && want != null -> { want = null; true }
                        e.key == Key.Escape && (current is Route.Detail || current is Route.Reader) -> { nav.back(); true }
                        else -> false
                    }
                },
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val compact = maxWidth < Tokens.compactBreakpoint
                    val audioDock = showAudioDock(playback.type, playback.active, maxWidth.value, fullscreen)
                    Row(Modifier.fillMaxSize()) {
                        if (!fullscreen) SideNav(
                            current, onGo = { if (it == Route.Search) openSearch() else nav.go(it) }, connection = session.connection, compact = compact,
                            connectionDetail = run {
                                val host = session.config.baseUrl.removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':')
                                session.lastLatencyMs?.let { "$it ms · $host" } ?: host.ifBlank { null }
                            },
                            onConnection = { showConnection = true }, consuming = consuming,
                        )
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            if (!fullscreen) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                FilterChip("Consume", consuming, { consuming = true; nav.go(Route.Consume(Experience.WATCH)) })
                                FilterChip("Manage", !consuming, { consuming = false; nav.go(Route.Search) })
                                GhostButton("Search everything", ::openSearch, icon = androidx.compose.material.icons.Icons.Rounded.Search)
                            }
                            if (!fullscreen) when (session.connection) {
                                Connection.OFFLINE -> OfflineBanner("Can't reach heyarr", session.config.baseUrl, onRetry = { scope.launch { session.probe() } }, onSettings = { nav.go(Route.Settings) })
                                Connection.UNAUTHORIZED -> OfflineBanner("heyarr refused the token", "Check the bearer token in Settings.", onRetry = { scope.launch { session.probe() } }, onSettings = { nav.go(Route.Settings) })
                                else -> {}
                            }
                            // Guest choke point: any "save" action (want by id or by title) is an
                            // enrolled-only surface, so in guest mode it opens the "Sign in to save"
                            // prompt instead of the Want sheet. This gates want uniformly across
                            // every screen from one place.
                            val onWant: (String, String) -> Unit = { id, title -> if (session.isGuest) showSignIn = true else want = WantRequest(id, title) }
                            val onWantTitle: WantByTitle = { title, year, type -> if (session.isGuest) showSignIn = true else want = WantRequest(null, title, year, type) }
                            Box(Modifier.weight(1f)) { when (val r = current) {
                                is Route.Consume -> LibraryScreen(session, shelves.getValue(r.experience), ::go, onWant, experience = r.experience)
                                Route.Home -> HomeScreen(session, home, ::go, onWant)
                                Route.Discover -> DiscoverScreen(session, discover, onWantTitle)
                                Route.Search -> SearchScreen(session, search, ::go, onWant, onWantTitle, searchFocus)
                                Route.Library -> LibraryScreen(session, library, ::go, onWant)
                                Route.Missing -> MissingScreen(session, missing, ::go, onWantTitle = { if (session.isGuest) showSignIn = true else want = WantRequest(null, "") })
                                Route.NowPlaying -> NowPlayingScreen(session, nowPlaying)
                                Route.Settings -> SettingsScreen(session, settingsState, onSourcesChanged = { search.invalidateSources() })
                                is Route.Detail -> DetailScreen(session, r, details.getOrPut(r.workId) { DetailState(r.workId) }, onBack = nav::back, onOpen = ::go, onWant = onWant)
                                is Route.Player -> PlayerScreen(session, r, playerScreen, fullscreen = fullscreen, onFullscreen = ::setFullscreen, onBack = { if (fullscreen) setFullscreen(false); nav.back() }, onOpen = ::go)
                                is Route.Reader -> ReaderScreen(session, r, onBack = nav::back)
                            } }
                            if (playback.active && current !is Route.Player && !fullscreen && !audioDock) NowPlayingBar(session, onOpen = { playback.current?.let { nav.go(it) } })
                        }
                        if (audioDock) AudioDock(session, onOpen = { playback.current?.let { nav.go(it) } })
                    }
                }
                PlaybackHost(session)
                Column(Modifier.align(Alignment.BottomEnd).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                    for (t in session.toasts.takeLast(4)) ToastCard(t, onDismiss = { session.dismiss(t) })
                }
                want?.let { req -> WantSheet(session, req, onClose = { want = null }) }
                if (showSignIn) SignInSheet(session, onClose = { showSignIn = false }, onOpenSettings = { showSignIn = false; nav.go(Route.Settings) })
                if (showConnection) ConnectionSheet(session, connectionState, onClose = { showConnection = false }, onSettings = { nav.go(Route.Settings) })
            }
        }
    }
}

/**
 * The "Sign in to save" upgrade sheet a guest sees when they reach for an enrolled-only
 * action (want / follow / your place). Browsing and playing need no login; this is the
 * optional step to save wants, follows and resume state. The live upgrade is a pasted
 * bearer token (the enrol path); device/QR login (Voidbind) is the next upgrade and is
 * noted here. Paste-and-save flips the client out of guest mode.
 */
@Composable
private fun SignInSheet(session: AppSession, onClose: () -> Unit, onOpenSettings: () -> Unit) {
    var token by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(Tokens.bgBase.copy(alpha = 0.7f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose), contentAlignment = Alignment.Center) {
        Box(Modifier.width(520.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})) {
            Panel("Sign in to save", trailing = { GhostButton("Close", onClose) }) {
                Text("You're browsing as a guest — watch and listen freely, no account needed. Sign in to save your place, keep playlists, and want or follow things.", style = MaterialTheme.typography.bodyMedium, color = Tokens.textPrimary)
                Text("Paste a bearer token (heyarr_<id>_<secret>) to sign in on this machine.", style = MaterialTheme.typography.labelMedium, color = Tokens.textMuted)
                Field("Bearer token", token, secret = true) { token = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Sign in", {
                        session.save(session.config.copy(bearerToken = token.trim()))
                        onClose()
                    }, icon = Icons.Rounded.Add, enabled = token.isNotBlank())
                    GhostButton("Open Settings", onOpenSettings)
                    GhostButton("Keep browsing", onClose)
                }
                Text("Device sign-in (Voidbind device/QR) is the next upgrade and is not wired on desktop yet — a pasted token is the way in for now.", style = MaterialTheme.typography.bodySmall, color = Tokens.textDisabled)
            }
        }
    }
}

/**
 * The Want sheet: pick a quality profile (required — "this should exist" with no
 * standard cannot be evaluated), optionally a note, and go. Work-by-id when opened from
 * a card; title + type when opened from Missing for something the library has never seen.
 */
@Composable
private fun WantSheet(session: AppSession, req: WantRequest, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(req.title) }
    var year by remember { mutableStateOf(req.year?.toString() ?: "") }
    var type by remember { mutableStateOf(req.type) }
    var profile by remember(session.profiles) { mutableStateOf(session.profiles.firstOrNull { it.name == "everyday" }?.name ?: session.profiles.firstOrNull()?.name ?: "") }
    var monitor by remember { mutableStateOf(true) }
    var reason by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val byTitle = req.workId == null
    Box(Modifier.fillMaxSize().background(Tokens.bgBase.copy(alpha = 0.7f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose), contentAlignment = Alignment.Center) {
        Box(Modifier.width(520.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})) {
            Panel(if (byTitle) "Want by title" else "Want “${req.title}”", trailing = { GhostButton("Close", onClose) }) {
                if (byTitle) {
                    Field("Title", title) { title = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("Year (optional)", year, Modifier.width(140.dp)) { year = it.filter { c -> c.isDigit() }.take(4) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (t in MediaType.SEARCHABLE) FilterChip(t.label, type == t, { type = t }) }
                    Text("Created from the title with the same normalisation a scan uses, so wanting it now and scanning it later converge on one work.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
                }
                Text("Quality profile — the standard this want is measured against", style = MaterialTheme.typography.labelMedium, color = Tokens.textMuted)
                if (session.profiles.isEmpty()) Text("No profiles loaded yet.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (p in session.profiles) FilterChip(p.name, profile == p.name, { profile = p.name }) }
                session.profiles.firstOrNull { it.name == profile }?.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip("Keep looking for something better", monitor, { monitor = !monitor })
                }
                Field("Reason (a note for whoever reads this in six months)", reason) { reason = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Want", {
                        val a = session.api ?: return@PrimaryButton
                        if (!byTitle) { session.want(req.workId!!, req.title, profile, monitor, reason.ifBlank { null }) { onClose() }; return@PrimaryButton }
                        busy = true
                        scope.launch {
                            session.io { a.wantTitle(title.trim(), type, profile, year.toIntOrNull(), monitor, reason.ifBlank { null }) }.onSuccess { r ->
                                when (r) {
                                    is McpResult.Ok -> { session.toast(Toast.Kind.SUCCESS, "Wanted “${title.trim()}”", "Measured against the $profile profile."); session.refreshIndex(); onClose() }
                                    is McpResult.Refused -> session.refused(r)
                                }
                            }
                            busy = false
                        }
                    }, icon = Icons.Rounded.Add, enabled = !busy && profile.isNotBlank() && (!byTitle || title.isNotBlank()))
                    GhostButton("Cancel", onClose)
                }
            }
        }
    }
}
