package one.rarebit.heyarr.mobile.cast

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.core.cast.CastTimeline
import one.rarebit.heyarr.core.cast.CastTimeline.Fate
import one.rarebit.heyarr.core.cast.CastTimeline.Verdict
import one.rarebit.heyarr.core.cast.ReceiverReport
import one.rarebit.heyarr.core.playback.ResumeItem
import one.rarebit.heyarr.mobile.playback.PlaybackTarget
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI

/**
 * What the manager is doing on the receiver's behalf, film-absolute, for the UI to say.
 * [SEEKING]: the receiver jumped to a point it cannot reach in a live repackage, so the film
 * is restarted there. [RESTARTING]: the receiver stopped making progress, so its media app is
 * quit and relaunched where it was. [GAVE_UP]: it kept stalling or dropping the film after
 * that. [RECONNECTED]: the Cast session came back after a network drop and the film is put
 * back where it was. [RESUMING]: the node's stream broke or the receiver dropped the film
 * mid-way, and the film is put back without a reconnect. [WAITING_FOR_UNLOCK]: a restart is
 * needed, and a locked phone cannot sign the request that would fetch it.
 */
data class CastRecovery(val kind: Kind, val device: String, val position: Double) {
    enum class Kind { SEEKING, RESTARTING, GAVE_UP, RECONNECTED, RESUMING, WAITING_FOR_UNLOCK }
}

/** Everything the Cast UI shows, film-absolute where it is our film. */
data class CastUiState(
    /** False when this phone has no Google Play services Cast framework: no Cast button at all. */
    val available: Boolean = false,
    val receiver: CastReceiver? = null,
    val live: Boolean = false,
    /** Our film on the receiver; null when the receiver plays nothing of ours. */
    val item: CastItem? = null,
    val state: String? = null,
    val position: Double = 0.0,
    val duration: Double? = null,
    val route: CastRoute? = null,
    val recovery: CastRecovery? = null,
    val error: String? = null,
)

/** What a cast tells the resume points (heyarr-kmp#232): a tick, a stop or pause, the real end. */
enum class CastProgress { TICK, CHECKPOINT, ENDED }

/**
 * Keeps the phone awake while it relays a film (heyarr-kmp#250): [CastRelayService] on a
 * phone. [update] with null releases everything.
 */
fun interface CastHold {
    /** [locks]: hold the wake and Wi-Fi locks; false keeps only the service (a long pause). */
    data class Hold(val title: String, val device: String, val locks: Boolean)

    fun update(hold: Hold?)
}

/** A restart is needed and this phone is locked: its credential cannot be signed for the fetch. */
class CastLocked : IllegalStateException("Unlock this phone: it relays the film, and a locked phone cannot sign for it")

/**
 * The Google Cast session's media, owned by this phone (heyarr-kmp#250): the desktop's
 * `CastManager` (#242, #249) on Android, over the Cast SDK ([CastChannel]) instead of a
 * hand-rolled Cast v2 socket. The receiver policy itself is `:core`'s [CastTimeline].
 *
 *  - **Film-absolute.** The node streams a live fMP4 repackage, restarted with `?start=` to
 *    seek. The receiver's clock restarts at zero on each LOAD and its duration is only the
 *    buffered end, so every seek is a restart at film time ([sourceOffset] plus receiver
 *    time), LOAD declares what remains of the film, and the UI shows offset-plus-receiver
 *    time over the plan's duration.
 *  - **A fresh media app.** A Default Media Receiver whose session we do not own healthily is
 *    never reused: a wedged one stalls every LOAD into it (heyarr-kmp#248).
 *  - **Following the receiver.** While it owns media it polls every [WATCH_MS]: a forward jump
 *    the receiver sits on (its remote, the lock screen) is restarted there; no progress while
 *    meant to play is a stall, answered by relaunching the receiver where it was, twice at most.
 *  - **Resume, not revive.** A film dropped mid-way (an ERROR, a cut stream, the receiver
 *    playing out its buffer after this phone fell off the network) is put back where it was;
 *    a user's stop, from any sender or the TV's remote, and the film's real end are final.
 *  - **Reconnect.** The SDK rides out a short drop itself (a suspended session). If it gives
 *    up, the receiver is rejoined with a backoff for [CastTimeline.RECONNECT_GIVE_UP_SECONDS].
 *  - **Reachability.** Each cast is routed by [CastRouting.decide]; a relayed one runs through
 *    a [MediaDoorway] made per LOAD, kept awake by [hold].
 *
 * Every entry point takes [mutex]: the watcher, the session events and the UI's controls
 * would otherwise interleave a LOAD with a poll that reads the old session's clock.
 */
