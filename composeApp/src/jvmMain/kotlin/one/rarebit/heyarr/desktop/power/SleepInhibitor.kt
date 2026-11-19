package one.rarebit.heyarr.desktop.power

import java.io.IOException

/**
 * Keeps the machine from sleeping while a hold is open. A Cast relays the film through this
 * app (heyarr-kmp#248): when the laptop idle-suspended mid-film, the node dropped the stream
 * as "client gone" and the TV played out its buffer and stopped.
 */
fun interface SleepInhibitor {
    /** Takes a hold, or null when this platform offers none. Closing the hold releases it. */
    fun acquire(reason: String): AutoCloseable?

    companion object {
        val NONE = SleepInhibitor { null }

        /** The inhibitor for this OS; [NONE] where there is none yet. */
        fun system(os: String = System.getProperty("os.name").orEmpty()): SleepInhibitor = when {
            os.startsWith("Linux") -> ProcessSleepInhibitor(::logindCommand)

            os.startsWith("Mac") -> ProcessSleepInhibitor({ caffeinateCommand() })

            // TODO(heyarr-kmp#248): SetThreadExecutionState on a held thread, via JNA, if a
            //  Windows build ever ships. Only Linux packages are built today.
            else -> NONE
        }

        /**
         * A logind block lock on sleep and idle, held by `systemd-inhibit` for as long as its
         * child runs. The child is `cat` on a pipe from this JVM, so the lock goes when the
         * hold is closed and, because the kernel closes that pipe, when the app crashes or is
         * killed: a lock can never outlive the app.
         *
         * Not the desktop portal's Inhibit, though the app reads the portal over `gdbus`: a
         * portal inhibition lasts only as long as the caller's D-Bus connection, and `gdbus
         * call` disconnects as soon as it returns, which releases the lock at once. Holding a
         * bus connection open needs a JVM D-Bus client. The observed suspend was logind's own
         * idle action, which this lock blocks directly. The lid switch is deliberately not
         * inhibited: a closed laptop in a bag must still sleep; reconnecting covers it.
         */
        internal fun logindCommand(reason: String): List<String> = listOf(
            "systemd-inhibit",
            "--what=sleep:idle",
            "--who=heyarr",
            "--why=$reason",
            "--mode=block",
            "cat",
        )

        /**
         * An IOPMAssertion against idle sleep (and system sleep on AC), which `caffeinate`
         * drops by itself when this JVM exits (`-w`). The lid still sleeps the Mac.
         */
        internal fun caffeinateCommand(): List<String> = listOf(
            "caffeinate",
            "-i",
            "-s",
            "-w",
            ProcessHandle.current().pid().toString(),
        )
    }
}

/**
 * A hold that is a child process: the lock lasts while it runs. Its stdin is a pipe from this
 * JVM that is never written, so a child that waits on it (`cat`) ends with the app.
 */
internal class ProcessSleepInhibitor(
    private val command: (String) -> List<String>,
    private val start: (List<String>) -> Process = ::startHolder,
) : SleepInhibitor {
    override fun acquire(reason: String): AutoCloseable? {
        val holder = try {
            start(command(reason))
        } catch (_: IOException) {
            // Not installed (no systemd, a container): casting still works, the machine may sleep.
            return null
        }
        return AutoCloseable {
            runCatching { holder.outputStream.close() }
            holder.destroy()
        }
    }

    private companion object {
        fun startHolder(command: List<String>): Process = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }
}
