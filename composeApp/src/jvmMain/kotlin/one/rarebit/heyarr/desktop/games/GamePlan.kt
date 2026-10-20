package one.rarebit.heyarr.desktop.games

import one.rarebit.heyarr.core.net.JsonScan
import java.io.File

/**
 * One process to run: its argv (one slot per argument, never a shell line), the
 * environment it adds, and where it runs.
 */
data class Command(val argv: List<String>, val env: Map<String, String> = emptyMap(), val cwd: File? = null)

/** One installer file a game's edition holds on the node. */
data class GameFile(val name: String, val blobHash: String, val sizeBytes: Long?)

/** What shape a game's installer files are, which decides how it is installed and run. */
enum class InstallerKind {
    /** A GOG offline installer for Windows: `setup_*.exe` and its `-N.bin` parts. Run under umu. */
    GOG_WINDOWS,

    /** A GOG native Linux installer: a MojoSetup `.sh` with the game zipped inside. */
    GOG_LINUX,

    /** A plain `.zip` of a DRM-free game, unpacked as-is. */
    ZIP,

    /** Nothing this launcher knows how to install. */
    UNKNOWN,
}

/** The program a game is started with, relative to its install directory. */
data class PlayTask(val path: String, val workingDir: String? = null, val arguments: String = "")

/**
 * The pure half of the launcher (heyarr-core ADR-0118/0119; the Faugus model: a thin
 * wrapper over `umu-run`, not a Lutris rewrite). Every decision — which installer, which
 * command installs it, which program is the game, the exact launch line — is made here
 * from data, so it is asserted in a JVM test without a game, a prefix or a process.
 */
object GamePlan {

    /** The installer tools [installCommand] may need, for an up-front "install this" message. */
    const val INNOEXTRACT = "innoextract"
    const val UNZIP = "unzip"
    const val UMU_RUN = "umu-run"

    /** `unzip` exits 1 on warnings — a MojoSetup `.sh` always warns about its shell prefix. */
    const val UNZIP_WARNING_EXIT = 1

    private val GOG_SETUP = Regex("""(?i)^setup_.+\.exe$""")

    /** What [files] are. A GOG Windows set wins over a stray `.sh` or `.zip` beside it. */
    fun kindOf(files: List<GameFile>): InstallerKind {
        val names = files.map { it.name }
        return when {
            names.any { GOG_SETUP.matches(it) } -> InstallerKind.GOG_WINDOWS
            names.any { it.endsWith(".sh", ignoreCase = true) } -> InstallerKind.GOG_LINUX
            names.any { it.endsWith(".zip", ignoreCase = true) } -> InstallerKind.ZIP
            else -> InstallerKind.UNKNOWN
        }
    }

    /**
     * The command that installs [files] (already downloaded into [installerDir]) into
     * [installDir], or null when [kindOf] knows no way. A GOG Windows installer is
     * unpacked with `innoextract --gog` (which reads the `-N.bin` parts beside the
     * `.exe`) rather than run under Wine: no prefix, no GUI, no installer quirks.
     */
    fun installCommand(files: List<GameFile>, installerDir: File, installDir: File): Command? {
        val dir = installDir.absolutePath
        return when (kindOf(files)) {
            InstallerKind.GOG_WINDOWS -> {
                val setup = files.first { GOG_SETUP.matches(it.name) }
                Command(
                    listOf(INNOEXTRACT, "--gog", "--silent", "-d", dir, File(installerDir, setup.name).absolutePath),
                )
            }

            InstallerKind.GOG_LINUX -> {
                val sh = files.first { it.name.endsWith(".sh", ignoreCase = true) }
                // The game is the archive's data/noarch tree; the MojoSetup installer
                // around it is never run.
                Command(
                    listOf(UNZIP, "-o", "-qq", File(installerDir, sh.name).absolutePath, "data/noarch/*", "-d", dir),
                )
            }

            InstallerKind.ZIP -> {
                val zip = files.first { it.name.endsWith(".zip", ignoreCase = true) }
                Command(listOf(UNZIP, "-o", "-qq", File(installerDir, zip.name).absolutePath, "-d", dir))
            }

            InstallerKind.UNKNOWN -> null
        }
    }

    /**
     * Which edition's installer set to install, from a work's editions (id → files). A
     * native Linux build is preferred on x86 (no Wine at all); on aarch64 everything runs
     * in the x86 microVM, so the Windows build — the one umu and Proton are tuned for —
     * is preferred there and when there is no native one. A zip is the last resort.
     */
    fun chooseEdition(editions: Map<String, List<GameFile>>, arch: String): List<GameFile>? {
        val order = if (isArm(arch)) {
            listOf(InstallerKind.GOG_WINDOWS, InstallerKind.GOG_LINUX, InstallerKind.ZIP)
        } else {
            listOf(InstallerKind.GOG_LINUX, InstallerKind.GOG_WINDOWS, InstallerKind.ZIP)
        }
        val byKind = editions.values.filter { it.isNotEmpty() }.groupBy { kindOf(it) }
        return order.firstNotNullOfOrNull { byKind[it]?.firstOrNull() }
    }

    /** Whether an install command's exit code means it worked. */
    fun installSucceeded(kind: InstallerKind, exitCode: Int): Boolean = when (kind) {
        InstallerKind.GOG_LINUX, InstallerKind.ZIP -> exitCode == 0 || exitCode == UNZIP_WARNING_EXIT
        else -> exitCode == 0
    }

