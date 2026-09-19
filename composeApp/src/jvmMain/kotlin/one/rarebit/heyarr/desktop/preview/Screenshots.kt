package one.rarebit.heyarr.desktop.preview

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import one.rarebit.heyarr.desktop.open.BlobDownloader
import one.rarebit.heyarr.desktop.open.DownloadResult
import one.rarebit.heyarr.desktop.open.ExternalOpener
import one.rarebit.heyarr.desktop.open.OpenResult
import one.rarebit.heyarr.desktop.playback.PlayResult
import one.rarebit.heyarr.desktop.playback.Player
import one.rarebit.heyarr.desktop.settings.DesktopConfig
import one.rarebit.heyarr.desktop.settings.InMemorySettingsStore
import one.rarebit.heyarr.desktop.state.ArtworkLoader
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.ui.App
import one.rarebit.heyarr.desktop.ui.Route
import one.rarebit.heyarr.desktop.ui.Experience
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Renders every screen with fixture data to PNG, off-screen, through Compose's
 * `ImageComposeScene` — no display, no network. `./gradlew :composeApp:screenshots`
 * writes them under build/screenshots/. This is how a headless build environment shows
 * the running app; the same fixtures back the JVM tests.
 */
fun main(args: Array<String>) {
    verifyShelfSwitch()
    val out = File(args.firstOrNull() ?: "build/screenshots").apply { mkdirs() }
    val shots = linkedMapOf(
        "00-watch" to Route.Consume(Experience.WATCH),
        "00-listen" to Route.Consume(Experience.LISTEN),
        "00-read" to Route.Consume(Experience.READ),
        "01-home" to Route.Home,
        "02-search-idle" to Route.Search,
        "02-search-results" to Route.Search,
        "02b-discover" to Route.Discover,
        "03-detail-series-watch" to Route.Detail(Fixtures.YELLOWSTONE, MediaType.SERIES, "Yellowstone", from = "Home"),
        "03b-detail-series-curate" to Route.Detail(Fixtures.YELLOWSTONE, MediaType.SERIES, "Yellowstone", from = "Home", curate = true),
        "04-detail-movie-missing" to Route.Detail(Fixtures.SINTEL, MediaType.MOVIE, "Sintel", from = "Missing"),
        "05-detail-book" to Route.Detail("w-piranesi", MediaType.BOOK, "Piranesi", from = "Library"),
        "06-library" to Route.Library,
        "06b-downloads" to Route.Library,
        "05b-player" to Route.Player(Fixtures.YELLOWSTONE, "as-S04E03", Fixtures.HASH, "Yellowstone", "S04E03 All I See Is You", typeHint = MediaType.SERIES, from = "Back"),
        "07-missing" to Route.Missing,
        "08-now-playing" to Route.NowPlaying,
        "09-settings" to Route.Settings,
    )
    val sizes = listOf(1280 to 900)
    for ((name, route) in shots) for ((w, h) in sizes) {
        render(File(out, "$name.png"), w, h, route, query = if (name.endsWith("results")) "dune" else null, downloads = name == "06b-downloads")
    }
    // Guest mode: no token, browsing as an anonymous guest — the "Sign in to save" affordance
    // shows and the personal rails (continue / missing / following) are hidden.
    render(File(out, "01c-home-guest.png"), 1280, 900, Route.Home, guest = true)
    render(File(out, "09c-settings-guest.png"), 1280, 900, Route.Settings, guest = true)
    render(File(out, "14-discover-refusal.png"), 1280, 900, Route.Discover, query = "dune")
    // A small window, to show the compact nav and reflow.
    render(File(out, "09b-connection.png"), 1280, 900, Route.Home, connection = true)
    render(File(out, "10-home-compact.png"), 760, 620, Route.Home)
    render(File(out, "11-search-compact.png"), 760, 620, Route.Search, query = "yellow")
    render(File(out, "12-read-with-audio.png"), 1280, 900, Route.Consume(Experience.READ), audio = true)
    render(File(out, "13-audio-compact.png"), 760, 620, Route.Consume(Experience.READ), audio = true)
    println("wrote ${out.listFiles()?.size ?: 0} screenshots to $out")
}

