package one.rarebit.heyarr.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.open.BlobDownloader
import one.rarebit.heyarr.desktop.open.DownloadResult
import one.rarebit.heyarr.desktop.open.ExternalOpener
import one.rarebit.heyarr.desktop.open.OpenResult
import one.rarebit.heyarr.desktop.playback.PlayResult
import one.rarebit.heyarr.desktop.playback.Player
import one.rarebit.heyarr.desktop.preview.FakeHeyarrTransport
import one.rarebit.heyarr.desktop.preview.Fixtures
import one.rarebit.heyarr.desktop.settings.DesktopConfig
import one.rarebit.heyarr.desktop.settings.InMemorySettingsStore
import one.rarebit.heyarr.desktop.state.ArtworkLoader
import one.rarebit.heyarr.desktop.state.DesktopExternalMetadata
import one.rarebit.heyarr.desktop.theme.DesktopPalette
import one.rarebit.heyarr.desktop.theme.WalPalette
import one.rarebit.heyarr.desktop.ui.App
import one.rarebit.heyarr.desktop.ui.Route
import one.rarebit.heyarr.ui.theme.ActivePalette
import one.rarebit.heyarr.ui.theme.Appearance
import one.rarebit.heyarr.ui.theme.Palette
import org.jetbrains.skia.Bitmap
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The running app follows the desktop: the real [App], rendered off-screen with fixture data,
 * re-colours when the theme file under it changes, and keeps the player on a dark stage. The
 * theme is fed through a pywal file because that source needs no desktop session.
 */
class AppPaletteTest {

    @AfterTest
    fun restore() {
        ActivePalette.current = Palette.Archive
    }

    private val tokyoNight = """{"special": {"background": "#1a1b26", "foreground": "#a9b1d6"},
        |"colors": {"color4": "#7aa2f7"}}
    """.trimMargin()
    private val latte = """{"special": {"background": "#eff1f5", "foreground": "#4c4f69"},
        |"colors": {"color4": "#1e66f5"}}
    """.trimMargin()

    private object NoPlayer : Player {
        override fun play(baseUrl: String, blobHash: String, token: String) = PlayResult.Failed("no player in test")
    }

    private object NoOpener : ExternalOpener {
        override fun open(file: File) = OpenResult.Failed("no opener in test")
    }

    private object NoDownloader : BlobDownloader {
        override fun download(baseUrl: String, blobHash: String, token: String, ext: String) =
            DownloadResult.Failed("no download in test")
    }

    private fun scene(colors: Path, route: Route): ImageComposeScene =
        ImageComposeScene(width = 640, height = 400, density = Density(1f)).apply {
            setContent {
                App(
                    settings = InMemorySettingsStore(
                        DesktopConfig(baseUrl = "https://heyarr.example.test:7777", uiScale = 1f),
                    ),
                    transport = FakeHeyarrTransport(),
                    player = NoPlayer,
                    opener = NoOpener,
                    downloader = NoDownloader,
                    initialRoute = route,
                    artworkLoader = ArtworkLoader.NONE,
                    externalMetadata = DesktopExternalMetadata.NONE,
                    nanoClock = { 0L },
                    initialAppearance = Appearance(reduceMotion = true),
                    desktopPalette = DesktopPalette(listOf(WalPalette(colors))),
                )
            }
        }

    /** The colour covering most of the frame: the ground, whatever the screen draws on it. */
    private fun ImageComposeScene.groundArgb(): Int {
        val bitmap = Bitmap.makeFromImage(render(System.nanoTime()))
        val counts = HashMap<Int, Int>()
        for (x in 0 until bitmap.width step 4) {
            for (y in 0 until bitmap.height step 4) counts.merge(bitmap.getColor(x, y), 1, Int::plus)
        }
        return counts.maxBy { it.value }.key
    }

    /** Render frames until [done] holds: the watcher and the derivation run off the frame clock. */
    private fun ImageComposeScene.until(what: String, done: () -> Boolean) {
        repeat(WAIT_FRAMES) {
            render(System.nanoTime())
            if (done()) return
            Thread.sleep(FRAME_MS)
        }
        fail("timed out waiting for $what")
    }

    private fun Color.argb() = toArgb()

    @Test
    fun aThemeSwitchRecoloursTheRunningApp() {
        val colors = Files.createTempDirectory("heyarr-wal").resolve("colors.json")
        colors.writeText(tokyoNight)
        scene(colors, Route.Settings).use { scene ->
            scene.until("the Tokyo Night ground") { ActivePalette.current.bgBase.argb() == 0xFF1A1B26.toInt() }
            assertEquals(0xFF1A1B26.toInt(), scene.groundArgb(), "the app is drawn on Tokyo Night's ground")

            // Switch the desktop to a light theme while the app runs.
            colors.writeText(latte)
            scene.until("the Latte ground") { ActivePalette.current.bgBase.argb() == 0xFFEFF1F5.toInt() }
            assertFalse(ActivePalette.current.dark)
            assertEquals(0xFFEFF1F5.toInt(), scene.groundArgb(), "the same app, now on Latte's paper")
        }
    }

    @Test
    fun thePlayerIsADarkStageUnderALightTheme() {
        val colors = Files.createTempDirectory("heyarr-wal").resolve("colors.json")
        colors.writeText(latte)
        val player = Route.Player(
            Fixtures.YELLOWSTONE,
            "as-S04E03",
            Fixtures.HASH,
            "Yellowstone",
            "S04E03",
            typeHint = MediaType.SERIES,
        )
        scene(colors, player).use { scene ->
            // The theme is light, yet while the player is up the palette in force is its dark
            // stage: Archive's forest in Latte's blue.
            scene.until("Latte's accent") { ActivePalette.current.accent != Palette.Archive.accent }
            assertTrue(ActivePalette.current.dark, "the stage is dark")
            assertEquals(Palette.Archive.bgBase, ActivePalette.current.bgBase, "on Archive's forest")
        }
    }

    private companion object {
        const val WAIT_FRAMES = 200
        const val FRAME_MS = 25L
    }
}
