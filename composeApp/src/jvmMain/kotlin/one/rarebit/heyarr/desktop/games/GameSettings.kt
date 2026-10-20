package one.rarebit.heyarr.desktop.games

import one.rarebit.heyarr.core.mcp.JsonWrite
import one.rarebit.heyarr.core.net.JsonScan
import java.io.File

/**
 * How one game is launched. Every field has a default that runs an ordinary GOG game, so a
 * person who never opens the settings still gets a working launch.
 */
data class GameSettings(
    /** A Proton build for umu (`GE-Proton`, or a path). Null lets umu pick its default. */
    val protonPath: String? = null,
    /** The umu database id for per-game fixes; [GamePlan.DEFAULT_UMU_GAME_ID] means none. */
    val umuGameId: String = GamePlan.DEFAULT_UMU_GAME_ID,
    /** A program to run instead of GOG's primary play task, relative to the install. */
    val exeOverride: String? = null,
    /** Arguments added after the play task's own. */
    val extraArgs: String = "",
    val gamemode: Boolean = false,
    val mangohud: Boolean = false,
    val gamescope: Boolean = false,
    /** Arguments for gamescope, before its `--`. */
    val gamescopeArgs: String = "-f",
)

/** Where per-game settings live. A seam, so the UI is testable against a fake. */
interface GameSettingsStore {
    fun load(workId: String): GameSettings

    fun save(workId: String, settings: GameSettings)
}

/** A [GameSettingsStore] for tests and previews. */
class InMemoryGameSettingsStore : GameSettingsStore {
    private val byWork = mutableMapOf<String, GameSettings>()

    override fun load(workId: String): GameSettings = byWork[workId] ?: GameSettings()

    override fun save(workId: String, settings: GameSettings) {
        byWork[workId] = settings
    }
}

/**
 * One small JSON file per game, beside its install: `<games>/<workId>/settings.json`.
 *
 * Deliberately NOT in the app's `config.json`: that file holds the bearer token and is
 * rewritten whole on every save, and a launcher toggle has no business touching it. Read
 * tolerantly — a missing or hand-damaged file is the defaults, never a crash.
 */
class FileGameSettingsStore(private val paths: GamePaths) : GameSettingsStore {

    override fun load(workId: String): GameSettings {
        val f = paths.settingsFile(workId)
        val raw = runCatching { f.readText() }.getOrNull()?.replace('\n', ' ') ?: return GameSettings()
        val d = GameSettings()
        return GameSettings(
            protonPath = JsonScan.stringField(raw, "proton_path")?.ifBlank { null },
            umuGameId = JsonScan.stringField(raw, "umu_game_id") ?: d.umuGameId,
            exeOverride = JsonScan.stringField(raw, "exe_override")?.ifBlank { null },
            extraArgs = JsonScan.stringField(raw, "extra_args") ?: d.extraArgs,
            gamemode = JsonScan.boolField(raw, "gamemode") ?: d.gamemode,
            mangohud = JsonScan.boolField(raw, "mangohud") ?: d.mangohud,
            gamescope = JsonScan.boolField(raw, "gamescope") ?: d.gamescope,
            gamescopeArgs = JsonScan.stringField(raw, "gamescope_args") ?: d.gamescopeArgs,
        )
    }

    override fun save(workId: String, settings: GameSettings) {
        val f = paths.settingsFile(workId)
        f.parentFile.mkdirs()
        val json = JsonWrite.obj(
            linkedMapOf(
                "proton_path" to settings.protonPath.orEmpty(),
                "umu_game_id" to settings.umuGameId,
                "exe_override" to settings.exeOverride.orEmpty(),
                "extra_args" to settings.extraArgs,
                "gamemode" to settings.gamemode,
                "mangohud" to settings.mangohud,
                "gamescope" to settings.gamescope,
                "gamescope_args" to settings.gamescopeArgs,
            ),
        )
        f.writeText(json + "\n")
    }
}

/**
 * The on-disk layout for games: `$XDG_DATA_HOME/heyarr-desktop/games/<workId>/` holds the
 * downloaded `installer/` files, the `install/` tree, the Wine `prefix/` and `settings.json`.
 * One directory per work means removing a game is removing one directory.
 */
class GamePaths(val root: File = defaultRoot()) {
    fun gameDir(workId: String): File = File(root, safeId(workId))

    fun installerDir(workId: String): File = File(gameDir(workId), "installer")

    fun installDir(workId: String): File = File(gameDir(workId), "install")

    fun prefixDir(workId: String): File = File(gameDir(workId), "prefix")

    fun settingsFile(workId: String): File = File(gameDir(workId), "settings.json")

    companion object {
        private val UNSAFE = Regex("[^A-Za-z0-9_-]")

        /**
         * A work id as a directory name. Ids are UUIDs, but they come off the wire, so
         * anything that could climb out of the games root is replaced, never trusted.
         */
        fun safeId(workId: String): String = UNSAFE.replace(workId, "_").ifBlank { "_" }

        fun defaultRoot(): File {
            val data = System.getenv("XDG_DATA_HOME")?.ifBlank { null }
                ?: File(System.getProperty("user.home"), ".local/share").path
            return File(File(data, "heyarr-desktop"), "games")
        }
    }
}
