package one.rarebit.heyarr.desktop.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import one.rarebit.heyarr.core.mcp.JsonWrite
import one.rarebit.heyarr.core.net.JsonScan
import com.sun.jna.Pointer
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets

/** One subtitle or audio track as mpv lists it. */
data class MpvTrack(val id: Int, val type: String, val title: String?, val lang: String?, val selected: Boolean, val external: Boolean) {
    val label: String get() = listOfNotNull(lang?.uppercase(), title).joinToString(" · ").ifBlank { "#$id" }
}

/** What the player is doing right now — the only state the controls read. */
data class PlayerState(
    val loaded: Boolean = false,
    val paused: Boolean = true,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val volume: Double = 100.0,
    val muted: Boolean = false,
    val buffering: Boolean = false,
    val eof: Boolean = false,
    // mpv's core-idle: true whenever no frame is being shown — during the initial
    // warm-up, a mid-stream cache stall, a seek, a pause or EOF. Paired with
    // [hasStarted] to tell the FIRST warm-up (nothing on screen yet) apart from a
    // later pause, which core-idle alone cannot.
    val coreIdle: Boolean = true,
    // Sticky: flips true the instant the first frame plays (core-idle goes false, or
    // the position passes the first second) and never flips back. Before it the
    // picture is still warming up and the scrubber is not yet live; after it a pause
    // is just a pause. A fresh start/load resets it.
    val hasStarted: Boolean = false,
    // The media time up to which bytes are already cached ahead (mpv's
    // demuxer-cache-time). Drives the "buffered" band on the scrubber. For a
    // server transcode stream it is how far the encode has been read, so it
    // trails the pinned total rather than reaching it until the stream ends.
    val bufferedTo: Double = 0.0,
    val subtitles: List<MpvTrack> = emptyList(),
    val audio: List<MpvTrack> = emptyList(),
    val subtitleId: Int? = null,
    val audioId: Int? = null,
    val error: String? = null,
    val title: String? = null,
) {
    val fraction: Float get() = if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f

    /** How far the buffer reaches, 0..1 of the total — the lighter band ahead of the playhead. */
    val bufferedFraction: Float get() = if (duration > 0) (bufferedTo / duration).toFloat().coerceIn(0f, 1f) else 0f

    /**
     * Warming up: a file is loading or has loaded but no frame has played yet — the
     * "playing but nothing on screen" gap at the very start, which for a server stream
     * can run tens of seconds while the encode and the cache fill. Distinct from
     * [buffering] (a cache stall AFTER playback has begun), and false once [hasStarted],
     * so a later pause is never mistaken for warm-up. The picture shows a starting state
     * and the scrubber is not yet seekable while this holds.
     */
    val warmingUp: Boolean get() = error == null && !eof && !hasStarted

    /**
     * Stalled: playback has started and is not paused, but no frame is being shown right now —
     * a mid-stream cache underrun (the "stuck at the first second" case once it has nominally
     * begun). The picture would otherwise sit on a frozen last frame; treated like warm-up so
     * the loading cover shows instead. A deliberate pause (paused) is not a stall.
     */
    val stalled: Boolean get() = error == null && !eof && hasStarted && !paused && coreIdle
}

/**
 * mpv, embedded: libmpv inside this process, decoding into memory frames the picture
 * composable draws ([MpvRenderer]), with every control driven over mpv's JSON IPC
 * socket — the same socket a pop-out `mpv` process offers, so one transport drives
 * both. The UI draws the transport over the picture; mpv only ever produces frames.
 *
 * Why libmpv and not `--wid`: an X window embedded over Compose sits above everything
 * the app draws, so nothing could overlay the picture, and it only worked through
 * XWayland at all. A memory frame is a Compose element like any other. The bearer
 * token rides in `http-header-fields` as an option (no shell), never in a log line.
 *
 * Blocking I/O lives on a reader thread; state lands in a Compose `mutableStateOf`
 * so the controls recompose on every property change. Pure parsing (events →
 * state) is split into [PlayerEvents] and unit-tested.
 */
