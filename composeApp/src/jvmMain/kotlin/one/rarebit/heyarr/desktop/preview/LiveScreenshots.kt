package one.rarebit.heyarr.desktop.preview

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.net.JdkHttpTransport
import one.rarebit.heyarr.desktop.open.BlobDownloader
import one.rarebit.heyarr.desktop.open.DownloadResult
import one.rarebit.heyarr.desktop.open.ExternalOpener
import one.rarebit.heyarr.desktop.open.OpenResult
import one.rarebit.heyarr.desktop.playback.PlayResult
import one.rarebit.heyarr.desktop.playback.Player
import one.rarebit.heyarr.desktop.settings.DesktopConfig
import one.rarebit.heyarr.desktop.settings.InMemorySettingsStore
import one.rarebit.heyarr.desktop.state.ArtworkLoader
import one.rarebit.heyarr.desktop.state.DesktopExternalMetadata
import one.rarebit.heyarr.desktop.ui.App
import one.rarebit.heyarr.desktop.ui.Experience
import one.rarebit.heyarr.desktop.ui.Route
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Renders every screen off-screen against a REAL node, so a stock-take can see what the
 * app shows with live data instead of the fixture matrix [Screenshots] draws. Same
 * `ImageComposeScene` path (no display), but the real [JdkHttpTransport], the real
 * artwork pipeline and whatever the node answers.
 *
 * `./gradlew :composeApp:liveScreenshots` — reads:
 *
 * - `HEYARR_BASE_URL` (required): the node, e.g. `https://heyarr.example.test:7777`.
 * - `HEYARR_TOKEN`: a bearer token; blank renders as a guest.
 * - `HEYARR_DETAIL`: comma-separated `type:workId[:title]` entries to add a Detail page
 *   for (`series:<uuid>:Yellowstone,movie:<uuid>,book:<uuid>`).
 * - `HEYARR_SETTLE_MS` (default 12000): how long to let network effects land per screen.
 * - `HEYARR_EXTERNAL_METADATA=1`: also hit the public cover/synopsis sources.
 *
 * Nothing here is written to the user's config or caches: settings are in memory and the
 * artwork cache lives under the output directory.
 */
@Suppress("MagicNumber")
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/live-screenshots").apply { mkdirs() }
    val baseUrl = System.getenv("HEYARR_BASE_URL")?.trim()?.takeIf { it.isNotBlank() }
        ?: error("set HEYARR_BASE_URL to the node to render against")
    val token = System.getenv("HEYARR_TOKEN")?.trim().orEmpty()
    val settleMs = System.getenv("HEYARR_SETTLE_MS")?.toLongOrNull() ?: 12_000L
    val externalMetadata = System.getenv("HEYARR_EXTERNAL_METADATA") == "1"

    val details = System.getenv("HEYARR_DETAIL").orEmpty().split(',').filter { it.isNotBlank() }.map { spec ->
        val parts = spec.split(':', limit = 3)
        val type = MediaType.entries.firstOrNull { it.name.equals(parts[0], ignoreCase = true) } ?: MediaType.UNKNOWN
        Triple(type, parts.getOrElse(1) { "" }, parts.getOrNull(2))
    }

    val settings = InMemorySettingsStore(
        DesktopConfig(baseUrl = baseUrl, bearerToken = token, uiScale = 1f, externalMetadata = externalMetadata),
    )
    val art = ArtworkLoader({ baseUrl }, { token }, cacheDir = File(out, ".art-cache"))
    val external = if (externalMetadata) null else DesktopExternalMetadata.NONE

    val shots = buildList {
        add(Shot("00-watch", Route.Consume(Experience.WATCH)))
        add(Shot("00-listen", Route.Consume(Experience.LISTEN)))
        add(Shot("00-read", Route.Consume(Experience.READ)))
        add(Shot("01-home", Route.Home))
        add(Shot("02-search-idle", Route.Search))
        add(Shot("02-search-results", Route.Search, query = "alien"))
        add(Shot("02b-discover", Route.Discover, query = "dune"))
        for ((type, id, title) in details) {
            add(Shot("03-detail-${type.name.lowercase()}", Route.Detail(id, type, title, from = "Library")))
            add(
                Shot(
                    "03b-detail-${type.name.lowercase()}-curate",
                    Route.Detail(id, type, title, from = "Library", curate = true),
                ),
            )
        }
        add(Shot("06-library", Route.Library))
        add(Shot("07-missing", Route.Missing))
        add(Shot("08-now-playing", Route.NowPlaying))
        add(Shot("09-settings", Route.Settings))
        add(Shot("10-home-compact", Route.Home, 760, 620))
        add(Shot("11-search-compact", Route.Search, 760, 620, query = "alien"))
    }

    for (shot in shots) renderShot(out, shot, settings, art, external, settleMs)
}

private data class Shot(
    val name: String,
    val route: Route,
    val w: Int = 1280,
    val h: Int = 900,
    val query: String? = null,
)

@Suppress("MagicNumber", "LongParameterList")
private fun renderShot(
    out: File,
    shot: Shot,
    settings: InMemorySettingsStore,
    art: ArtworkLoader,
    external: one.rarebit.heyarr.core.state.ExternalMetadata?,
    settleMs: Long,
) {
    val started = System.currentTimeMillis()
    ImageComposeScene(width = shot.w, height = shot.h, density = Density(1f)).use { scene ->
        scene.setContent {
            App(
                settings = settings,
                transport = JdkHttpTransport(),
                player = LiveNoPlayer,
                opener = LiveNoOpener,
                downloader = LiveNoDownloader,
                initialRoute = shot.route,
                artworkLoader = art,
                externalMetadata = external,
                initialQuery = shot.query,
            )
        }
        // Pump until the scene has been quiet for a while or the budget runs out: real
        // requests land on IO at their own pace, and artwork arrives after the list does.
        var image = scene.render(System.nanoTime())
        var quiet = 0
        val deadline = started + settleMs
        while (System.currentTimeMillis() < deadline && quiet < 12) {
            Thread.sleep(150)
            if (scene.hasInvalidations()) {
                image = scene.render(System.nanoTime())
                quiet = 0
            } else {
                quiet++
            }
        }
        Thread.sleep(300)
        image = scene.render(System.nanoTime())
        val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("encode failed")
        File(out, "${shot.name}.png").writeBytes(png.bytes)
        println(
            "rendered ${shot.name}.png (${shot.w}x${shot.h}, ${shot.route}) " +
                "in ${System.currentTimeMillis() - started} ms",
        )
    }
}

private object LiveNoPlayer : Player {
    override fun play(baseUrl: String, blobHash: String, token: String): PlayResult =
        PlayResult.Failed("no player in a live screenshot")
}

private object LiveNoOpener : ExternalOpener {
    override fun open(file: File): OpenResult = OpenResult.Failed("no opener in a live screenshot")
}

private object LiveNoDownloader : BlobDownloader {
    override fun download(baseUrl: String, blobHash: String, token: String, ext: String): DownloadResult =
        DownloadResult.Failed("no download in a live screenshot")
}
