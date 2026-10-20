package one.rarebit.heyarr.desktop.games

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The process seam for games. Installing waits for its tool and reads the exit code;
 * launching hands the game off and returns. Injected so the launcher's orchestration is
 * tested with a recording fake, as the mpv and xdg-open seams are.
 */
interface ProcessRunner {
    /** Run [cmd] to completion and return its exit code. */
    fun run(cmd: Command): Int

    /** Start [cmd] detached; the game outlives the click that started it. */
    fun start(cmd: Command)

    /** Whether [program] resolves to an executable (absolute path, or on `$PATH`). */
    fun available(program: String): Boolean = Which.onPath(program)
}

/** The real [ProcessRunner]: `ProcessBuilder`, the command's environment added to ours. */
class JdkProcessRunner(private val installTimeoutMinutes: Long = INSTALL_TIMEOUT_MINUTES) : ProcessRunner {

    override fun run(cmd: Command): Int {
        val p = builder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (!p.waitFor(installTimeoutMinutes, TimeUnit.MINUTES)) {
            p.destroyForcibly()
            return TIMED_OUT
        }
        return p.exitValue()
    }

    override fun start(cmd: Command) {
        builder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    }

    private fun builder(cmd: Command): ProcessBuilder = ProcessBuilder(cmd.argv).apply {
        environment().putAll(cmd.env)
        cmd.cwd?.let { directory(it) }
    }

    companion object {
        /** Unpacking tens of gigabytes takes a while; a hung tool must still end. */
        const val INSTALL_TIMEOUT_MINUTES = 120L

        /** The exit code [run] reports when the tool had to be killed. */
        const val TIMED_OUT = -1
    }
}

/** PATH lookup, the same rule the mpv and xdg-open seams apply. */
object Which {
    fun onPath(command: String): Boolean = if (command.contains(File.separatorChar)) {
        File(command).let { it.isFile && it.canExecute() }
    } else {
        System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).any { dir ->
            dir.isNotBlank() && File(dir, command).let { it.isFile && it.canExecute() }
        }
    }
}
