package one.rarebit.heyarr.mobile.nav

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.launch
import one.rarebit.heyarr.mobile.AppGraph
import one.rarebit.heyarr.mobile.AppViewModel
import one.rarebit.heyarr.mobile.device.EnrolScreen
import one.rarebit.heyarr.mobile.device.EnrolUiState
import one.rarebit.heyarr.mobile.library.LibraryClient
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.playback.PlaybackClient
import one.rarebit.heyarr.mobile.playback.PlaybackProgress
import one.rarebit.heyarr.mobile.playlist.AddToPlaylistDialog
import one.rarebit.heyarr.mobile.playlist.PersonalActionsViewModel
import one.rarebit.heyarr.mobile.playlist.PlaylistScreen
import one.rarebit.heyarr.mobile.playlist.PlaylistViewModel
import one.rarebit.heyarr.mobile.playlist.PlaylistsScreen
import one.rarebit.heyarr.mobile.playlist.PlaylistsViewModel
import one.rarebit.heyarr.mobile.reader.ReaderActivity
import one.rarebit.heyarr.mobile.state.Connection
import one.rarebit.heyarr.mobile.state.Toast
import one.rarebit.heyarr.mobile.theme.HeyarrTheme
import one.rarebit.heyarr.mobile.theme.LocalAppearance
import one.rarebit.heyarr.ui.theme.MediaThemes
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.mobile.ui.components.HeyarrBottomBar
import one.rarebit.heyarr.mobile.ui.components.HeyarrNavRail
import one.rarebit.heyarr.mobile.ui.components.GhostButton
import one.rarebit.heyarr.mobile.ui.components.NowPlayingBar
import one.rarebit.heyarr.mobile.ui.components.OfflineBanner
import one.rarebit.heyarr.mobile.ui.components.PrimaryButton
import one.rarebit.heyarr.mobile.ui.components.ToastCard
import one.rarebit.heyarr.mobile.ui.screens.AudioQueueScreen
import one.rarebit.heyarr.mobile.ui.screens.CastScreen
import one.rarebit.heyarr.mobile.ui.screens.DetailPersonal
import one.rarebit.heyarr.mobile.ui.screens.DetailPlayback
import one.rarebit.heyarr.mobile.ui.screens.DetailScreen
import one.rarebit.heyarr.mobile.ui.screens.HomeScreen
import one.rarebit.heyarr.mobile.ui.screens.LibraryScreen
import one.rarebit.heyarr.mobile.ui.screens.MissingScreen
import one.rarebit.heyarr.mobile.ui.screens.PersonalRows
import one.rarebit.heyarr.mobile.ui.screens.PlayerScreen
import one.rarebit.heyarr.mobile.ui.screens.SearchScreen
import one.rarebit.heyarr.mobile.ui.screens.SettingsScreen
import one.rarebit.heyarr.mobile.ui.screens.TelemetryScreen
import one.rarebit.heyarr.mobile.ui.screens.WantRequest
import one.rarebit.heyarr.mobile.ui.screens.WantSheet

/**
 * The signed-in app: the design system's shell — a bottom bar on a phone, a left rail
 * from [Tokens.railBreakpoint] up — over a typed navigation graph ([Route]), with the
 * offline banner, the persistent now-playing bar, the toast stack and the Want sheet
 * around it. The accent in force follows the media of the screen in focus (a series
 * detail turns the bar violet), per the appearance preference.
 *
 * Every screen reads one [SessionHolder] keyed on the [ApiEnv] snapshot, so a node or
 * credential-shape change rebuilds the session rather than keeping a stale client.
 * Nothing here touches auth or enrolment: those screens are re-homed as routes and
 * take the same props they always did.
 */