private fun render(file: File, width: Int, height: Int, route: Route, query: String? = null, connection: Boolean = false, downloads: Boolean = false, guest: Boolean = false, audio: Boolean = false) {
    val transport = FakeHeyarrTransport()
    // A guest carries no token (browses on a trusted network); everyone else pastes one.
    val settings = InMemorySettingsStore(DesktopConfig(baseUrl = "https://heyarr.example.test:7777", bearerToken = if (guest) "" else "heyarr_fixture_token"))
    val art = ArtworkLoader({ "" }, { "" }, fetcher = { PlaceholderArt.bytes(it) })
    ImageComposeScene(width = width, height = height, density = Density(1f)).use { scene ->
        scene.setContent {
            App(
                settings = settings, transport = transport, player = NoPlayer, opener = NoOpener, downloader = NoDownloader,
                initialAudioQueue = if (audio) listOf(Route.Player("w-album", "track-a", Fixtures.HASH, "Fixture album", "First track", MediaType.MUSIC), Route.Player("w-album", "track-b", Fixtures.HASH, "Fixture album", "Next track", MediaType.MUSIC)) else emptyList(),
                initialRoute = route, artworkLoader = art, externalMetadata = one.rarebit.heyarr.desktop.state.ExternalMetadata.NONE, initialQuery = query, initialConnectionSheet = connection, initialLibraryTab = if (downloads) 1 else 0,
            )
        }
        // Let effects (fixture fetches on IO) land: render, wait, re-render until quiet.
        var image = scene.render(System.nanoTime())
        repeat(30) {
            Thread.sleep(150)
            if (scene.hasInvalidations()) image = scene.render(System.nanoTime())
        }
        Thread.sleep(400)
        image = scene.render(System.nanoTime())
        val artPath = "/api/v1/blobs/${Fixtures.HASH}/content"
        System.err.println("  artwork cached for fixture hash: ${art.peek(artPath) != null}")
        val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("encode failed")
        file.writeBytes(png.bytes)
        println("rendered ${file.name} (${width}x$height, ${route})")
    }
}

private object NoPlayer : Player {
    override fun play(baseUrl: String, blobHash: String, token: String): PlayResult = PlayResult.Failed("no player in preview")
}

private object NoOpener : ExternalOpener {
    override fun open(file: File): OpenResult = OpenResult.Failed("no opener in preview")
}

private object NoDownloader : BlobDownloader {
    override fun download(baseUrl: String, blobHash: String, token: String, ext: String): DownloadResult = DownloadResult.Failed("no download in preview")
}

/** Exercises a reused composition: initial-route screenshots alone cannot catch a stale effect key. */
private fun verifyShelfSwitch() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = InMemorySettingsStore(DesktopConfig(baseUrl = "https://heyarr.example.test:7777", externalMetadata = false))
    val session = one.rarebit.heyarr.desktop.state.AppSession(
        settings, FakeHeyarrTransport(), NoPlayer,
        one.rarebit.heyarr.desktop.open.OpenExternally(NoDownloader, NoOpener), scope,
        ArtworkLoader({ "" }, { "" }, fetcher = { PlaceholderArt.bytes(it) }),
        one.rarebit.heyarr.desktop.state.ExternalMetadata.NONE,
    )
    val selected = mutableStateOf(Experience.WATCH)
    val states = Experience.entries.associateWith { one.rarebit.heyarr.desktop.ui.screens.LibraryState() }
    try {
        ImageComposeScene(width = 1280, height = 900, density = Density(1f)).use { scene ->
            scene.setContent {
                one.rarebit.heyarr.desktop.theme.HeyarrTheme {
                    one.rarebit.heyarr.desktop.ui.screens.LibraryScreen(
                        session, states.getValue(selected.value), {}, { _, _ -> }, experience = selected.value,
                    )
                }
            }
            for (experience in Experience.entries + Experience.WATCH) {
                selected.value = experience
                val state = states.getValue(experience)
                // Pump the real composition while the fixture request completes on IO.
                for (attempt in 0 until 100) {
                    scene.render(System.nanoTime())
                    if (state.works != null) break
                    Thread.sleep(20)
                }
                check(!state.works.isNullOrEmpty()) { "Shelf $experience did not load after navigation: ${state.error}" }
            }
        }
    } finally { scope.cancel() }
    println("verified Watch → Listen → Read → Watch in one composition")
}