class EmbeddedPlayer(
    private val command: String = "mpv",
    private val spawn: (List<String>) -> Process = { argv ->
        ProcessBuilder(argv).apply { redirectErrorStream(true); redirectOutput(ProcessBuilder.Redirect.DISCARD) }.start()
    },
) {
    var state: PlayerState by mutableStateOf(PlayerState())
        private set

    /** The in-process renderer while embedded; the picture composable reads its frames. */
    internal var renderer: MpvRenderer? by mutableStateOf(null)
        private set

    private var lib: MpvLib? = null
    private var handle: Pointer? = null
    private var process: Process? = null
    private var channel: SocketChannel? = null
    private var reader: Thread? = null
    private var socketPath: File? = null
    @Volatile private var closed = false

    /**
     * The real source runtime, when known (a server transcode stream, whose own
     * duration grows as it is encoded). When set, it PINS the reported duration
     * so the scrubber shows the full length instead of what has streamed so far.
     * Null for a direct file, where mpv's own duration is authoritative.
     */
    @Volatile private var knownDuration: Double? = null

    /**
     * The token URL of a restart-seekable transcode stream, with no `start` param —
     * null for a direct blob, which mpv seeks natively over HTTP ranges. When set,
     * every seek is a RESTART ([restartStream]) rather than an mpv `seek` command,
     * because the bytes ahead of the playhead do not exist yet (ADR-0069).
     */
    @Volatile private var streamBase: String? = null

    /**
     * Where the CURRENT repackage begins in the source. mpv's own clock restarts at
     * zero on every restart, so this is the offset that turns mpv's time back into
     * source time — the one the scrubber, the resume position and the next seek all
     * have to be expressed in. Always zero for a direct blob.
     */
    @Volatile private var streamStart: Double = 0.0

    val isRunning: Boolean get() = (handle != null || process?.isAlive == true) && channel?.isOpen == true

    /** True when mpv runs in its own window rather than inside the app. */
    var poppedOut: Boolean = false
        private set
    /** Set when mpv went away on its own (the pop-out window was closed): the screen re-embeds and resumes. */
    var exited: Boolean by mutableStateOf(false)
        private set
    private var resumeAt: Double? = null
    private var lastUrl: String? = null
    private var lastToken: String? = null
    private var lastTitle: String? = null
    /** External subtitle URLs already `sub-add`ed to the current file, so re-applying is idempotent. */
    private val appliedSubs = mutableListOf<String>()
    /**
     * The sidecar URLs the session wants on this item, kept so a restart seek can put
     * them back: a re-cut is a whole new file to mpv and its `sub-add`s go with the old
     * one, and nothing upstream re-offers them (the shell only calls `refreshSubtitles`
     * when an item starts). Re-applied on the `file-loaded` that follows the re-cut,
     * not straight after `loadfile`, because `sub-add` before the file is open is lost.
     */
    private var wantedSubs: List<String> = emptyList()
    @Volatile private var resubOnLoad = false

    /**
     * Start mpv and load [url]. [embedded] runs libmpv in-process, rendering into the
     * app; otherwise an `mpv` process opens its own window — the pop-out — while the
     * app's transport still drives it over the same socket. Returns null on success,
     * else a UI-safe reason.
     */
    fun start(embedded: Boolean, url: String, token: String, title: String, knownDurationSec: Double? = null, streamBaseUrl: String? = null): String? {
        close()
        closed = false
        knownDuration = knownDurationSec?.takeIf { it > 0 }
        streamBase = streamBaseUrl
        poppedOut = !embedded
        lastUrl = url; lastToken = token; lastTitle = title
        val tmp = File(System.getProperty("java.io.tmpdir"))
        sweepStaleSockets(tmp)
        val sock = File(tmp, "heyarr-mpv-${ProcessHandle.current().pid()}-${System.nanoTime()}.sock")
        socketPath = sock
        val resume = resumeAt?.takeIf { it > 1.0 }
        resumeAt = null
        // A stream resumes by being re-cut from the offset, not by seeking into it: the
        // URL carries the position and mpv gets no `start` at all. A direct blob is the
        // other way round — one URL, and mpv seeks it over ranges.
        streamStart = if (streamBaseUrl != null) resume ?: 0.0 else 0.0
        val effectiveUrl = streamBaseUrl?.let { StreamSeek.urlAt(it, streamStart) } ?: url
        val start = if (streamBaseUrl != null) null else resume
        exited = false
        val err = if (embedded) startInProcess(sock, token, title, start) else startProcess(sock, effectiveUrl, token, title, start)
        if (err != null) { close(); return err }
        // The socket appears once mpv is up; a window under XWayland can take a moment.
        val deadline = System.currentTimeMillis() + 12_000
        var ch: SocketChannel? = null
        while (System.currentTimeMillis() < deadline && ch == null) {
            if (!embedded && process?.isAlive != true) { close(); return "mpv exited before it opened its control socket." }
            ch = try {
                SocketChannel.open(StandardProtocolFamily.UNIX).also { it.connect(UnixDomainSocketAddress.of(sock.toPath())) }
            } catch (e: IOException) { Thread.sleep(80); null }
        }
        channel = ch ?: run { close(); return "mpv started but its control socket never answered." }
        state = PlayerState(title = title, duration = knownDuration ?: 0.0, position = streamStart)
        reader = Thread({ readLoop(ch) }, "mpv-ipc").apply { isDaemon = true; start() }
        for ((i, prop) in OBSERVED.withIndex()) send("observe_property", i + 1, prop)
        if (embedded) send("loadfile", effectiveUrl)
        return null
    }

    /** libmpv in this process: no window, no OSD of its own, frames through [MpvRenderer]. */
    private fun startInProcess(sock: File, token: String, title: String, start: Double?): String? {
        val lib = MpvLib.loaded.getOrElse { return it.message ?: "libmpv is not installed" }
        val h = lib.mpv_create() ?: return "libmpv could not create a player"
        val options = listOf(
            "vo" to "libmpv", "input-ipc-server" to sock.absolutePath, "idle" to "yes", "keep-open" to "yes", "terminal" to "no",
            // Hardware decode into system memory — the frame-readback renderer needs the frames
            // back in RAM (plain `auto` hands back GPU-only frames it cannot read), and 4K HEVC is
            // far too slow to software-decode here. [EMBEDDED_HWDEC] is `vulkan-copy` on aarch64
            // Linux (Apple Silicon / Asahi), where the VA-API path crashes libmpv, and `auto-copy`
            // elsewhere; mpv falls back to software if the method is unavailable, so it is always safe.
            "msg-level" to "all=error", "osc" to "no", "osd-level" to "0", "input-default-bindings" to "no", "hwdec" to EMBEDDED_HWDEC,
            // HDR sources (4K especially) tone-mapped toward the SDR, 8-bit surface this
            // software renderer presents. Without it mpv hands back BT.2020/PQ pixels the UI
            // shows as if they were sRGB — the washed-out, low-contrast look on 4K HDR. The
            // software render path cannot tone-map as fully as the GPU pop-out (default vo),
            // which stays the reference for HDR; this is the best the in-app surface offers.
            "tone-mapping" to "bt.2390",
            "http-header-fields" to "Authorization: Bearer $token", "force-media-title" to title,
        ) + listOfNotNull(start?.let { "start" to it.toString() })
        for ((k, v) in options) lib.mpv_set_option_string(h, k, v)
        val rc = lib.mpv_initialize(h)
        if (rc < 0) { lib.mpv_terminate_destroy(h); return "libmpv: ${lib.mpv_error_string(rc)}" }
        this.lib = lib; handle = h
        renderer = try { MpvRenderer(lib, h) } catch (e: IllegalStateException) { return e.message }
        return null
    }

    /** A window of mpv's own. Picture only: the app's transport is the one UI in either mode; its keys still work on their own. */
    private fun startProcess(sock: File, url: String, token: String, title: String, start: Double?): String? {
        val argv = buildList {
            add(command)
            add("--input-ipc-server=${sock.absolutePath}")
            addAll(listOf("--idle=yes", "--force-window=yes", "--keep-open=yes", "--no-terminal", "--msg-level=all=error"))
            // Same decoder story as the embedded path: on aarch64 Linux force Vulkan so the
            // pop-out is not a black screen from the crashing VA-API/v4l2 route. The GPU vo can
            // take Vulkan frames directly (no copy). Elsewhere leave mpv's own default.
            POPOUT_HWDEC?.let { add("--hwdec=$it") }
            // osc=yes: the pop-out is its OWN window, so give it mpv's on-screen controller —
            // a seek bar and buttons on mouse-over. (The embedded path has no window and is
            // driven by the app's transport; this only affects the pop-out.)
            addAll(listOf("--osc=yes", "--osd-level=1", "--osd-bar=yes", "--input-default-bindings=yes", "--input-vo-keyboard=yes", "--geometry=60%", "--input-conf=${inputConf().absolutePath}"))
            start?.let { add("--start=$it") }
            add("--http-header-fields=Authorization: Bearer $token")
            add("--title=$title")
            add(url)
        }
        process = try { spawn(argv) } catch (e: IOException) { return "mpv could not be started — is it installed and on PATH?" }
        return null
    }

    /** Replace what is playing (the token was given at start; mpv keeps its header option). */
    fun load(url: String, title: String, knownDurationSec: Double? = null, streamBaseUrl: String? = null) {
        lastUrl = url; lastTitle = title
        knownDuration = knownDurationSec?.takeIf { it > 0 }
        streamBase = streamBaseUrl
        streamStart = 0.0     // a new item always begins at its own start
        appliedSubs.clear()   // a new file drops the old file's external subtitles
        wantedSubs = emptyList(); resubOnLoad = false   // and they belonged to the old item
        state = state.copy(loaded = false, position = 0.0, duration = knownDuration ?: 0.0, eof = false, error = null, hasStarted = false, coreIdle = true, title = title, subtitles = emptyList(), audio = emptyList())
        send("set_property", "force-media-title", title)
        send("loadfile", streamBaseUrl ?: url)
        send("set_property", "pause", false)
    }

    /**
     * Move playback between the app and a window of mpv's own, keeping the position,
     * pause state and volume. A live player cannot change hosts, so this is a restart
     * with a seek — the file is streamed, so it resumes in a moment.
     */
    fun switchTo(embedded: Boolean): String? {
        val url = lastUrl ?: return "nothing is playing"
        val token = lastToken ?: return "nothing is playing"
        val title = lastTitle ?: ""
        val resume = state.copy()
        resumeAt = resume.position
        // resumeAt is source time, and start() knows what to do with it either way:
        // a stream is re-cut from there, a direct file is seeked there. Carrying
        // streamBase across is what keeps the pop-out seekable too.
        val err = start(embedded, url, token, title, knownDuration, streamBase) ?: run {
            send("set_property", "volume", resume.volume)
            send("set_property", "mute", resume.muted)
            send("set_property", "pause", resume.paused)
            null
        }
        return err
    }

    fun togglePause() = send("cycle", "pause")
    fun play() = send("set_property", "pause", false)
    fun pause() = send("set_property", "pause", true)
    /**
     * Seek, in SOURCE seconds — the units the scrubber and the ±10 s buttons already
     * speak. For a direct blob this is mpv's own seek over HTTP ranges. For a
     * transcode stream there is nothing ahead of the demuxer cache to seek into, so
     * the stream is re-cut from the new offset instead ([restartStream]); that is the
     * difference between a scrub that works anywhere on the bar and one that only
     * works inside what has already buffered.
     */
    fun seekTo(seconds: Double) {
        if (streamBase == null) { send("seek", seconds, "absolute"); return }
        // Inside what mpv has already demuxed there is nothing to re-cut: seek it
        // natively, in mpv's own clock. That keeps Back-10 and a small nudge forward
        // instant, and spends an ffmpeg only on the seek that actually needs one.
        if (withinCache(seconds)) send("seek", seconds - streamStart, "absolute") else restartStream(seconds)
    }

    /** Relative seek. [state] carries source time, so the arithmetic is the same either way. */
    fun seekBy(seconds: Double) {
        if (streamBase == null) { send("seek", seconds, "relative"); return }
        seekTo(state.position + seconds)
    }

    /**
     * True when [sourceSeconds] is inside the current cut AND inside what the demuxer
     * has read ahead — the only window a transcode stream can be seeked in without
     * restarting it. [PlayerState.bufferedTo] is exactly that frontier, in source time.
     * The margin keeps a seek off the very edge of the cache, which mpv would answer
     * by stalling at the frontier rather than playing on.
     */
    private fun withinCache(sourceSeconds: Double): Boolean =
        sourceSeconds >= streamStart && sourceSeconds <= state.bufferedTo - CACHE_SEEK_MARGIN

    fun seekFraction(f: Double) { if (state.duration > 0) seekTo(f * state.duration) }

    /**
     * Re-cut a transcode stream from [atSeconds] into the source (ADR-0069): a new
     * URL off the base, a fresh ffmpeg, and mpv's clock back at zero — which is why
     * [streamStart] moves with it. The position is shown at the target immediately so
     * the scrubber lands where it was dropped instead of snapping back while the new
     * encode warms up. `hasStarted` deliberately does NOT reset: this is a seek, not a
     * fresh load, and warm-up swaps the scrubber for an indeterminate bar — which
     * would take the slider away mid-drag for as long as the new encode takes. Keeping
     * it started leaves the bar live and reports the gap as [PlayerState.stalled]
     * instead, so the loading cover still shows over the picture but a second seek can
     * be made straight away. Clamped to the runtime so dragging to the very end still
     * yields a request ffmpeg can answer.
     */
    private fun restartStream(atSeconds: Double) {
        val base = streamBase ?: return
        val limit = (knownDuration ?: state.duration).takeIf { it > 0 }
        val at = atSeconds.coerceAtLeast(0.0).let { if (limit != null) it.coerceAtMost(limit) else it }
        streamStart = at
        appliedSubs.clear()   // the re-cut is a new file to mpv; sidecars re-attach below
        resubOnLoad = wantedSubs.isNotEmpty()
        state = state.copy(position = at, bufferedTo = at, loaded = false, eof = false, error = null, coreIdle = true)
        send("loadfile", StreamSeek.urlAt(base, at))
        send("set_property", "pause", false)
    }
    fun setVolume(v: Double) = send("set_property", "volume", v.coerceIn(0.0, 130.0))
    fun toggleMute() = send("cycle", "mute")
    fun setSubtitle(id: Int?) = send("set_property", "sid", id ?: "no")
    fun cycleSubtitle() = send("cycle", "sub")

    /**
     * Attach heyarr subtitle-sidecar blob URLs to the CURRENT file as external tracks
     * (mpv `sub-add`). They ride the same `http-header-fields` bearer the video does, so
     * the authenticated blob fetch just works, and land in `track-list` as `external`
     * tracks — exactly what the CC picker labels "sidecar file". The first is selected so
     * captions show; the rest are added selectable-but-off. Idempotent and a no-op until
     * the socket is up, so callers may invoke it on both start and queue-load without
     * double-adding; [load] clears the set when the file changes.
     */
    fun addExternalSubtitles(urls: List<String>) {
        wantedSubs = urls.filter { it.isNotBlank() }
        if (channel?.isOpen != true) return
        for (u in urls) {
            if (u.isBlank() || u in appliedSubs) continue
            send("sub-add", u, if (appliedSubs.isEmpty()) "select" else "auto")
            appliedSubs.add(u)
        }
    }
    fun setAudio(id: Int) = send("set_property", "aid", id)
    fun stop() = send("stop")

    fun close() {
        closed = true
        runCatching { send("quit") }
        runCatching { channel?.close() }
        channel = null
        // Order matters: the render context must go before the core (render.h), and the core before the socket file.
        renderer?.let { r -> runCatching { r.close() } }
        renderer = null
        handle?.let { h -> lib?.let { l -> runCatching { l.mpv_terminate_destroy(h) } } }
        handle = null
        process?.let { p -> if (!p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() }
        process = null
        socketPath?.delete()
        appliedSubs.clear()
        wantedSubs = emptyList()
        resubOnLoad = false
        streamBase = null
        streamStart = 0.0
        state = PlayerState()
    }

    private fun send(vararg command: Any?) {
        val ch = channel ?: return
        val line = JsonWrite.obj(mapOf("command" to command.toList())) + "\n"
        try {
            synchronized(ch) { ch.write(ByteBuffer.wrap(line.toByteArray(StandardCharsets.UTF_8))) }
        } catch (e: IOException) {
            if (!closed) state = state.copy(error = "lost the connection to mpv")
        }
    }

    private fun readLoop(ch: SocketChannel) {
        val buf = ByteBuffer.allocate(64 * 1024)
        val pending = StringBuilder()
        try {
            while (!closed && ch.read(buf).also { if (it < 0) { onGone(); return } } >= 0) {
                buf.flip()
                pending.append(StandardCharsets.UTF_8.decode(buf))
                buf.clear()
                var nl: Int
                while (pending.indexOf("\n").also { nl = it } >= 0) {
                    val line = pending.substring(0, nl).trim()
                    pending.delete(0, nl + 1)
                    if (line.isNotEmpty()) {
                        val next = PlayerEvents.apply(state, line, streamStart)
                        // Pin the total to the known source runtime: a transcode stream's
                        // own duration grows as it encodes, which would flicker the scrubber.
                        state = knownDuration?.let { next.copy(duration = it) } ?: next
                        // The re-cut is open: put the sidecars back on it.
                        if (resubOnLoad && next.loaded) { resubOnLoad = false; addExternalSubtitles(wantedSubs) }
                    }
                }
            }
        } catch (e: IOException) {
            if (!closed) onGone()
        }
    }

    /** mpv closed its end (the user shut the pop-out, or it crashed): remember where it was. */
    private fun onGone() {
        if (closed) return
        resumeAt = state.position
        state = state.copy(error = null)
        exited = true
    }

    /**
     * Key bindings for the pop-out: mpv's defaults plus a horizontal wheel that follows
     * natural scrolling (a two-finger swipe to the right moves forward), and a wheel
     * over the picture that seeks rather than changes volume.
     */
    private fun inputConf(): File {
        val f = File(System.getProperty("java.io.tmpdir"), "heyarr-mpv-input.conf")
        f.writeText(
            """
            WHEEL_LEFT  seek 5
            WHEEL_RIGHT seek -5
            WHEEL_UP    seek 10
            WHEEL_DOWN  seek -10
            SPACE       cycle pause
            f           cycle fullscreen
            ESC         set fullscreen no
            """.trimIndent() + "\n",
        )
        return f
    }

    companion object {
        // aarch64 Linux is Apple Silicon / Asahi (the app's other target beside x86). There the
        // VA-API decode path runs through libva-v4l2request, which fails to decode and crashes
        // libmpv outright ("pure virtual method called") — it turned a pop-out into a black screen
        // and a pop-back-in into a hard crash. Vulkan video decode works there (verified for H.264
        // and 4K HEVC-10), so prefer it. `-copy` on the embedded path hands frames back to system
        // memory for the software renderer; the pop-out's GPU vo takes Vulkan frames directly.
        private val asahiLike = System.getProperty("os.name").orEmpty().startsWith("Linux") &&
            System.getProperty("os.arch") in setOf("aarch64", "arm64")

        /** Embedded (in-process, frame-readback) decode path. */
        val EMBEDDED_HWDEC = if (asahiLike) "vulkan-copy" else "auto-copy"

        /** Pop-out (external mpv, GPU vo) decode path; null leaves mpv's own default. */
        val POPOUT_HWDEC: String? = if (asahiLike) "vulkan" else null

        /** Sockets left by app processes that are gone (a kill, a crash); ours are named by pid. */
        internal fun sweepStaleSockets(dir: File) {
            dir.listFiles { f -> f.name.startsWith("heyarr-mpv-") && f.name.endsWith(".sock") }?.forEach { f ->
                val pid = f.name.removePrefix("heyarr-mpv-").substringBefore('-').toLongOrNull() ?: return@forEach
                if (pid != ProcessHandle.current().pid() && !ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) f.delete()
            }
        }

        /** How far short of the cache frontier a seek still counts as "already buffered". */
        private const val CACHE_SEEK_MARGIN = 1.0

        /** Properties observed in order; the index+1 is the observer id. */
        val OBSERVED = listOf("time-pos", "duration", "pause", "volume", "mute", "paused-for-cache", "demuxer-cache-time", "eof-reached", "core-idle", "track-list", "sid", "aid", "media-title")
    }
}

/** Pure: fold one mpv IPC line into the state. */
object PlayerEvents {
    /**
     * [sourceOffset] is where the current file begins in the source — non-zero only
     * for a transcode stream re-cut by a restart seek (ADR-0069), whose own clock
     * starts at zero however far in it actually is. mpv's times are shifted by it so
     * everything above this line speaks source time and nothing has to remember to
     * add it back: the scrubber, the buffered band, the resume position and the next
     * seek's arithmetic are all in the same units as a direct file's.
     */
    fun apply(state: PlayerState, line: String, sourceOffset: Double = 0.0): PlayerState {
        val obj = JsonScan.rootObject(line) ?: return state
        val event = JsonScan.stringField(obj, "event") ?: return state
        return when (event) {
            "property-change" -> onProperty(state, JsonScan.stringField(obj, "name") ?: return state, obj, sourceOffset)
            "file-loaded" -> state.copy(loaded = true, error = null, eof = false)
            "end-file" -> {
                val reason = JsonScan.stringField(obj, "reason")
                if (reason == "error") state.copy(error = JsonScan.stringField(obj, "file_error") ?: "playback failed", loaded = false)
                else if (reason == "eof") state.copy(eof = true) else state
            }
            else -> state
        }
    }

    private fun onProperty(s: PlayerState, name: String, obj: String, offset: Double): PlayerState = when (name) {
        // hasStarted flips on the first frame; time crossing the first second is the
        // fallback for a stream mpv plays without ever reporting core-idle=false. That
        // test reads mpv's OWN clock (how far into this cut), not the shifted position,
        // so a seek far into a film still has to play half a second to count as started.
        "time-pos" -> num(obj)?.let { s.copy(position = it + offset, hasStarted = s.hasStarted || it > 0.5) } ?: s
        "duration" -> num(obj)?.let { s.copy(duration = it) } ?: s
        "pause" -> JsonScan.boolField(obj, "data")?.let { s.copy(paused = it) } ?: s
        "volume" -> num(obj)?.let { s.copy(volume = it) } ?: s
        "mute" -> JsonScan.boolField(obj, "data")?.let { s.copy(muted = it) } ?: s
        "paused-for-cache" -> JsonScan.boolField(obj, "data")?.let { s.copy(buffering = it) } ?: s
        "demuxer-cache-time" -> num(obj)?.let { s.copy(bufferedTo = it + offset) } ?: s
        "eof-reached" -> JsonScan.boolField(obj, "data")?.let { s.copy(eof = it) } ?: s
        // core-idle false means a frame is being shown: the first one ends warm-up for good.
        "core-idle" -> JsonScan.boolField(obj, "data")?.let { idle -> s.copy(coreIdle = idle, hasStarted = s.hasStarted || !idle) } ?: s
        "sid" -> s.copy(subtitleId = JsonScan.longField(obj, "data")?.toInt())
        "aid" -> s.copy(audioId = JsonScan.longField(obj, "data")?.toInt())
        "media-title" -> JsonScan.stringField(obj, "data")?.let { s.copy(title = it) } ?: s
        "track-list" -> {
            val tracks = JsonScan.objectsOf(JsonScan.arrayOf(obj, listOf("data")) ?: "[]", emptyList()).mapNotNull { t ->
                val id = JsonScan.longField(t, "id")?.toInt() ?: return@mapNotNull null
                MpvTrack(
                    id = id, type = JsonScan.stringField(t, "type") ?: "?", title = JsonScan.stringField(t, "title"), lang = JsonScan.stringField(t, "lang"),
                    selected = JsonScan.boolField(t, "selected") ?: false, external = JsonScan.boolField(t, "external") ?: false,
                )
            }
            s.copy(subtitles = tracks.filter { it.type == "sub" }, audio = tracks.filter { it.type == "audio" })
        }
        else -> s
    }

    private fun num(obj: String): Double? {
        val i = JsonScan.valueStart(obj, "data") ?: return null
        var j = i
        while (j < obj.length && (obj[j].isDigit() || obj[j] in ".-+eE")) j++
        return obj.substring(i, j).toDoubleOrNull()
    }
}
