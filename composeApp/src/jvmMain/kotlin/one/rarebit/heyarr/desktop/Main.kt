package one.rarebit.heyarr.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import one.rarebit.heyarr.desktop.discovery.JmdnsResolver
import one.rarebit.heyarr.desktop.net.JdkHttpTransport
import one.rarebit.heyarr.desktop.playback.MpvPlayer
import one.rarebit.heyarr.desktop.settings.FileSettingsStore
import one.rarebit.heyarr.desktop.ui.App
import kotlin.system.exitProcess

/**
 * Desktop entry point (`compose.desktop.application { mainClass = "…MainKt" }`).
 *
 * Wires the concrete platform pieces — the JDK HttpTransport actual, the file-backed
 * SettingsStore and the mpv player — and hands them to the shared [App] composable.
 * This is the only place that names concretes; everything below the UI depends on
 * interfaces, so the shared-module extraction later is a move, not a rewrite.
 *
 * # Closing the window fully stops playback and exits
 *
 * The embedded player is libmpv INSIDE this process (EmbeddedPlayer.startInProcess): it
 * decodes audio on native threads the JVM does not own, and mpv is never terminated by
 * the Compose lifecycle. With `onCloseRequest = ::exitApplication` alone, closing the
 * window ended the UI while those native threads kept playing — and the IPC/coroutine
 * machinery could leave non-daemon threads alive — so the process lingered, still
 * audible, and did not even die on SIGTERM. So [main] does not stop at `application {}`:
 * when it returns (the last window closed) we [exitProcess] to tear the whole process
 * down, native audio threads and all; a shutdown hook first destroys any pop-out `mpv`
 * child so it cannot outlive the app either.
 */
fun main() {
    // A pop-out mpv is a child process; force any still alive to die when the app exits,
    // so closing it never leaves one playing. Runs before exitProcess halts the JVM
    // below, and on a SIGTERM-driven shutdown too.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            ProcessHandle.current().descendants().forEach { runCatching { it.destroyForcibly() } }
        },
    )

    application {
        val state = rememberWindowState(width = 1280.dp, height = 800.dp)
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            title = "Heyarr",
        ) {
            App(
                settings = FileSettingsStore(),
                transport = JdkHttpTransport(),
                player = MpvPlayer(),
                mdns = JmdnsResolver(),
                // The real desktop entry point turns on device enrolment ("Sign in to save"):
                // a filesystem-backed device key store + the voidbind pairing coordinator.
                enableDeviceEnrol = true,
                onFullscreen = { on ->
                    state.placement =
                        if (on) WindowPlacement.Fullscreen else WindowPlacement.Floating
                },
            )
        }
    }

    // application() returns only once the last window has closed. Force the process all
    // the way down so the embedded libmpv's native audio threads stop with it, instead
    // of playing on behind a closed window.
    exitProcess(0)
}
