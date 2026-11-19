package one.rarebit.heyarr.desktop.cast

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import one.rarebit.heyarr.desktop.heyarr.HeyarrApi
import one.rarebit.heyarr.desktop.heyarr.PlaybackTarget
import one.rarebit.heyarr.desktop.power.SleepInhibitor
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the manager is doing on the receiver's behalf, film-absolute, for the UI to say.
 * [SEEKING]: the receiver jumped to a point it cannot reach in a live repackage, so the
 * film is restarted there. [RESTARTING]: the receiver stopped making progress, so its
 * media app is quit and relaunched where it was. [GAVE_UP]: it kept stalling or dropping
 * the film after that. [RECONNECTED]: the Cast channel came back (after a suspend, a network
 * drop) and the film is resumed where it was. [RESUMING]: the node's stream broke or the
 * receiver dropped the film mid-way, and the film is resumed without a reconnect.
 */
internal data class CastRecovery(val kind: Kind, val device: String, val position: Double) {
    enum class Kind { SEEKING, RESTARTING, GAVE_UP, RECONNECTED, RESUMING }
}

/** Session-owned Cast transport and media doorway; the API itself is intentionally short-lived. */
@Suppress(
    "TooGenericExceptionCaught", // Surface any planner, TLS, relay, or receiver failure.
    "TooManyFunctions",
    "LargeClass", // One owner of the receiver's media: seeks, stalls, reconnects and the sleep hold share its state.
    "LongParameterList", // Each seam is a test's hand on one real-world input: sockets, threads, time, power.
)
internal class CastManager(
    private val localMediaUrl: (String) -> String,
    private val connect: (CastDevice) -> CastChannel = ::CastConnection,
    private val dispatchReset: (() -> Unit) -> Unit = ::resetInBackground,
    private val discoverDevices: () -> List<CastDevice> = { CastDiscovery().discover() },
    /** Times receiver progress; tests drive it by hand. */
    private val clock: () -> Long = System::nanoTime,
    /**
     * Polls [refresh] while we own media, so a stall or a receiver-side seek is followed
     * even when no screen is showing the Cast controls. Tests pass a no-op and poll.
     */
    private val watch: (() -> Unit) -> AutoCloseable = ::watchInBackground,
    /** Read beside [clock] to notice a suspend: wall time runs through one, monotonic time doesn't. */
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** Keeps the machine awake while it relays a playing film; tests record it. */
    private val inhibitor: SleepInhibitor = SleepInhibitor.NONE,
) : AutoCloseable {
    var devices by mutableStateOf<List<CastDevice>>(emptyList())
        private set
    var selected by mutableStateOf<CastDevice?>(null)
        private set
    var status by mutableStateOf<CastStatus?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var recovery by mutableStateOf<CastRecovery?>(null)
        private set
    private var connection: CastChannel? = null
    private var relay: CastMediaRelay? = null
    private var target: PlaybackTarget? = null

    // Volatile, not locked: the UI reads castingTo/castingAsset while a recovery may hold
    // the lock for a receiver round-trip.
    @Volatile private var assetId: String? = null

    @Volatile private var ownedMediaSessionId: Int? = null
    private var title = ""
    private var sourceOffset = 0.0
    private var playIntent = false
    private var progress: Progress? = null
    private var recoveries = 0
    private var watcher: AutoCloseable? = null

    /** Film-absolute, last seen while our media played or paused: where a dropped film resumes. */
    private var lastFilm = 0.0

    /**
     * The node's stream is presumed cut: the machine slept (the node drops a client it cannot
     * write to), or a resume is under way and not yet loaded. A receiver still playing our
     * session is then restarted at once, and a session that vanished is resumed.
     */
    private var streamCut = false
    private var resumes = 0
    private var resumedAt = 0.0

    /** The Cast channel must be replaced before it is trusted again (the machine slept). */
    private var stale = false
    private var reconnected = false
    private var retries = 0
    private var retryAt = 0L
    private var lostAt: Long? = null

    /** The last poll's monotonic and wall times, nanoseconds and milliseconds. */
    private var tick: Pair<Long, Long>? = null
    private var sleepHold: AutoCloseable? = null
    private var holding = false
    private var pausedAt: Long? = null
    private val invalidation = AtomicInteger()
    private val completedInvalidation = AtomicInteger()
    private val invalidating: Boolean get() = invalidation.get() != completedInvalidation.get()

    /**
     * The receiver name while this app's own film plays there (heyarr-kmp#248): the local
     * player must not open a second stream of it, which is what preceded the wedged receiver.
     */
    val castingTo: String? get() = selected?.name?.takeIf { ownedMediaSessionId != null }

    /** The asset this app is casting, null when it owns no receiver media. */
    val castingAsset: String? get() = assetId?.takeIf { ownedMediaSessionId != null }

    fun discover(): List<CastDevice> = discoverDevices().also { devices = it }

    /** Attach to an existing media app without launching or loading anything. */
    @Synchronized
    fun attach(device: CastDevice): CastStatus? {
        try {
            check(!invalidating) { "Cast connection is resetting" }
            if (selected?.id != device.id && ownedMediaSessionId != null) {
                refresh()
                check(ownedMediaSessionId == null) { "Stop casting on the current receiver before switching controls" }
            }
            ensureConnection(device)
            return refresh()
        } catch (failure: Exception) {
            error = failure.message ?: "Could not attach to Cast receiver"
            throw failure
        }
    }

    @Synchronized
    fun play(device: CastDevice, api: HeyarrApi, assetId: String, title: String = "Media") {
        try {
            check(!invalidating) { "Cast connection is resetting" }
            require(assetId.isNotBlank()) { "This item has no playable asset" }
            val plan = api.playbackTarget(assetId, "", strict = true)
            // A direct MP4 may still contain HEVC or multichannel AAC when the node
            // cannot transcode. Only the planner's profile-converted stream is known safe.
            require(plan.restartSeekable && plan.mime == "video/mp4") {
                "Playback planner did not provide a Cast-compatible H.264/stereo stream"
            }
            val next = ensureConnection(device)
            // A media app this app does not own healthily is never reused: a wedged
            // Default Media Receiver stalls every LOAD into its session identically, even
            // from a restarted app (heyarr-kmp#248). A fresh launch costs a splash screen.
            if (!ownsHealthyMedia(next)) quitReceiver(next)
            val doorway = CastMediaRelay(device.address, URI.create(localMediaUrl(plan.url)))
            val mediaSessionId = try {
                next.load(doorway.url, title, duration = plan.durationSeconds)
                check(!invalidating) { "Cast connection was reset" }
                requireNotNull(next.status?.mediaSessionId) { "Cast receiver did not open a media session" }
            } catch (failure: Exception) {
                doorway.close()
                throw failure
            }
            relay?.close()
            relay = doorway
            target = plan
            this.assetId = assetId
            ownedMediaSessionId = mediaSessionId
            this.title = title
            sourceOffset = 0.0
            playIntent = true
            progress = null
            recoveries = 0
            recovery = null
            selected = device
            status = next.status
            error = null
            lastFilm = 0.0
            streamCut = false
            resumes = 0
            if (watcher == null) watcher = watch(::poll)
            holdSleep()
        } catch (failure: Exception) {
            error = failure.message ?: "Cast playback failed"
            throw failure
        }
    }

    @Synchronized
    fun refresh(): CastStatus? {
        val connection = connection ?: return status
        try {
            val fresh = connection.refresh()
            val fate = settle(fresh)
            if (fate == Fate.DROPPED && !invalidating) {
                // The receiver dropped our film mid-way, most likely having played out the
                // buffer of a stream the node cut while this machine slept. Put it back.
                resumeAt(connection, lastFilm, relaunch = true)
            } else {
                noteIntent(fresh)
                publish(fresh)
                error = connection.error ?: status?.error?.let { "Cast receiver reported $it" }
                if (fate == Fate.OURS && fresh != null && !invalidating) follow(connection, fresh)
            }
            reconnected = false
        } catch (failure: Exception) {
            error = failure.message ?: "Cast status unavailable"
        } finally {
            holdSleep()
        }
        return status
    }

    /** What [fresh] means for the media we own (null: none), releasing it when it is no longer ours. */
    private fun settle(fresh: CastStatus?): Fate? {
        val owned = ownedMediaSessionId ?: return null
        val cut = streamCut || relay?.severed == true
        val fate = fateOf(fresh, owned, lastFilm, target?.durationSeconds, cut)
        // Another sender can replace the media without replacing the receiver app. Its
        // status and seeks must never reuse our previous film's plan. A stop from the
        // TV's remote, or the film's real end, also releases it.
        if (fate == Fate.FOREIGN || fate == Fate.ENDED) clearOwnedMedia()
        return fate
    }

    /** The receiver's own play and pause (its remote, another sender) are what we mean to do too. */
    private fun noteIntent(fresh: CastStatus?) {
        when (fresh?.state) {
            "PLAYING" -> playIntent = true
            "PAUSED", "PAUSED_PLAYBACK" -> playIntent = false
        }
    }

    /**
     * Our film's receiver time is relative to the last restart, and its reported duration
     * is only the buffered end of a live repackage. Shown, both are film-absolute: the
     * offset added, the plan's duration or none. Someone else's media is reported as is.
     */
    private fun publish(fresh: CastStatus?) {
        val plan = target
        status = fresh?.copy(
            position = fresh.position + sourceOffset,
            duration = if (plan != null) plan.durationSeconds else fresh.duration,
        )
    }

    /** Acts on what the receiver's progress says: a cut stream, an unreachable seek or a stall. */
    private fun follow(connection: CastChannel, fresh: CastStatus) {
        val film = sourceOffset + fresh.position
        val live = fresh.state in LIVE
        if (live) lastFilm = film
        if (live && (streamCut || relay?.severed == true)) {
            // Still playing our film from its buffer, but nothing more will reach it. Restart
            // now, while the buffer lasts: a short reload instead of a stop minutes later.
            // (The receiver's buffered end is not observable once LOAD declares the film's
            // duration, so "when the buffer runs low" can't be timed reliably.)
            resumeAt(connection, film, relaunch = false)
            return
        }
        val judged = judge(progress, fresh, clock(), playIntent)
        progress = judged.progress
        if (judged.played) {
            // Playing again: a recovery worked, so the next stall gets a full budget.
            recoveries = 0
            if (recovery?.kind != CastRecovery.Kind.GAVE_UP) recovery = null
            // A resume that has played on for a while worked; one that keeps failing at the
            // same point (a node that can't get past it) must not loop forever.
            if (film >= resumedAt + RESUME_PROOF_SECONDS) resumes = 0
        }
        val device = selected?.name ?: "The receiver"
        when (judged.verdict) {
            Verdict.STEADY -> Unit

            Verdict.UNREACHED_SEEK -> {
                // The receiver (its remote, Google TV's controls, another sender) jumped
                // past what the repackage has produced and cannot fetch ahead in it. The
                // film is restarted at that point, as the app's own scrubber does.
                val landing = restartSeek(film, target?.durationSeconds, playIntent = true).position
                recovery = CastRecovery(CastRecovery.Kind.SEEKING, device, landing)
                loadAt(connection, landing, autoplay = true)
            }

            Verdict.STALLED -> if (recoveries >= MAX_RECOVERIES) {
                // Said once (the toast), but kept as the error every poll would otherwise clear.
                if (recovery?.kind != CastRecovery.Kind.GAVE_UP) {
                    recovery = CastRecovery(CastRecovery.Kind.GAVE_UP, device, film)
                }
                error = "$device keeps stalling. Stop and cast again, or restart the receiver."
            } else {
                recoveries++
                recovery = CastRecovery(CastRecovery.Kind.RESTARTING, device, film)
                quitReceiver(connection)
                loadAt(connection, film, autoplay = true)
            }
        }
    }

    /**
     * Puts our film back on the receiver at [at]: into its current session when it still
     * plays ours ([relaunch] false), else into a freshly launched media app. Said once, as
     * [CastRecovery.Kind.RECONNECTED] when the channel was just replaced. After
     * [MAX_RESUMES] without the film playing on, it stops and says so instead.
     */
    private fun resumeAt(connection: CastChannel, at: Double, relaunch: Boolean) {
        val device = selected?.name ?: "The receiver"
        if (resumes >= MAX_RESUMES) {
            clearOwnedMedia()
            recovery = CastRecovery(CastRecovery.Kind.GAVE_UP, device, at)
            error = "$device keeps dropping the film. Cast it again to retry."
            return
        }
        resumes++
        resumedAt = at
        val kind = if (reconnected) CastRecovery.Kind.RECONNECTED else CastRecovery.Kind.RESUMING
        recovery = CastRecovery(kind, device, restartSeek(at, target?.durationSeconds, playIntent).position)
        // Until the LOAD lands, a vanished session is still ours to restore: a failure here
        // is retried on the next poll rather than read as the user's stop.
        streamCut = true
        if (relaunch) quitReceiver(connection)
        loadAt(connection, at, autoplay = playIntent)
        streamCut = false
    }

    /**
     * The watcher's beat: notice a suspend, replace a dead channel, then read the receiver.
     * Runs only while we own media, so reconnecting never outlives the cast.
     */
    @Synchronized
    private fun poll() {
        if (ownedMediaSessionId == null) return
        noteWake()
        val channel = connection?.takeIf { !stale && it.error == null } ?: reconnect()
        if (channel != null) refresh() else holdSleep()
    }

    /**
     * The monotonic clock stands still while the machine sleeps and the wall clock does not.
     * A gap between them means a suspend: the Cast socket is dead (or soon found so, after a
     * heartbeat timeout), and the node has dropped a stream it could not write to.
     */
    private fun noteWake() {
        val mono = clock()
        val wall = wallClock()
        val last = tick
        tick = mono to wall
        if (last == null) return
        val slept = (wall - last.second) - TimeUnit.NANOSECONDS.toMillis(mono - last.first)
        if (slept < WAKE_GAP_MS) return
        stale = true
        streamCut = true
        // The receiver played on while we slept; that is not a seek.
        progress = null
        retries = 0
        retryAt = 0L
        lostAt = null
    }

    /**
     * Replaces a dead channel to the receiver we are casting to, backing off between tries.
     * Ownership is kept meanwhile (the local player still defers to the cast); after
     * [RECONNECT_GIVE_UP_SECONDS] of failures the cast is let go.
     */
    private fun reconnect(): CastChannel? {
        val device = selected ?: return null
        val now = clock()
        val since = lostAt ?: now.also { lostAt = it }
        return when {
            now - since >= TimeUnit.SECONDS.toNanos(RECONNECT_GIVE_UP_SECONDS) -> {
                connection?.close()
                connection = null
                status = null
                clearOwnedMedia()
                error = "Lost ${device.name}. Cast again once it is back."
                null
            }

            now < retryAt -> null

            else -> tryConnect(device, now)
        }
    }

    private fun tryConnect(device: CastDevice, now: Long): CastChannel? {
        connection?.close()
        connection = null
        return try {
            connect(device).also {
                connection = it
                stale = false
                reconnected = true
                retries = 0
                retryAt = 0L
                lostAt = null
                progress = null
            }
        } catch (failure: Exception) {
            retries++
            retryAt = now + TimeUnit.SECONDS.toNanos(backoffSeconds(retries))
            error = "Reconnecting to ${device.name}: ${failure.message ?: "unreachable"}"
            null
        }
    }

    /**
     * Holds the machine awake while our film plays on a receiver, and for [PAUSE_GRACE_SECONDS]
     * after a pause: the TV's remote can resume it while the laptop is idle, and releasing
     * at once would let an idle laptop suspend straight away and cut the stream it then
     * needs. A longer pause releases it; a resume takes it again.
     */
    private fun holdSleep() {
        val owned = ownedMediaSessionId != null
        val now = clock()
        val paused = if (!owned || playIntent) null else pausedAt ?: now
        pausedAt = paused
        val wanted = owned && (paused == null || now - paused < TimeUnit.SECONDS.toNanos(PAUSE_GRACE_SECONDS))
        if (wanted && !holding) {
            holding = true
            val device = selected?.name ?: "a Cast receiver"
            // Null (no inhibitor here) is remembered too: it is not retried every poll.
            sleepHold = runCatching { inhibitor.acquire("Casting $title to $device") }.getOrNull()
        } else if (!wanted && holding) {
            holding = false
            runCatching { sleepHold?.close() }
            sleepHold = null
        }
    }

    @Synchronized
    fun control(action: String, seconds: Double? = null) {
        try {
            check(!invalidating) { "Cast connection is resetting" }
            if (unreachable()) {
                // "Play here" must work while the receiver is unreachable: let the cast go.
                if (action == "stop") {
                    clearOwnedMedia()
                    return
                }
                error("Reconnecting to ${selected?.name ?: "the receiver"}")
            }
            val connection = connection ?: error("No Cast receiver connected")
            if (ownedMediaSessionId != null && connection.status?.mediaSessionId != ownedMediaSessionId) {
                clearOwnedMedia()
            }
            if (action == "seek" && target?.restartSeekable == true) {
                val restart = restartSeek(requireNotNull(seconds), target?.durationSeconds, playIntent)
                loadAt(connection, restart.position, restart.autoplay)
            } else {
                connection.control(action, seconds)
                if (action == "pause") playIntent = false
                if (action == "resume") playIntent = true
            }
            if (action == "stop") {
                clearOwnedMedia()
            }
            refresh()
            holdSleep()
        } catch (failure: Exception) {
            error = failure.message ?: "Cast control failed"
            throw failure
        }
    }

    /** We own media but have no live channel to its receiver: a reconnect is pending. */
    private fun unreachable(): Boolean {
        val current = connection
        return ownedMediaSessionId != null && (current == null || stale || current.error != null)
    }

    /**
     * A restart seek: a new receiver-scoped doorway and a LOAD of the node's stream from
     * film time [at]. The receiver's clock starts again at zero, so [sourceOffset] keeps
     * what the user sees film-absolute; the declared duration is what remains.
     */
    private fun loadAt(connection: CastChannel, at: Double, autoplay: Boolean) {
        // Offset plus receiver time is binary noise past the millisecond the node's -ss keeps.
        val position = Math.round(at * MILLIS) / MILLIS
        val plan = requireNotNull(target)
        val base = requireNotNull(plan.streamBaseUrl)
        val doorway = CastMediaRelay(requireNotNull(selected).address, URI.create(localMediaUrl(base)))
        val remaining = plan.durationSeconds?.let { (it - position).coerceAtLeast(0.0) }
        val mediaSessionId = try {
            connection.load("${doorway.url}?start=$position", title, autoplay = autoplay, duration = remaining)
            requireNotNull(connection.status?.mediaSessionId) { "Cast receiver did not open a media session" }
        } catch (failure: Exception) {
            doorway.close()
            throw failure
        }
        relay?.close()
        relay = doorway
        ownedMediaSessionId = mediaSessionId
        sourceOffset = position
        lastFilm = position
        playIntent = autoplay
        // A new media session's clock restarts: the old trajectory must not read as a jump.
        progress = null
        status = status?.copy(position = position, state = if (autoplay) "PLAYING" else "PAUSED")
    }

    /**
     * Best effort: a receiver that refuses the STOP still gets the LOAD, into its current
     * session, which is all a Cast did before; a dead connection fails on that LOAD anyway.
     */
    private fun quitReceiver(channel: CastChannel) {
        runCatching { channel.stopReceiver() }
    }

    /** Whether the receiver's media is ours and was last seen playing or paused, not stuck. */
    private fun ownsHealthyMedia(channel: CastChannel): Boolean {
        val current = channel.status ?: return false
        return ownedMediaSessionId != null && current.mediaSessionId == ownedMediaSessionId &&
            current.state in setOf("PLAYING", "PAUSED", "PAUSED_PLAYBACK") && recovery == null
    }

    @Synchronized
    private fun ensureConnection(device: CastDevice): CastChannel {
        // A pending reconnect leaves no connection (or a stale one): casting again opens a fresh one.
        connection?.takeIf { selected?.id == device.id && it.error == null && !stale }?.let { return it }
        connection?.close()
        connection = null
        clearOwnedMedia()
        selected = null
        status = null
        return connect(device).also {
            connection = it
            stale = false
            selected = device
        }
    }

    /** A node or credential change revokes the relay off the UI thread before another Cast starts. */
    fun invalidate() {
        val id = invalidation.incrementAndGet()
        dispatchReset { resetIfCurrent(id) }
    }

    @Synchronized
    private fun resetIfCurrent(id: Int) {
        // Reset callbacks can run out of order. A stale callback must not close a
        // new connection opened after a newer reset has already finished.
        if (invalidation.get() == id) {
            try {
                close()
            } finally {
                // If another invalidation arrived while close was running, marking
                // only this generation complete keeps new playback gated on it.
                completedInvalidation.set(id)
            }
        }
    }

    @Synchronized
    override fun close() {
        clearOwnedMedia()
        connection?.close()
        connection = null
        selected = null
        status = null
        error = null
    }

    private fun clearOwnedMedia() {
        watcher?.close()
        watcher = null
        relay?.close()
        relay = null
        target = null
        assetId = null
        ownedMediaSessionId = null
        sourceOffset = 0.0
        title = ""
        playIntent = false
        progress = null
        recoveries = 0
        recovery = null
        lastFilm = 0.0
        streamCut = false
        resumes = 0
        resumedAt = 0.0
        reconnected = false
        retries = 0
        retryAt = 0L
        lostAt = null
        tick = null
        holdSleep()
    }

    companion object {
        internal data class RestartSeek(val position: Double, val autoplay: Boolean)

        /**
         * The receiver's last seen time, and when it last moved forward in play.
         * [jumped]: it leapt past where playback could have reached since, which only a
         * seek does, and has not played on from there yet.
         */
        internal data class Progress(
            val position: Double,
            val state: String,
            val seenAt: Long,
            val advancedAt: Long,
            val jumped: Boolean,
        )

        internal enum class Verdict { STEADY, UNREACHED_SEEK, STALLED }

        /**
         * What a receiver report means for the media we own. [OURS]: still ours. [FOREIGN]:
         * someone else's media replaced it. [ENDED]: let it go (a stop, the film's end).
         * [DROPPED]: it ended mid-film without anyone stopping it, so it is resumed.
         */
        internal enum class Fate { OURS, FOREIGN, ENDED, DROPPED }

        /** [played]: the clock moved forward in play since the last report, the only proof of health. */
        internal data class Judgement(val progress: Progress, val verdict: Verdict, val played: Boolean)

        /** A jump smaller than this is poll jitter or a short buffering catch-up, not a seek. */
        internal const val SEEK_TOLERANCE_SECONDS = 5.0

        /** A seek past the buffered end leaves the receiver waiting for bytes that never come. */
        internal const val SEEK_GRACE_SECONDS = 4L

        /**
         * No progress while meant to play for this long is a wedged receiver. The node's
         * first fragment of a restarted 4K tone-mapped transcode takes a few seconds; the
         * observed wedge lasted minutes.
         */
        internal const val STALL_SECONDS = 20L

        /** Relaunches without any progress in between before the user is asked to step in. */
        internal const val MAX_RECOVERIES = 2

        /** Resumes of a dropped or cut film without it playing [RESUME_PROOF_SECONDS] on in between. */
        internal const val MAX_RESUMES = 3

        /** How far a resumed film must play on before its resume counts as having worked. */
        internal const val RESUME_PROOF_SECONDS = 60.0

        /**
         * A session that ends this close to the film's end ended with the film: credits, or
         * the transcode's last frames, not a dropped stream. Not resumed.
         */
        internal const val END_MARGIN_SECONDS = 60.0

        /** A wall-clock lead over the monotonic clock this large between two polls is a suspend. */
        internal const val WAKE_GAP_MS = 10_000L

        /** Failed reconnects for this long (awake time) let the cast go. */
        internal const val RECONNECT_GIVE_UP_SECONDS = 600L

        /** How long a paused cast keeps the machine awake. */
        internal const val PAUSE_GRACE_SECONDS = 600L

        private const val MAX_BACKOFF_SECONDS = 30L

        private const val PROGRESS_EPSILON = 0.25
        private const val MILLIS = 1_000.0
        private const val WATCH_MS = 2_000L
        private val STILL = setOf("PAUSED", "PAUSED_PLAYBACK", "IDLE")

        /** Our media with a pipeline behind it: what a cut stream restarts and a resume returns to. */
        private val LIVE = setOf("PLAYING", "BUFFERING", "PAUSED", "PAUSED_PLAYBACK")

        private fun resetInBackground(task: () -> Unit) {
            Thread(task, "heyarr-cast-reset").apply {
                isDaemon = true
                start()
            }
        }

        private fun watchInBackground(task: () -> Unit): AutoCloseable {
            val running = AtomicBoolean(true)
            Thread({
                while (running.get()) {
                    try {
                        Thread.sleep(WATCH_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (running.get()) runCatching(task)
                }
            }, "heyarr-cast-watch").apply {
                isDaemon = true
                start()
            }
            return AutoCloseable { running.set(false) }
        }

        /** 1, 2, 4, … seconds between reconnect attempts, at most [MAX_BACKOFF_SECONDS]. */
        internal fun backoffSeconds(failures: Int): Long =
            (1L shl (failures - 1).coerceIn(0, Long.SIZE_BITS - 2)).coerceAtMost(MAX_BACKOFF_SECONDS)

        /**
         * Reads a receiver report against the media session we own. A session that went IDLE
         * says why: CANCELLED is a stop (from the TV's remote too) and is never undone;
         * ERROR or INTERRUPTED mid-film is resumed; FINISHED mid-film is the receiver playing
         * out a buffer the node stopped filling, resumed when the film's length shows it
         * ended early (or the stream is known to be [cut]). A session that vanished without
         * a word, typically while our channel was down, is resumed only when the stream is
         * known to be cut; otherwise it is taken as stopped. Nothing near the film's end is
         * resumed. An IDLE report with no reason is the moment around a LOAD, still ours.
         */
        internal fun fateOf(fresh: CastStatus?, owned: Int, lastFilm: Double, duration: Double?, cut: Boolean): Fate {
            val session = fresh?.mediaSessionId
            val nearEnd = duration != null && lastFilm >= duration - END_MARGIN_SECONDS
            return when {
                session != null && session != owned -> Fate.FOREIGN
                session == owned && !endedWithReason(fresh) -> Fate.OURS
                !nearEnd && droppedFor(fresh?.idleReason, duration != null, cut) -> Fate.DROPPED
                else -> Fate.ENDED
            }
        }

        private fun endedWithReason(status: CastStatus?): Boolean = status?.state == "IDLE" && status.idleReason != null

        /** Whether a session that ended mid-film for [reason] (null: unseen) was dropped rather than stopped. */
        private fun droppedFor(reason: String?, knownLength: Boolean, cut: Boolean): Boolean = when (reason) {
            "CANCELLED" -> false
            "ERROR", "INTERRUPTED" -> true
            "FINISHED" -> knownLength || cut
            else -> cut
        }

        internal fun restartSeek(seconds: Double, duration: Double?, playIntent: Boolean): RestartSeek {
            require(seconds.isFinite()) { "Invalid Cast seek position" }
            val end = duration?.takeIf { it.isFinite() && it >= 0 } ?: Double.MAX_VALUE
            return RestartSeek(seconds.coerceIn(0.0, end), playIntent)
        }

        /**
         * Reads one receiver report against the last. The Default Media Receiver plays a
         * live repackage as a progressive download it cannot range-fetch: a seek inside
         * what it buffered works natively and needs nothing from us, but a seek past it
         * jumps the clock and then waits forever. So a forward jump only matters once the
         * receiver sits on it for [SEEK_GRACE_SECONDS]; any other lack of progress while
         * meant to play is a stall after [STALL_SECONDS]. Paused time never counts.
         */
        internal fun judge(previous: Progress?, next: CastStatus, now: Long, wantsPlay: Boolean): Judgement {
            val fresh = Progress(next.position, next.state, now, now, jumped = false)
            if (previous == null) return Judgement(fresh, Verdict.STEADY, false)
            val jumped = next.position > reach(previous, next, now) + SEEK_TOLERANCE_SECONDS
            val rewound = next.position < previous.position - SEEK_TOLERANCE_SECONDS
            val played = !jumped && next.state == "PLAYING" && next.position > previous.position + PROGRESS_EPSILON
            val still = next.state in STILL
            val progress = when {
                jumped -> fresh.copy(jumped = true)

                // A rewind lands in what was already buffered; give it a fresh baseline.
                rewound || played || still -> fresh

                else -> previous.copy(position = next.position, state = next.state, seenAt = now)
            }.let { if (still) it.copy(advancedAt = now) else it } // paused time never counts; a paused jump does
            val verdict = if (wantsPlay && !still) verdictOf(progress, now) else Verdict.STEADY
            return Judgement(progress, verdict, played)
        }

        /** The furthest the receiver's clock could have run since [previous] without a seek. */
        private fun reach(previous: Progress, next: CastStatus, now: Long): Double {
            val elapsed = (now - previous.seenAt).toDouble() / TimeUnit.SECONDS.toNanos(1)
            val moving = previous.state == "PLAYING" || next.state == "PLAYING"
            return previous.position + if (moving) elapsed else 0.0
        }

        private fun verdictOf(progress: Progress, now: Long): Verdict {
            val stuck = TimeUnit.NANOSECONDS.toSeconds(now - progress.advancedAt)
            return when {
                progress.jumped && stuck >= SEEK_GRACE_SECONDS -> Verdict.UNREACHED_SEEK
                stuck >= STALL_SECONDS -> Verdict.STALLED
                else -> Verdict.STEADY
            }
        }
    }
}
