package one.rarebit.heyarr.desktop.theme

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit

/**
 * Where this desktop's colours come from, most specific first. The first source with an
 * answer wins, so an Omarchy session reads Omarchy's full palette even though its portal also
 * answers, and a Plasma session reads its colour scheme before the portal's bare accent.
 *
 * Distributions don't matter here; desktops do. Fedora KDE, Kubuntu and Arch + Plasma all
 * publish through `kdeglobals`; GNOME, COSMIC and most others through the portal; a hand-built
 * Hyprland or sway setup usually through pywal/wallust.
 */
class DesktopPalette(private val sources: List<PaletteSource>) {

    /** The first source's answer, or null (heyarr's own palette). A broken source is skipped, never fatal. */
    fun resolve(): DesktopColors? = sources.firstNotNullOfOrNull { runCatching { it.read() }.getOrNull() }

    /**
     * The current answer, then a fresh one whenever a watched file or the portal changes.
     * Bursts (Omarchy rewrites a dozen files on one theme switch) settle for [SETTLE_MS] and
     * re-resolve once; identical answers are dropped.
     */
    fun changes(): Flow<DesktopColors?> = channelFlow {
        send(resolve())
        val poke = Channel<Unit>(Channel.CONFLATED)
        val watch = watchFiles(sources.flatMap { it.watched }) { poke.trySend(Unit) }
        val monitor = sources.filterIsInstance<PortalPalette>().firstOrNull()?.monitor { poke.trySend(Unit) }
        launch {
            poke.consumeEach {
                delay(SETTLE_MS)
                poke.tryReceive()
                send(resolve())
            }
        }
        awaitClose {
            watch?.close()
            monitor?.close()
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    companion object {
        const val SETTLE_MS = 250L
        private const val POLL_MS = 500L

        /**
         * The source chain for this session. `HEYARR_PALETTE` pins one source (`omarchy`,
         * `kde`, `wal`, `portal`) or none (`archive`); unset or `auto` walks the chain. KDE is
         * only consulted under a Plasma session: other desktops leave a `kdeglobals` behind
         * for Qt apps, and that is not this session's palette.
         */
        fun forThisDesktop(
            env: (String) -> String? = System::getenv,
            home: Path = Path.of(System.getProperty("user.home")),
            portal: PortalBus = GdbusPortalBus(),
        ): DesktopPalette {
            val config = env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of) ?: home.resolve(".config")
            val cache = env("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of) ?: home.resolve(".cache")
            val omarchy = OmarchyPalette.forHome(home)
            val kde = KdePalette(config.resolve("kdeglobals"))
            val wal = WalPalette(cache.resolve("wal/colors.json"))
            val xdg = PortalPalette(portal)
            val plasma = env("XDG_CURRENT_DESKTOP").orEmpty().split(':').any { it.equals("KDE", ignoreCase = true) }
            val sources = when (env("HEYARR_PALETTE")?.trim()?.lowercase()) {
                "archive", "none", "off" -> emptyList()
                "omarchy" -> listOf(omarchy)
                "kde", "plasma" -> listOf(kde)
                "wal", "pywal", "wallust" -> listOf(wal)
                "portal", "xdg" -> listOf(xdg)
                else -> listOfNotNull(omarchy, kde.takeIf { plasma }, wal, xdg)
            }
            return DesktopPalette(sources)
        }

        /**
         * Watch [files] by their directories: every source's file is replaced by a rename (a
         * temp file, or Omarchy's whole theme directory), which a watch on the file itself
         * would lose. A directory that doesn't exist yet is not watched; the portal and the
         * next launch cover a desktop that is set up while heyarr runs.
         */
        private fun watchFiles(files: List<Path>, onChange: () -> Unit): WatchService? {
            val byDir = files.groupBy({ it.parent }, { it.fileName.toString() }).filterKeys { Files.isDirectory(it) }
            val service = byDir.takeIf { it.isNotEmpty() }
                ?.let { runCatching { FileSystems.getDefault().newWatchService() }.getOrNull() }
                ?: return null
            val keys = byDir.mapNotNull { (dir, names) ->
                runCatching {
                    dir.register(
                        service,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE,
                    ) to names.toSet()
                }.getOrNull()
            }.toMap()
            Thread({
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val key = service.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                        val names = keys[key].orEmpty()
                        if (key.pollEvents().any { it.context()?.toString() in names }) onChange()
                        key.reset()
                    }
                } catch (_: ClosedWatchServiceException) {
                    // Closed by the flow's collector going away: the normal end.
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }, "heyarr-palette-watch").apply { isDaemon = true }.start()
            return service
        }
    }
}