@UnstableApi
@Composable
fun HeyarrNavHost(
    vm: AppViewModel,
    graph: AppGraph,
    focusDevice: Int,
    /** Raise the "Sign in to save" upgrade (QR/device) over the guest shell. */
    onSignInToSave: () -> Unit = {},
    navController: NavHostController = rememberNavController(),
) {
    val config by vm.configState.collectAsStateWithLifecycle()
    val authority by vm.sessionAuthority.collectAsStateWithLifecycle()
    val enrolState by vm.enrolState.collectAsStateWithLifecycle()
    val parkedInvite by vm.parkedInvite.collectAsStateWithLifecycle()
    val nowPlaying by vm.playback.nowPlaying.collectAsStateWithLifecycle()
    val playbackNotice by vm.playback.notice.collectAsStateWithLifecycle()
    val audio = graph.audio
    val video = graph.video
    val audioState by audio.state.collectAsStateWithLifecycle()

    // Never null: Credential.Guest when nothing is enrolled (guest-as-default). The env key
    // includes the credential class, so adopting a real credential rebuilds the session enrolled.
    val credential = vm.effectiveCredential()
    val env = ApiEnv(config.baseUrl, config.defaultQualityProfile, credential, vm.transport)
    val holder: SessionHolder = viewModel(
        key = "session:${env.key}",
        factory = viewModelFactory { initializer { SessionHolder(env, graph.settings, graph.external, graph.recent) } },
    )
    val session = holder.session
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var want by remember { mutableStateOf<WantRequest?>(null) }
    // A guest tapped a gated affordance (Want, Follow, playlists…): offer the upgrade instead
    // of firing an unauthenticated write. The string is the surface, for the sheet's copy.
    var signInPrompt by remember { mutableStateOf<String?>(null) }
    var openPlayerOnAudio by remember { mutableStateOf(false) }

    LaunchedEffect(session) { session.startHeartbeat(); session.refreshIndex() }
    LaunchedEffect(playbackNotice) { playbackNotice?.let { session.toast(Toast.Kind.INFO, it); vm.playback.clearNotice() } }
    LaunchedEffect(video) {
        video.onProgress = { p ->
            when (p.event) {
                PlaybackProgress.Event.TICK -> vm.playback.reportProgress(p.seconds)
                PlaybackProgress.Event.PAUSED -> vm.playback.reportPause(p.seconds)
                PlaybackProgress.Event.RESUMED -> vm.playback.reportResume(p.seconds)
                PlaybackProgress.Event.ENDED -> vm.playback.reportEnded(p.seconds, completed = true)
                PlaybackProgress.Event.LEFT -> vm.playback.reportEnded(p.seconds, completed = false)
            }
        }
        video.onIssue = vm.playback::onIssue
    }
    // What the coordinator resolved is what the session plays; a new asset puts the player in front.
    var lastStartedAsset by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(nowPlaying) {
        val np = nowPlaying
        if (np == null) { video.stop(); lastStartedAsset = null; return@LaunchedEffect }
        audio.stop()
        video.load(np)
        if (np.assetId != lastStartedAsset) { lastStartedAsset = np.assetId; navController.navigate(Route.Player) { launchSingleTop = true } }
    }
    LaunchedEffect(audioState.item?.assetId) {
        if (openPlayerOnAudio && audioState.item != null) { openPlayerOnAudio = false; navController.navigate(Route.Player) { launchSingleTop = true } }
    }
    // Fullscreen is first-class: landscape and no system bars while it is on.
    LaunchedEffect(video.fullscreen) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(activity.window, view)
        if (video.fullscreen) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(focusDevice) { if (focusDevice > 0) navController.navigate(Route.Device) { launchSingleTop = true } }

    val backStack by navController.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val currentRoute: Route? = destination?.let { d ->
        when {
            d.hasRoute(Route.Home::class) -> Route.Home
            d.hasRoute(Route.Discover::class) -> Route.Discover
            d.hasRoute(Route.Search::class) -> Route.Search
            d.hasRoute(Route.Library::class) -> Route.Library
            d.hasRoute(Route.Missing::class) -> Route.Missing
            d.hasRoute(Route.Cast::class) -> Route.Cast
            d.hasRoute(Route.Settings::class) -> Route.Settings
            d.hasRoute(Route.Telemetry::class) -> Route.Telemetry
            d.hasRoute(Route.Device::class) -> Route.Device
            d.hasRoute(Route.Playlists::class) -> Route.Playlists
            d.hasRoute(Route.Playlist::class) -> backStack?.toRoute<Route.Playlist>()
            d.hasRoute(Route.Detail::class) -> backStack?.toRoute<Route.Detail>()
            d.hasRoute(Route.Player::class) -> Route.Player
            else -> null
        }
    }
    val fullScreen = Route.isFullScreen(currentRoute) || video.fullscreen
    val currentSection = Decisions.section(currentRoute)
    val focusType = when (val r = currentRoute) {
        is Route.Detail -> holder.details[r.workId]?.detail?.kind?.let { MediaType.from(it) } ?: r.typeHint
        Route.Player -> video.current?.kind?.let { MediaType.from(it) } ?: if (audioState.item != null) MediaType.MUSIC else MediaType.MOVIE
        Route.Playlists, is Route.Playlist -> MediaType.MUSIC
        else -> MediaType.MOVIE
    }
    val shellTheme = if (session.appearance.adaptiveAccents) MediaThemes.of(focusType) else MediaThemes.default

    // Encrypted personal state (playlists, starred, history), decrypted on-device.
    val personalActions: PersonalActionsViewModel = viewModel(
        key = "personal:${env.key}",
        factory = viewModelFactory { initializer { PersonalActionsViewModel(vm.personalState(env.baseUrl, env.credential), LibraryClient(env.transport, env.baseUrl, env.credential)) } },
    )
    val starredIds by personalActions.starredIds.collectAsStateWithLifecycle()
    val starredWorks by personalActions.starredWorks.collectAsStateWithLifecycle()
    val recentWorks by personalActions.recentWorks.collectAsStateWithLifecycle()
    val addTarget by personalActions.addTarget.collectAsStateWithLifecycle()
    val playlistsForAdd by personalActions.playlists.collectAsStateWithLifecycle()
    val personalRows = PersonalRows(
        starred = starredWorks, recentlyPlayed = recentWorks, starredIds = starredIds,
        // A work card's id is a bare work id — i.e. ItemRef.work(id).encode() — so it round-trips as-is.
        onToggleStar = if (personalActions.enabled) ({ w: Work -> personalActions.toggleStar(w.id) }) else null,
        onAddToPlaylist = if (personalActions.enabled) ({ w: Work -> personalActions.openAddToPlaylist(w.id) }) else null,
        onOpenPlaylists = if (personalActions.enabled) ({ navController.navigate(Route.Playlists) }) else null,
    )
    // Per-track / per-file ★ and Add-to-playlist for the detail screen (issue #41); ids arrive already
    // tagged as `asset:<id>`, so toggleStar/openAddToPlaylist store them opaquely as-is.
    val detailPersonal = DetailPersonal(
        enabled = personalActions.enabled,
        starredIds = starredIds,
        onToggleStar = { id -> personalActions.toggleStar(id) },
        onAddToPlaylist = { id -> personalActions.openAddToPlaylist(id) },
    )

    // The single Want choke point: a guest is routed to "Sign in to save" (GuestGate — WANT
    // requires enrolment); an enrolled client opens the Want sheet.
    val onWant: (String, String) -> Unit = { id, title ->
        if (session.isGuest) signInPrompt = "want" else want = WantRequest(id, title)
    }
    val play = DetailPlayback(
        playVideo = { work, assetId, hash, mime, title, start, queue, art, subtitles ->
            personalActions.recordPlay(work.id)
            video.queue = queue
            vm.playback.playFile(title, assetId, hash, mime, work.kind, start, art, subtitles)
        },
        playAudio = { work, tracks, start ->
            personalActions.recordPlay(work.id)
            vm.playback.stop()
            val (items, index) = Decisions.queueFor(env.baseUrl, work, tracks, start)
            if (items.isNotEmpty()) { openPlayerOnAudio = true; audio.playQueue(items, index) }
        },
        read = { work, asset ->
            asset.blobHash?.let { hash ->
                personalActions.recordPlay(work.id)
                audio.stop()
                context.startActivity(ReaderActivity.intent(context, asset.id, PlaybackClient.blobContentUrl(env.baseUrl, hash), work.title))
            }
        },
    )
    /** Play a playlist (its playable works) as an audio queue. */
    val playPlaylist: (List<Work>) -> Unit = { works ->
        val items = works.filter { !it.blobHash.isNullOrBlank() }.map { w ->
            one.rarebit.heyarr.mobile.playback.AudioItem(
                assetId = w.primaryAssetId ?: w.id, workId = w.id, title = w.title, artist = w.artist, album = null,
                artworkUrl = one.rarebit.heyarr.mobile.catalog.Artwork.posterUrl(env.baseUrl, w),
                contentUrl = PlaybackClient.blobContentUrl(env.baseUrl, w.blobHash!!), mime = w.mime,
            )
        }
        if (items.isNotEmpty()) { vm.playback.stop(); openPlayerOnAudio = true; audio.playQueue(items, 0) }
    }
    fun go(section: one.rarebit.heyarr.mobile.ui.components.NavSection) = navController.navigateTab(Decisions.routeOf(section))
    fun open(route: Route) = navController.navigate(route)
    fun back() { navController.popBackStack() }
    val deviceSummary = when (enrolState) {
        is EnrolUiState.Enrolled -> "This phone is enrolled as a device and signs in with its own key."
        is EnrolUiState.Ready -> "A device key exists; this phone is not enrolled yet."
        is EnrolUiState.Unprovisioned -> "No device key yet — a QR session signs this phone in."
        else -> null
    }

    CompositionLocalProvider(LocalAppearance provides session.appearance) {
        HeyarrTheme(shellTheme) {
            BoxWithConstraints(Modifier.fillMaxSize().background(Tokens.bgBase)) {
                val wide = maxWidth >= Tokens.railBreakpoint
                Row(Modifier.fillMaxSize()) {
                    if (wide && !fullScreen) HeyarrNavRail(
                        currentSection, onGo = ::go, connection = session.connection,
                        connectionDetail = session.lastLatencyMs?.let { "$it ms" },
                        onConnection = { open(Route.Telemetry) },
                    )
                    Column(Modifier.fillMaxSize()) {
                        if (!fullScreen) Box(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                            when (session.connection) {
                                Connection.OFFLINE -> OfflineBanner("Can't reach heyarr", session.baseUrl, onRetry = { scope.launch { session.probe() } }, onSettings = { go(one.rarebit.heyarr.mobile.ui.components.NavSection.SETTINGS) })
                                Connection.UNAUTHORIZED -> OfflineBanner("heyarr refused the credential", "Sign in again, or re-check this device's authorisation in Settings.", onRetry = { scope.launch { session.probe() } }, onSettings = { go(one.rarebit.heyarr.mobile.ui.components.NavSection.SETTINGS) })
                                else -> {}
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            val content = Modifier.fillMaxSize()
                            NavHost(navController = navController, startDestination = Route.Home) {
                                composable<Route.Home> { HomeScreen(session, holder.home, ::open, onWant, onPlayContinue = { e -> e.blobHash?.let { vm.playback.playFile(e.workTitle + (e.subtitle?.let { s -> " — $s" } ?: ""), e.assetId, it, e.mime, e.contentType, startSeconds = e.positionSeconds, artworkUrl = e.artworkPath?.let { p -> one.rarebit.heyarr.mobile.heyarr.HeyarrApi.blobUrlFromPath(env.baseUrl, p) }) } }, modifier = content, personal = personalRows, onSignInToSave = onSignInToSave) }
                                composable<Route.Discover> { HomeScreen(session, holder.home, ::open, onWant, onPlayContinue = { e -> e.blobHash?.let { vm.playback.playFile(e.workTitle, e.assetId, it, e.mime, e.contentType, startSeconds = e.positionSeconds) } }, modifier = content, discover = true, personal = personalRows, onSignInToSave = onSignInToSave) }
                                composable<Route.Search> {
                                    SearchScreen(session, holder.search, ::open, onWant, onPlayEpisode = { ep -> ep.blobHash?.let { vm.playback.playFile("${ep.workTitle ?: ""} — ${ep.title}".trimStart(' ', '—'), ep.assetId ?: ep.id, it, ep.mime, ep.contentType ?: "series") } }, modifier = content)
                                }
                                composable<Route.Library> { LibraryScreen(session, holder.library, ::open, onWant, modifier = content, onPlaylists = if (personalActions.enabled) ({ open(Route.Playlists) }) else null) }
                                composable<Route.Missing> { MissingScreen(session, holder.missing, ::open, onWantTitle = { if (session.isGuest) signInPrompt = "want" else want = WantRequest(null, "") }, modifier = content) }
                                composable<Route.Cast> { CastScreen(session, holder.cast, modifier = content) }
                                composable<Route.Settings> {
                                    SettingsScreen(
                                        session, holder.settingsState, config, authority,
                                        onSaveConnection = vm::updateSettings, onResetConnection = vm::resetSettings, onSignOut = vm::signOut,
                                        onTelemetry = { open(Route.Telemetry) }, onDevice = { open(Route.Device) },
                                        onSourcesChanged = { holder.search.invalidateSources() }, modifier = content, deviceSummary = deviceSummary,
                                        isGuest = session.isGuest, onSignInToSave = onSignInToSave,
                                        onDiscover = { vm.discoverAndSave { found -> session.toast(Toast.Kind.INFO, "Discovered a node (${found.source.name.lowercase()})", found.baseUrl) } },
                                    )
                                }
                                composable<Route.Telemetry> {
                                    TelemetryScreen(session, holder.telemetry, credentialSummary = credential.javaClass.simpleName.lowercase() + " credential, re-stamped per request", onBack = ::back, modifier = content)
                                }
                                composable<Route.Device> {
                                    EnrolScreen(
                                        state = enrolState,
                                        onCreateKey = vm::provisionDevice, onJoinInvite = vm::joinPairing,
                                        onSasMatches = vm::confirmSas, onSasMismatch = vm::rejectSas,
                                        onRetry = vm::retryEnrol, onForget = vm::forgetDevice,
                                        modifier = content.padding(horizontal = Tokens.screenPadding),
                                        parkedInvite = parkedInvite, onDiscardParked = vm::discardParkedInvite,
                                        onCancelPairing = vm::cancelPairing, onRegister = vm::registerDevice,
                                        manage = true,
                                    )
                                }
                                composable<Route.Detail> { entry ->
                                    val route = entry.toRoute<Route.Detail>()
                                    DetailScreen(session, route, holder.detail(route.workId), play, onBack = ::back, onOpen = ::open, onWant = onWant, modifier = content, personal = detailPersonal)
                                }
                                composable<Route.Playlists> {
                                    val plVm: PlaylistsViewModel = viewModel(key = "playlists:${env.key}", factory = viewModelFactory { initializer { PlaylistsViewModel(vm.personalState(env.baseUrl, env.credential)) } })
                                    val plState by plVm.state.collectAsStateWithLifecycle()
                                    PlaylistsScreen(state = plState, onOpen = { sid, name -> open(Route.Playlist(sid, name)) }, onCreate = { name -> plVm.create(name) { sid -> open(Route.Playlist(sid, name)) } }, onBack = ::back, modifier = content)
                                }
                                composable<Route.Playlist> { entry ->
                                    val r = entry.toRoute<Route.Playlist>()
                                    val ps = vm.personalState(env.baseUrl, env.credential)
                                    if (ps == null) {
                                        LaunchedEffect(Unit) { back() }
                                    } else {
                                        val plVm: PlaylistViewModel = viewModel(key = "playlist:${r.spaceId}:${env.key}", factory = viewModelFactory { initializer { PlaylistViewModel(r.spaceId, r.title, ps, LibraryClient(env.transport, env.baseUrl, env.credential)) } })
                                        val st by plVm.state.collectAsStateWithLifecycle()
                                        PlaylistScreen(
                                            state = st, onBack = ::back,
                                            onPlayAll = playPlaylist,
                                            onOpenWork = { w -> open(detailRoute(w.id, MediaType.from(w.kind), w.title, from = "Library")) },
                                            onRemove = plVm::remove, onRename = plVm::rename, modifier = content,
                                        )
                                    }
                                }
                                composable<Route.Player> {
                                    when (Decisions.playerContent(nowPlaying, audioState.item)) {
                                        Decisions.PlayerContent.VIDEO -> PlayerScreen(
                                            session, video, holder.player, onBack = ::back,
                                            onNext = { e -> vm.playback.playFile(e.title, e.assetId, e.blobHash, e.mime, e.kind, artworkUrl = video.current?.artworkUrl) },
                                            onStop = { vm.playback.stop(); back() }, modifier = content,
                                        )
                                        Decisions.PlayerContent.AUDIO -> AudioQueueScreen(
                                            state = audioState, onBack = ::back,
                                            onTogglePlay = audio::togglePlayPause, onNext = audio::next, onPrevious = audio::previous,
                                            onSeek = audio::seekTo, onSkipTo = audio::skipTo,
                                            onStop = { audio.stop(); back() }, modifier = content,
                                        )
                                        Decisions.PlayerContent.NONE -> LaunchedEffect(Unit) { back() }
                                    }
                                }
                            }
                        }
                        if (Decisions.showNowPlayingBar(fullScreen, video.active, audioState.item)) NowPlayingBar(
                            video = video, audio = audioState,
                            onOpen = { navController.navigate(Route.Player) { launchSingleTop = true } },
                            onAudioToggle = audio::togglePlayPause, onAudioNext = audio::next, onAudioSeek = audio::seekTo, onAudioStop = audio::stop,
                            onVideoNext = video.next()?.let { e -> { vm.playback.playFile(e.title, e.assetId, e.blobHash, e.mime, e.kind, artworkUrl = video.current?.artworkUrl) } },
                        )
                        if (!wide && !fullScreen) HeyarrBottomBar(currentSection, onGo = ::go)
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp).padding(bottom = if (fullScreen) 16.dp else if (wide) 24.dp else 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in session.toasts.takeLast(3)) ToastCard(t, onDismiss = { session.dismiss(t) })
                }
                want?.let { req -> WantSheet(session, req, onClose = { want = null }) }
                signInPrompt?.let { GuestUpsellSheet(onSignIn = { signInPrompt = null; onSignInToSave() }, onDismiss = { signInPrompt = null }) }
                addTarget?.let {
                    AddToPlaylistDialog(
                        playlists = playlistsForAdd,
                        onPick = { sid -> personalActions.addTargetTo(sid) },
                        onCreateNew = { name -> personalActions.createPlaylistWithTarget(name) },
                        onDismiss = { personalActions.dismissAddToPlaylist() },
                    )
                }
            }
        }
    }
}

/**
 * The "Sign in to save" upgrade sheet a guest sees when they reach for an enrolled-only
 * surface (Want / Follow / a playlist). Guests browse and play freely; saving desired
 * state and personal state (`GuestGate.Surface`) needs an identity, so this offers the
 * QR/device sign-in rather than firing an unauthenticated write.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuestUpsellSheet(onSignIn: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Tokens.surface1, contentColor = Tokens.textPrimary) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Sign in to save", style = MaterialTheme.typography.headlineSmall, color = Tokens.textPrimary)
            Text(
                "You're browsing as a guest — watch, listen and read freely. To keep wants, follows, playlists and your place across devices, sign in with Cruciform or enrol this phone.",
                style = MaterialTheme.typography.bodyMedium, color = Tokens.textMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Sign in to save", onSignIn)
                GhostButton("Not now", onDismiss)
            }
        }
    }
}

/** Switch bottom-bar tabs the Material way: one back stack per tab, restored on return. */
private fun NavHostController.navigateTab(route: Route) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
