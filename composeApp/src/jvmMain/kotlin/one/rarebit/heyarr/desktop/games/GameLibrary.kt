package one.rarebit.heyarr.desktop.games

import java.io.File

/** The outcome of an install or a launch: a value the UI renders, never a thrown exception. */
sealed interface GameResult {
    data object Done : GameResult

    data class Failed(val message: String) : GameResult
}

/** Where an installed game's program is: the directory its play task is relative to, and the task. */
data class LaunchTarget(val base: File, val task: PlayTask)

/**
 * Installs games from the node onto this machine and starts them (heyarr-core ADR-0118/0119).
 *
 * Install is download → unpack → find the program → mark installed; nothing is run under
 * Wine to install, so a GOG installer's GUI never appears and no prefix is touched until
 * the first launch. Launch is the umu line [GamePlan.launchCommand] builds, started
 * detached. Every step reports a UI-safe reason when it cannot proceed, naming the tool
 * to install when that is the reason.
 */
class GameLibrary(
    private val paths: GamePaths = GamePaths(),
    private val runner: ProcessRunner = JdkProcessRunner(),
    val settings: GameSettingsStore = FileGameSettingsStore(paths),
    private val arch: String = System.getProperty("os.arch").orEmpty(),
) {

    /** Whether [workId] has been installed here. */
    fun isInstalled(workId: String): Boolean = marker(workId).isFile

    /**
     * Download [files] (one edition's installer set) and install the game. [onStep] hears
     * what is happening, for a status line. Re-running reuses installer files already
     * downloaded at their full size, and reinstalls into a fresh directory.
     */
    fun install(
        workId: String,
        files: List<GameFile>,
        fetcher: GameFileFetcher,
        onStep: (String) -> Unit = {},
    ): GameResult {
        val kind = GamePlan.kindOf(files)
        val local = files.map { it.copy(name = safeName(it.name).orEmpty()) }
        val problem = checkInstallable(kind, files, local)
            ?: download(workId, local, fetcher, onStep)
            ?: unpack(workId, kind, local, onStep)
        if (problem != null) return GameResult.Failed(problem)
        marker(workId).writeText(kind.name + "\n")
        return GameResult.Done
    }

    private fun checkInstallable(kind: InstallerKind, files: List<GameFile>, local: List<GameFile>): String? {
        val unsafe = files.zip(local).firstOrNull { (_, l) -> l.name.isEmpty() }?.first?.name
        return when {
            kind == InstallerKind.UNKNOWN ->
                "No installer here this launcher can install (a GOG setup .exe, a GOG .sh, or a .zip)."

            unsafe != null -> "Unsafe file name: $unsafe."

            !runner.available(toolFor(kind)) -> "${toolFor(kind)} is not installed. Install it to unpack this game."

            else -> null
        }
    }

    /** Fetch every file not already held at its full size; the first failure, or null. */
    private fun download(
        workId: String,
        local: List<GameFile>,
        fetcher: GameFileFetcher,
        onStep: (String) -> Unit,
    ): String? {
        val installerDir = paths.installerDir(workId).apply { mkdirs() }
        return local.withIndex().firstNotNullOfOrNull { (i, f) ->
            val target = File(installerDir, f.name)
            // Reused only when it is the same blob: an installer re-uploaded under the same
            // name and size is a different file, and its hash says so where its size cannot.
            val stamp = File(installerDir, f.name + BLOB_STAMP)
            val held = target.isFile && f.sizeBytes != null && target.length() == f.sizeBytes &&
                runCatching { stamp.readText().trim() }.getOrNull() == f.blobHash
            if (held) {
                null
            } else {
                onStep("Downloading ${i + 1} of ${local.size}: ${f.name}")
                stamp.delete()
                fetcher.fetch(f, target) ?: run {
                    stamp.writeText(f.blobHash + "\n")
                    null
                }
            }
        }
    }

    /** Unpack into a fresh install directory; the failure, or null. */
    private fun unpack(workId: String, kind: InstallerKind, local: List<GameFile>, onStep: (String) -> Unit): String? {
        val installDir = paths.installDir(workId)
        // The old tree is about to go: until the new one is whole, this is not an
        // installed game, so a failed reinstall never offers Play over an empty tree.
        marker(workId).delete()
        installDir.deleteRecursively()
        installDir.mkdirs()
        onStep("Unpacking…")
        val cmd = GamePlan.installCommand(local, paths.installerDir(workId), installDir)
            ?: return "No way to install this game."
        val exit = runner.run(cmd)
        return if (GamePlan.installSucceeded(
                kind,
                exit,
            )
        ) {
            null
        } else {
            "${toolFor(kind)} could not unpack the installer (exit $exit)."
        }
    }

    /** Start an installed game with its saved settings. */
    fun launch(workId: String): GameResult {
        val kind = installedKind(workId)
        val settings = settings.load(workId)
        val target = kind?.let { launchTarget(it, paths.installDir(workId), settings) }
        val problem = when {
            kind == null -> "This game is not installed here yet."
            target == null -> "No program to start was found. Set one in this game's settings."
            else -> toolsFor(target, settings).firstOrNull { !runner.available(it) }?.let { "$it is not installed." }
        }
        if (problem != null || kind == null || target == null) return GameResult.Failed(problem.orEmpty())
        val prefix = paths.prefixDir(workId).apply { if (GamePlan.isWindowsProgram(target.task.path)) mkdirs() }
        return try {
            runner.start(GamePlan.launchCommand(kind, target, settings, prefix, arch))
            GameResult.Done
        } catch (e: java.io.IOException) {
            GameResult.Failed("The game could not be started (${e.javaClass.simpleName}).")
        }
    }

    /** The programs a launch needs: umu (or the microVM on aarch64), and any wrapper turned on. */
    private fun toolsFor(target: LaunchTarget, settings: GameSettings): List<String> = if (GamePlan.isArm(arch)) {
        listOf("muvm")
    } else {
        listOfNotNull(
            GamePlan.UMU_RUN.takeIf { GamePlan.isWindowsProgram(target.task.path) },
            "gamemoderun".takeIf { settings.gamemode },
            "gamescope".takeIf { settings.gamescope },
            "mangohud".takeIf { settings.mangohud },
        )
    }

    /** What kind of installation [workId] is, from its marker. */
    fun installedKind(workId: String): InstallerKind? =
        runCatching { InstallerKind.valueOf(marker(workId).readText().trim()) }.getOrNull()

    private fun marker(workId: String): File = File(paths.gameDir(workId), "installed")

    companion object {
        /** The sidecar naming which blob a downloaded installer file is. */
        private const val BLOB_STAMP = ".blob"

        /** How deep to look for GOG's `goggame-*.info` under an unpacked tree. */
        private const val INFO_SEARCH_DEPTH = 4

        /**
         * The program to start. An override in settings wins. A GOG game names its own
         * primary program in `goggame-<id>.info`; a native GOG Linux game starts with
         * the `start.sh` GOG puts in `data/noarch`.
         */
        fun launchTarget(kind: InstallerKind, installDir: File, settings: GameSettings): LaunchTarget? {
            val override = settings.exeOverride?.ifBlank { null }
            val noarch = File(installDir, "data/noarch")
            return when {
                override != null -> LaunchTarget(installDir, PlayTask(override))

                kind == InstallerKind.GOG_LINUX && File(noarch, "start.sh").isFile ->
                    LaunchTarget(noarch, PlayTask("start.sh"))

                else -> gogInfoTarget(installDir) ?: guessTarget(installDir)
            }
        }

        /** Files that are never the game: uninstallers, runtime installers, crash reporters. */
        private val NOT_THE_GAME = Regex(
            "(?i)^(unins\\d*|setup.*|.*redist.*|dxsetup|dotnet.*|.*crash.*|.*installer.*|.*launcher.*)" +
                "(\\.[a-z0-9_]+)?$",
        )

        /**
         * The program a plain archive most plausibly starts with, when it names none: a
         * `start.sh`, else the largest Windows executable that is not an installer or a
         * helper, else a lone native executable. Shallowest first, as games put their
         * program near the top.
         */
        private fun guessTarget(installDir: File): LaunchTarget? {
            val files = installDir.walkTopDown().maxDepth(INFO_SEARCH_DEPTH).filter { it.isFile }.toList()
            val pick = files.filter { it.name == "start.sh" }.minByOrNull { it.path.length }
                ?: files.filter { GamePlan.isWindowsProgram(it.name) && !NOT_THE_GAME.matches(it.name) }
                    .maxByOrNull { it.length() }
                ?: files.filter { isNativeProgram(it) && !NOT_THE_GAME.matches(it.name) }.maxByOrNull { it.length() }
            return pick?.let { LaunchTarget(installDir, PlayTask(it.relativeTo(installDir).path)) }
        }

        /** The play task GOG's `goggame-*.info` names, relative to the directory it sits in. */
        private fun gogInfoTarget(installDir: File): LaunchTarget? {
            val info = installDir.walkTopDown().maxDepth(INFO_SEARCH_DEPTH)
                .firstOrNull { it.isFile && it.name.startsWith("goggame-") && it.name.endsWith(".info") }
            val task = info?.let { GamePlan.primaryPlayTask(it.readText()) }
            return if (info != null && task != null) LaunchTarget(info.parentFile, task) else null
        }

        /** Native suffixes games ship their program under, beside the bare executable name. */
        private val NATIVE_SUFFIXES = setOf("x86_64", "x86", "amd64", "aarch64", "arm64", "run", "bin", "sh")

        /**
         * An executable file that is a program rather than a library or data: no extension,
         * or one of the suffixes games conventionally give their Linux binary.
         */
        private fun isNativeProgram(f: File): Boolean {
            val ext = f.name.substringAfterLast('.', "")
            return f.canExecute() && (ext.isEmpty() || ext.lowercase() in NATIVE_SUFFIXES)
        }

        private fun toolFor(kind: InstallerKind): String =
            if (kind == InstallerKind.GOG_WINDOWS) GamePlan.INNOEXTRACT else GamePlan.UNZIP

        /** A server-supplied file name as a bare local name, or null when it is not one. */
        fun safeName(name: String): String? {
            val base = name.replace('\\', '/').substringAfterLast('/').trim()
            return base.takeIf { it.isNotEmpty() && it != "." && it != ".." }
        }
    }
}