@Suppress(
    "TooGenericExceptionCaught", // Surface any planner, relay or receiver failure as the Cast panel's error.
    "LongParameterList", // Each is a seam the tests drive: the receiver, the relay, DNS, signing, the hold, time.
    "TooManyFunctions",
    "LargeClass", // One owner of the receiver's media: seeks, stalls, resumes and reconnects share its state.
)
class CastManager(
    private val channel: CastChannel,
    /** A relay to [receiver] for [upstream]; [CastRelay] on a phone. Called on [io]. */
    private val doorway: (receiver: Inet4Address, upstream: String) -> MediaDoorway,
    /** Resolves the node's host for [CastRouting.decide]. Called on [io]. */
    private val resolve: (host: String) -> InetAddress? = { runCatching { InetAddress.getByName(it) }.getOrNull() },
    /** Whether a request can be signed now (false on a locked phone). Called on [io]. */
    private val canSign: () -> Boolean = { true },
    private val hold: CastHold = CastHold { },
    /** Monotonic nanoseconds; tests drive it by hand. */
    private val clock: () -> Long = System::nanoTime,
    private val io: CoroutineDispatcher,
    /** Runs the watcher; null in tests, which call [poll] themselves. */
    private val scope: CoroutineScope? = null,
    available: Boolean = true,
) {
    private val _state = MutableStateFlow(CastUiState(available = available))
    val state: StateFlow<CastUiState> = _state.asStateFlow()

    /**
     * Plans [CastItem.assetId] with [CastRouting.PROFILE] through the signed-in session's
     * node and credential; null signed out. The shell sets it as the session changes.
     */
    @Volatile
    var planner: (suspend (assetId: String) -> CastSource)? = null

    /** Resume points for our film, film-absolute. */
    var onProgress: (item: ResumeItem, event: CastProgress, film: Double, duration: Double?) -> Unit = { _, _, _, _ -> }

    /** A session came up while this phone owned no receiver media: the shell hands a playing film over. */
    var onConnected: () -> Unit = {}

    private val mutex = Mutex()
    private var source: CastSource? = null
    private var item: CastItem? = null
    private var route: CastRoute? = null
    private var directFailed = false
    private var relay: MediaDoorway? = null
    private var ownedMediaSessionId: Int? = null
    private var sourceOffset = 0.0
    private var playIntent = false
    private var progress: CastTimeline.Progress? = null
    private var recoveries = 0
    private var recovery: CastRecovery? = null
    private var error: String? = null
    private var lastReport: ReceiverReport? = null

    /** Film-absolute, last seen while our media played or paused: where a dropped film resumes. */
    private var lastFilm = 0.0

    /** A resume is under way and not yet loaded: a vanished session is still ours to restore. */
    private var streamCut = false
    private var resumes = 0
    private var resumedAt = 0.0

    /** The session came back after a suspension or a rejoin; said once, as [CastRecovery.Kind.RECONNECTED]. */
    private var reconnected = false

    /** The SDK ended the session on its own while we owned media: rejoin it. */
    private var lost = false
    private var retries = 0
    private var retryAt = 0L
    private var lostAt: Long? = null
    private var pausedAt: Long? = null
    private var wasPaused = false
    private var watcher: Job? = null

    /**
     * The asset this phone is casting, null when it owns no receiver media. Read off the main
     * thread by the playback gate ([CastGate]), so volatile rather than under [mutex].
     */
    @Volatile
    var castingAsset: String? = null
        private set

    /** A live Cast session: the gate sends a video to the TV. */
    val sessionLive: Boolean get() = channel.live

    /** The receiver name, for "Playing on …". */
    val castingTo: String? get() = channel.receiver?.name?.takeIf { castingAsset != null }

    // ── Playing ───────────────────────────────────────────────────────────────────

    /**
     * Casts [cast] to the session's receiver: plans it Cast-safe, routes it, quits a media app
     * we do not own healthily, and LOADs it at [CastItem.startSeconds]. Failure is the result,
     * and the panel's error; nothing of a previous film is left half-replaced.
     */
    suspend fun play(cast: CastItem): Result<Unit> = mutex.withLock {
        try {
            val receiver = channel.receiver?.takeIf { channel.live } ?: error("No Cast receiver is connected")
            val plan = planner ?: error("Sign in to cast")
            val next = withContext(io) { plan(cast.assetId) }
            check(next.mime == "video/mp4") {
                "The server offered a ${next.mime} stream, which a Cast receiver cannot play"
            }
            val relaunch = channel.report() != null && !ownsHealthyMedia()
            // The film we owned until now is left where it was, for Continue watching.
            checkpoint()
            clearOwnedMedia()
            source = next
            item = cast
            route = withContext(io) {
                CastRouting.decide(next, receiver.address) { URI(next.url).host?.let(resolve) }
            }
            val start = CastTimeline.restartSeek(cast.startSeconds, next.durationSeconds, cast.autoplay)
            loadAt(start.position, start.autoplay, relaunchFirst = relaunch)
            error = null
            startWatch()
            Result.success(Unit)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = failure.message ?: "Cast playback failed"
            Result.failure(failure)
        } finally {
            publish()
        }
    }

    /** What the UI's controls ask for. A seek is film-absolute. */
    enum class Control { PAUSE, RESUME, SEEK, STOP }

    suspend fun control(action: Control, seconds: Double? = null): Result<Unit> = mutex.withLock {
        try {
            if (ownedMediaSessionId != null && !channel.live) {
                // "Play here" must work while the receiver is unreachable: let the cast go.
                if (action == Control.STOP) {
                    checkpoint()
                    clearOwnedMedia()
                    return@withLock Result.success(Unit)
                }
                error("Reconnecting to ${deviceName()}")
            }
            when (action) {
                Control.PAUSE -> {
                    channel.pause()
                    playIntent = false
                }

                Control.RESUME -> {
                    channel.play()
                    playIntent = true
                }

                Control.SEEK -> {
                    val plan = checkNotNull(source) { "Nothing of this phone's is casting" }
                    // TODO(heyarr-core#908): a seekable HLS source seeks natively instead.
                    check(plan.restartSeekable) { "This stream cannot seek" }
                    val restart = CastTimeline.restartSeek(requireNotNull(seconds), plan.durationSeconds, playIntent)
                    loadAt(restart.position, restart.autoplay)
                }

                Control.STOP -> {
                    runCatching { channel.stop() }
                    checkpoint()
                    clearOwnedMedia()
                }
            }
            refreshLocked()
            Result.success(Unit)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = failure.message ?: "Cast control failed"
            Result.failure(failure)
        } finally {
            publish()
        }
    }

    /** The receiver's volume, 0 to 1; null with no session. Not the film's state, so not under [mutex]. */
    fun volume(): Double? = channel.volume()

    fun setVolume(level: Double) = channel.setVolume(level.coerceIn(0.0, 1.0))

    /** The Cast framework finished starting (or found no Play services): whether to show a Cast button. */
    fun setAvailable(available: Boolean) {
        _state.value = _state.value.copy(available = available)
    }

    // ── Following the receiver ────────────────────────────────────────────────────

    /** Reads the receiver now (the UI's refresh, a control's echo). */
    suspend fun refresh() = mutex.withLock {
        refreshLocked()
        publish()
    }

    /** The watcher's beat: rejoin a lost session, else read the receiver. Only while we own media. */
    suspend fun poll() = mutex.withLock {
        if (ownedMediaSessionId == null) return@withLock
        if (lost) rejoin() else refreshLocked()
        publish()
    }

    private suspend fun refreshLocked() {
        if (!channel.live) return
        val fresh = channel.report()
        val owned = ownedMediaSessionId
        if (owned == null) {
            lastReport = fresh
            return
        }
        try {
            val cut = streamCut || relay?.severed == true || reconnected
            when (CastTimeline.fateOf(fresh, owned, lastFilm, source?.durationSeconds, cut)) {
                // Another sender replaced our media without replacing the receiver app: its
                // status and seeks must never reuse our film's plan or offset.
                Fate.FOREIGN -> {
                    checkpoint()
                    clearOwnedMedia()
                }

                // A stop from the TV's remote or another sender, or the film's real end: final.
                Fate.ENDED -> {
                    if (fresh?.idleReason == "FINISHED") ended() else checkpoint()
                    clearOwnedMedia()
                }

                // Dropped mid-film, most likely having played out the buffer of a stream that
                // broke while this phone slept or left the network. Put it back.
                Fate.DROPPED -> resumeAt(lastFilm, relaunch = true)

                Fate.OURS -> follow(requireNotNull(fresh))
            }
        } finally {
            reconnected = false
        }
    }

    /** Acts on what our film's progress says: a cut stream, an unreachable seek or a stall. */
    private suspend fun follow(fresh: ReceiverReport) {
        when (fresh.state) {
            "PLAYING" -> playIntent = true
            "PAUSED", "PAUSED_PLAYBACK" -> playIntent = false
        }
        lastReport = fresh
        val film = sourceOffset + fresh.position
        val live = fresh.state in CastTimeline.LIVE
        if (live) lastFilm = film
        record(fresh, film)
        if (live && (streamCut || relay?.severed == true)) {
            // Still playing our film from its buffer, but nothing more will reach it. Restart
            // now, while the buffer lasts: a short reload instead of a stop minutes later.
            resumeAt(film, relaunch = false)
            return
        }
        val judged = CastTimeline.judge(progress, fresh, clock(), playIntent)
        progress = judged.progress
        if (judged.played) {
            // Playing again: a recovery worked, so the next stall gets a full budget.
            recoveries = 0
            if (recovery?.kind != CastRecovery.Kind.GAVE_UP) recovery = null
            // A resume that has played on for a while worked; one that keeps failing at the
            // same point (a node that can't get past it) must not loop forever.
            if (film >= resumedAt + CastTimeline.RESUME_PROOF_SECONDS) resumes = 0
            if (error != null && relay?.credentialBlocked != true) error = null
        }
        act(judged.verdict, film)
    }

    /** The recovery a [verdict] on our film at [film] calls for. */
    private suspend fun act(verdict: Verdict, film: Double) {
        when (verdict) {
            Verdict.STEADY -> Unit

            Verdict.UNREACHED_SEEK -> recover {
                // The receiver (its remote, the lock screen's scrubber, another sender) jumped
                // past what the repackage has produced and cannot fetch ahead in it. The film
                // is restarted at that point, as the app's own scrubber does.
                val landing = CastTimeline.restartSeek(film, source?.durationSeconds, playIntent = true).position
                recovery = CastRecovery(CastRecovery.Kind.SEEKING, deviceName(), landing)
                loadAt(landing, autoplay = true)
            }

            Verdict.STALLED -> if (recoveries >= CastTimeline.MAX_RECOVERIES) {
                // Said once, but kept as the error every poll would otherwise clear.
                if (recovery?.kind != CastRecovery.Kind.GAVE_UP) {
                    recovery = CastRecovery(CastRecovery.Kind.GAVE_UP, deviceName(), film)
                }
                error = "${deviceName()} keeps stalling. Stop and cast again, or restart the receiver."
            } else {
                recover {
                    ensureSignable()
                    recoveries++
                    recovery = CastRecovery(CastRecovery.Kind.RESTARTING, deviceName(), film)
                    loadAt(film, autoplay = true, relaunchFirst = true)
                }
            }
        }
    }

    /**
     * Puts our film back on the receiver at [at]: into its current session when it still
     * plays ours ([relaunch] false), else into a freshly launched media app. After
     * [CastTimeline.MAX_RESUMES] without the film playing on, it stops and says so instead.
     * Only a LOAD that landed counts against that budget: one this phone could not make (it
     * is locked, or off the network) is tried again on the next poll.
     */
    private suspend fun resumeAt(at: Double, relaunch: Boolean) {
        if (resumes >= CastTimeline.MAX_RESUMES) {
            clearOwnedMedia()
            recovery = CastRecovery(CastRecovery.Kind.GAVE_UP, deviceName(), at)
            error = "${deviceName()} keeps dropping the film. Cast it again to retry."
            return
        }
        val kind = if (reconnected) CastRecovery.Kind.RECONNECTED else CastRecovery.Kind.RESUMING
        val landing = CastTimeline.restartSeek(at, source?.durationSeconds, playIntent).position
        // Until the LOAD lands, a vanished session is still ours to restore: a failure here
        // is retried on the next poll rather than read as the user's stop.
        streamCut = true
        recover {
            recovery = CastRecovery(kind, deviceName(), landing)
            loadAt(at, autoplay = playIntent, relaunchFirst = relaunch)
            resumes++
            resumedAt = at
            streamCut = false
        }
    }

    /** Runs one recovery step; a locked phone waits for the unlock, anything else is the panel's error. */
    private suspend fun recover(step: suspend () -> Unit) {
        try {
            step()
        } catch (_: CastLocked) {
            recovery = CastRecovery(CastRecovery.Kind.WAITING_FOR_UNLOCK, deviceName(), lastFilm)
            error = CastLocked().message
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = failure.message ?: "Cast recovery failed"
        }
    }

    /**
     * A restart seek: a LOAD of the node's stream from film time [at], through a new doorway
     * when relayed. The receiver's clock starts again at zero, so [sourceOffset] keeps what
     * the user sees film-absolute; the declared duration is what remains. A direct LOAD the
     * receiver refuses is made again through the relay, which then carries the rest of the cast.
     */
    private suspend fun loadAt(at: Double, autoplay: Boolean, relaunchFirst: Boolean = false) {
        val plan = requireNotNull(source)
        val cast = requireNotNull(item)
        val position = CastTimeline.millis(at)
        val remaining = plan.durationSeconds?.let { (it - position).coerceAtLeast(0.0) }
        val direct = route is CastRoute.Direct && !directFailed
        if (!direct) ensureSignable()
        // Best effort: a receiver that refuses to quit still gets the LOAD, into its current session.
        if (relaunchFirst) runCatching { channel.relaunch() }
        val next = if (direct) null else withContext(io) { doorway(relayAddress(), plan.url) }
        val base = next?.url ?: plan.url
        val session = try {
            channel.load(CastLoad(PlaybackTarget.streamUrl(base, position), cast.title, plan.mime, autoplay, remaining))
        } catch (cancel: CancellationException) {
            next?.close()
            throw cancel
        } catch (failure: Exception) {
            next?.close()
            if (!direct) throw failure
            directFailed = true
            route = CastRoute.Relay("Relayed through this phone: the TV could not fetch from the server.")
            return loadAt(at, autoplay)
        }
        relay?.close()
        relay = next
        ownedMediaSessionId = session
        castingAsset = cast.assetId
        sourceOffset = position
        lastFilm = position
        playIntent = autoplay
        // A new media session's clock restarts: the old trajectory must not read as a jump.
        progress = null
        lastReport = ReceiverReport(0.0, if (autoplay) "PLAYING" else "PAUSED", session)
    }

    private fun relayAddress(): Inet4Address = channel.receiver?.address
        ?: error("The TV did not give its address, so this phone cannot relay to it")

    private suspend fun ensureSignable() {
        if (!withContext(io) { canSign() }) throw CastLocked()
    }

    /** Whether the receiver's media is ours and was last seen playing or paused, not stuck. */
    private fun ownsHealthyMedia(): Boolean {
        val current = channel.report() ?: return false
        return ownedMediaSessionId != null && current.mediaSessionId == ownedMediaSessionId &&
            current.state in CastTimeline.HEALTHY && recovery == null
    }

    // ── The session ───────────────────────────────────────────────────────────────

    /** The SDK started or resumed a session. */
    suspend fun sessionLive() {
        val handOver = mutex.withLock {
            if (ownedMediaSessionId != null) {
                // The receiver may have played on while the session was away; that is not a seek.
                reconnected = true
                lost = false
                progress = null
                refreshLocked()
            } else {
                lastReport = channel.report()
            }
            publish()
            ownedMediaSessionId == null
        }
        if (handOver) onConnected()
    }

    /** The SDK suspended the session (the network dropped); it reconnects on its own. */
    suspend fun sessionSuspended() = mutex.withLock { publish() }

    /**
     * The session ended. [lost]: the SDK gave up on it, so a film we owned is rejoined with a
     * backoff. Otherwise someone stopped casting (the Cast button's dialog, the notification):
     * a stop, never undone.
     */
    suspend fun sessionEnded(lost: Boolean) = mutex.withLock {
        if (ownedMediaSessionId != null) {
            if (lost) {
                this.lost = true
                streamCut = true
                retries = 0
                retryAt = 0L
                lostAt = null
            } else {
                checkpoint()
                clearOwnedMedia()
            }
        } else {
            lastReport = null
        }
        publish()
    }

    /**
     * Starts a session on the receiver we were casting to again, backing off between tries.
     * Ownership is kept meanwhile (the gate still defers to the cast); after
     * [CastTimeline.RECONNECT_GIVE_UP_SECONDS] of failures the cast is let go.
     */
    private suspend fun rejoin() {
        val receiver = channel.receiver ?: lastReceiver ?: return
        val now = clock()
        val since = lostAt ?: now.also { lostAt = it }
        when {
            CastTimeline.secondsBetween(since, now) >= CastTimeline.RECONNECT_GIVE_UP_SECONDS -> {
                checkpoint()
                clearOwnedMedia()
                error = "Lost ${receiver.name}. Cast again once it is back."
            }

            now < retryAt -> Unit

            tryRejoin(receiver) -> {
                lost = false
                reconnected = true
                retries = 0
                lostAt = null
                progress = null
                refreshLocked()
            }

            else -> {
                retries++
                retryAt = now + CastTimeline.backoffSeconds(retries) * NANOS
                error = "Reconnecting to ${receiver.name}"
            }
        }
    }

    private suspend fun tryRejoin(receiver: CastReceiver): Boolean = try {
        channel.rejoin(receiver)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        false
    }

    // ── Bookkeeping ───────────────────────────────────────────────────────────────

    private fun record(fresh: ReceiverReport, film: Double) {
        val resume = item?.resume ?: return
        val duration = source?.durationSeconds
        val paused = fresh.state == "PAUSED" || fresh.state == "PAUSED_PLAYBACK"
        when {
            fresh.state == "PLAYING" -> onProgress(resume, CastProgress.TICK, film, duration)
            paused && !wasPaused -> onProgress(resume, CastProgress.CHECKPOINT, film, duration)
        }
        wasPaused = paused
    }

    /** Leaves our film's resume point where it was last seen. */
    private fun checkpoint() {
        val resume = item?.resume?.takeIf { ownedMediaSessionId != null } ?: return
        onProgress(resume, CastProgress.CHECKPOINT, lastFilm, source?.durationSeconds)
    }

    private fun ended() {
        val resume = item?.resume ?: return
        onProgress(resume, CastProgress.ENDED, lastFilm, source?.durationSeconds)
    }

    private fun deviceName(): String = channel.receiver?.name ?: lastReceiver?.name ?: "The TV"

    private var lastReceiver: CastReceiver? = null

    private fun startWatch() {
        val scope = scope ?: return
        if (watcher?.isActive == true) return
        watcher = scope.launch {
            while (isActive) {
                delay(WATCH_MS)
                runCatching { poll() }
            }
        }
    }

    private fun clearOwnedMedia() {
        watcher?.cancel()
        watcher = null
        relay?.close()
        relay = null
        source = null
        item = null
        route = null
        directFailed = false
        ownedMediaSessionId = null
        castingAsset = null
        sourceOffset = 0.0
        playIntent = false
        progress = null
        recoveries = 0
        recovery = null
        lastFilm = 0.0
        streamCut = false
        resumes = 0
        resumedAt = 0.0
        lost = false
        retries = 0
        retryAt = 0L
        lostAt = null
        pausedAt = null
        wasPaused = false
    }

    /** The state the UI reads, and the hold the relay needs. */
    private fun publish() {
        channel.receiver?.let { lastReceiver = it }
        val owned = ownedMediaSessionId != null
        val report = lastReport
        _state.value = _state.value.copy(
            receiver = channel.receiver,
            live = channel.live,
            item = item.takeIf { owned },
            state = report?.state,
            position = if (owned) sourceOffset + (report?.position ?: 0.0) else report?.position ?: 0.0,
            duration = if (owned) source?.durationSeconds else null,
            route = route.takeIf { owned },
            recovery = recovery,
            error = error,
        )
        val now = clock()
        pausedAt = if (!owned || playIntent) null else pausedAt ?: now
        val relaying = owned && relay != null
        hold.update(
            if (relaying) {
                CastHold.Hold(item?.title.orEmpty(), deviceName(), CastTimeline.holdWanted(true, pausedAt, now))
            } else {
                null
            },
        )
    }

    companion object {
        /** How often the receiver is read while we own media. */
        const val WATCH_MS = 2_000L
        private const val NANOS = 1_000_000_000L
    }
}
