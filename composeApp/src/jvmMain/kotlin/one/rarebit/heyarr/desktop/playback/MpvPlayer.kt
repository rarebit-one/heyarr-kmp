package one.rarebit.heyarr.desktop.playback

import java.io.File
import java.io.IOException

/**
 * The [Player] backend for this box: **mpv**, launched as an external process on the
 * blob stream URL with the bearer credential passed as an HTTP header. mpv uses the
 * system CA store — which already trusts heyarr's internal cert — so TLS just works and
 * we do not touch the JVM trust store.
 *
 * The spawned argv is, exactly:
 * ```
 * mpv --force-window=yes --http-header-fields=Authorization: Bearer <token> <url>
 * ```
 * (each element is one argv slot; there is no shell, so no shell-quoting). The token
 * rides in the header value only and is NEVER put in a log line or an error message.
 *
 * The process spawn and the mpv-present check are injected so the argv the player would
 * spawn is asserted in a JVM test WITHOUT launching anything — the real [play] wires the
 * production [ProcessBuilder] spawn and a PATH lookup.
 */
class MpvPlayer(
    private val command: String = DEFAULT_COMMAND,
    private val available: () -> Boolean = { onPath(command) },
    private val spawn: (List<String>) -> Unit = { argv -> ProcessBuilder(argv).start() },
) : Player {

    override fun play(baseUrl: String, blobHash: String, token: String): PlayResult =
        playAll(baseUrl, listOf(blobHash), token)

    /**
     * Queue the whole ordered list as one mpv playlist. mpv takes several positional URLs
     * and plays them in order, and `--http-header-fields` is a GLOBAL option applied to
     * every HTTP request — and every track here carries the SAME bearer — so one shared
     * `Authorization` header covers the entire album. No per-URL headers, no temp m3u.
     */
    override fun playAll(baseUrl: String, blobHashes: List<String>, token: String): PlayResult {
        if (blobHashes.isEmpty()) return PlayResult.Failed("Nothing to play.")
        val urls = try {
            blobHashes.map { BlobStream.contentUrl(baseUrl, it) }
        } catch (e: IllegalArgumentException) {
            return PlayResult.Failed("Cannot play this file: ${e.message}.")
        }
        if (!available()) return PlayResult.Failed(MPV_NOT_FOUND)
        return try {
            spawn(argv(command, urls, token))
            PlayResult.Launched
        } catch (e: IOException) {
            // Never surface the exception's message verbatim in case a wrapper echoed the
            // argv; a fixed, token-free hint is enough for the user.
            PlayResult.Failed(MPV_NOT_FOUND)
        }
    }

    companion object {
        const val DEFAULT_COMMAND = "mpv"

        const val MPV_NOT_FOUND =
            "mpv was not found. Install it (e.g. `pacman -S mpv`) and make sure it is on your PATH."

        /** Single-URL argv — the one-track case, kept for callers and tests that pass a lone URL. */
        fun argv(command: String, url: String, token: String): List<String> = argv(command, listOf(url), token)

        /**
         * The exact argv [playAll] spawns — pure, so a test asserts it without launching.
         * `--force-window=yes` makes mpv open a window even for audio; the one shared header
         * carries the bearer token; the ordered URLs are the trailing positional arguments
         * (mpv plays them as a playlist).
         */
        fun argv(command: String, urls: List<String>, token: String): List<String> = buildList {
            add(command)
            add("--force-window=yes")
            add("--http-header-fields=Authorization: Bearer $token")
            addAll(urls)
        }

        /** True when [command] resolves to an executable file (absolute path, or on `$PATH`). */
        private fun onPath(command: String): Boolean {
            if (command.contains(File.separatorChar)) {
                val f = File(command)
                return f.isFile && f.canExecute()
            }
            val path = System.getenv("PATH") ?: return false
            return path.split(File.pathSeparatorChar).any { dir ->
                dir.isNotBlank() && File(dir, command).let { it.isFile && it.canExecute() }
            }
        }
    }
}