    /**
     * The game's primary program from a GOG `goggame-*.info` file: the `playTasks` entry
     * marked `isPrimary` (else the first file task). GOG writes this file for exactly
     * this purpose, so it beats guessing at which of a game's executables is the game.
     */
    fun primaryPlayTask(info: String): PlayTask? {
        // JsonScan is a field scanner over one line; JSON strings cannot hold a raw
        // newline, so folding them is lossless.
        val flat = info.replace('\n', ' ').replace('\r', ' ')
        val tasks = JsonScan.objectsOf(flat, listOf("playTasks"))
            .filter { (JsonScan.stringField(it, "type") ?: "FileTask") == "FileTask" }
            .filter { !JsonScan.stringField(it, "path").isNullOrBlank() }
        val task = tasks.firstOrNull { JsonScan.boolField(it, "isPrimary") == true } ?: tasks.firstOrNull()
        return task?.let {
            PlayTask(
                path = JsonScan.stringField(it, "path")!!.replace('\\', '/'),
                workingDir = JsonScan.stringField(it, "workingDir")?.replace('\\', '/')?.ifBlank { null },
                arguments = JsonScan.stringField(it, "arguments").orEmpty(),
            )
        }
    }

    /**
     * The launch line for an installed game.
     *
     * A Windows game runs as `umu-run <exe> <args>`, with the per-game prefix, the store
     * and the chosen Proton in the environment; a native one runs its own `start.sh`. The
     * optional wrappers stack outermost-first the way their own docs nest them:
     * `gamemoderun gamescope <args> -- mangohud <game>`. On aarch64 the whole line runs
     * inside an x86 microVM (`muvm -- FEXBash -c '<line>'`); the environment then rides in
     * the line itself, since the VM does not inherit this process's.
     */
    fun launchCommand(
        kind: InstallerKind,
        target: LaunchTarget,
        settings: GameSettings,
        prefixDir: File,
        arch: String,
    ): Command {
        val task = target.task
        val program = File(target.base, settings.exeOverride?.ifBlank { null } ?: task.path)
        val cwd = (settings.exeOverride?.ifBlank { null }?.let { program.parentFile })
            ?: task.workingDir?.let { File(target.base, it) }
            ?: program.parentFile
        val args = ShellArgs.split(
            listOf(task.arguments, settings.extraArgs).filter {
                it.isNotBlank()
            }.joinToString(" "),
        )

        val windows = isWindowsProgram(program.name)
        val game = when {
            windows -> listOf(UMU_RUN, program.absolutePath) + args

            // Through bash, not exec: an archive made on Windows, or unpacked without
            // mode bits, leaves start.sh without its executable bit, and GOG's start
            // scripts are bash scripts.
            program.name.endsWith(".sh", ignoreCase = true) -> listOf("bash", program.absolutePath) + args

            else -> listOf(program.absolutePath) + args
        }
        val env = if (windows) wineEnv(kind, settings, prefixDir) else emptyMap()
        val wrapped = wrappers(settings) + game

        if (!isArm(arch)) return Command(wrapped, env, cwd)
        val line = (env.map { (k, v) -> "$k=${ShellArgs.quote(v)}" } + wrapped.map(ShellArgs::quote)).joinToString(" ")
        val inVm = if (env.isEmpty()) line else "env $line"
        return Command(listOf("muvm", "--", "FEXBash", "-c", inVm), emptyMap(), cwd)
    }

    /** umu's environment: the per-game prefix, the fixes id, the store, and a chosen Proton. */
    private fun wineEnv(kind: InstallerKind, settings: GameSettings, prefixDir: File): Map<String, String> = buildMap {
        put("WINEPREFIX", prefixDir.absolutePath)
        put("GAMEID", settings.umuGameId.ifBlank { DEFAULT_UMU_GAME_ID })
        if (kind == InstallerKind.GOG_WINDOWS) put("STORE", "gog")
        settings.protonPath?.ifBlank { null }?.let { put("PROTONPATH", it) }
    }

    /** The wrappers turned on, outermost first, ready for the game to follow. */
    private fun wrappers(settings: GameSettings): List<String> = buildList {
        if (settings.gamemode) add("gamemoderun")
        if (settings.gamescope) {
            add("gamescope")
            addAll(ShellArgs.split(settings.gamescopeArgs))
            add("--")
        }
        if (settings.mangohud) add("mangohud")
    }

    /**
     * Whether a program is a Windows one, and so runs under umu. Decided by the program,
     * not the installer: a `.zip` can hold either a Windows game or a native one.
     */
    fun isWindowsProgram(name: String): Boolean =
        name.endsWith(".exe", ignoreCase = true) || name.endsWith(".bat", ignoreCase = true)

    /** umu's "no per-game fixes" id: a game with no entry in the umu database still runs. */
    const val DEFAULT_UMU_GAME_ID = "0"

    /** Whether [arch] (`os.arch`) needs the x86 microVM. */
    fun isArm(arch: String): Boolean = arch.lowercase().let { it == "aarch64" || it == "arm64" }
}

/** The two shell-shaped jobs a launch line needs: quoting for the aarch64 VM, and splitting a settings field. */
object ShellArgs {
    /**
     * POSIX single-quoting: the one quoting the aarch64 line needs. Safe for any byte but
     * NUL, since nothing inside single quotes is special except the quote itself.
     */
    fun quote(s: String): String =
        if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in SAFE }) s else "'" + s.replace("'", "'\\''") + "'"

    private const val SAFE = "-_./=:,+@%"

    /**
     * Split a settings field into argv slots on whitespace, honouring double quotes, so
     * `-w 1920 -h 1080` or `--config "My Settings.ini"` become what a person meant.
     */
    fun split(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var any = false
        for (c in s) {
            when {
                c == '"' -> {
                    quoted = !quoted
                    any = true
                }

                c.isWhitespace() && !quoted -> {
                    if (any) out.add(cur.toString())
                    cur.clear()
                    any = false
                }

                else -> {
                    cur.append(c)
                    any = true
                }
            }
        }
        if (any) out.add(cur.toString())
        return out
    }
}
