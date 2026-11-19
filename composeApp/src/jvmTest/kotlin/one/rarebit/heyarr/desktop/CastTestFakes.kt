package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.desktop.cast.CastChannel
import one.rarebit.heyarr.desktop.cast.CastStatus
import one.rarebit.heyarr.desktop.cast.SessionSighting
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Shared by the Cast manager's tests: a scripted receiver and a hand-driven clock.

/** [sessions] numbers media sessions receiver-wide, so a reconnected channel continues them. */
internal class FakeChannel(private val sessions: AtomicInteger = AtomicInteger(1)) : CastChannel {
    var closed = false
    var failLoad = false

    /** The receiver refuses an HLS LOAD (a playlist it cannot fetch or play) but plays a live stream. */
    var failHls = false
    var lastLoad: Pair<String, Boolean>? = null
    var lastDuration: Double? = null
    var lastStart = 0.0
    var lastHls = false
    var loadCount = 0
    var receiverStops = 0
    var refuseStop = false
    val events = mutableListOf<String>()
    var lastControl: Pair<String, Double?>? = null
    override var status: CastStatus? = CastStatus("PLAYING", 0.0, null, "Example", 1)
    override var error: String? = null

    /** The receiver app; a relaunch (ours or another sender's) starts a new one. */
    override var appSession: String? = "app-1"

    /** What this channel saw of each session, latched as CastConnection's: session to idleReason. */
    private val sightings = mutableMapOf<Int, String?>()
    override fun load(url: String, title: String, autoplay: Boolean, start: Double, duration: Double?) =
        record(url, title, autoplay, start, duration, hls = false)

    override fun loadPresentation(url: String, title: String, autoplay: Boolean, start: Double) =
        record(url, title, autoplay, start, duration = null, hls = true)

    @Suppress("LongParameterList") // Records every LOAD input a test asserts on.
    private fun record(url: String, title: String, autoplay: Boolean, start: Double, duration: Double?, hls: Boolean) {
        sightings.clear()
        if (failLoad || (hls && failHls)) error("LOAD_FAILED")
        loadCount++
        events += "load"
        lastLoad = url to autoplay
        lastDuration = duration
        lastStart = start
        lastHls = hls
        // An HLS receiver starts at the asked film time; a live stream's clock starts at zero.
        status = CastStatus(if (autoplay) "PLAYING" else "PAUSED", start, null, title, sessions.incrementAndGet())
        sight(status)
    }
    override fun stopReceiver() {
        if (status == null) return // no media app running: a no-op, as CastConnection's
        if (refuseStop) error("INVALID_REQUEST")
        receiverStops++
        events += "stop-receiver"
        status = null
        sightings.clear()
    }
    override fun sighting(mediaSessionId: Int): SessionSighting? =
        if (mediaSessionId in sightings) SessionSighting(sightings[mediaSessionId]) else null

    /**
     * The receiver closes the current session between two polls, broadcasting [idleReason]
     * on the way out (null: nothing this channel heard), so the next poll finds no media.
     */
    fun vanish(idleReason: String? = null) {
        idleReason?.let { reason -> status?.mediaSessionId?.let { sightings[it] = reason } }
        status = null
    }

    /** A broadcast this channel heard: [session] ended for [idleReason]. */
    fun hear(session: Int, idleReason: String) {
        sightings[session] = idleReason
    }

    private fun sight(seen: CastStatus?) {
        val session = seen?.mediaSessionId ?: return
        sightings[session] = seen.idleReason ?: sightings[session]
    }
    override fun control(action: String, seconds: Double?) {
        lastControl = action to seconds
        if (action == "pause") status = status?.copy(state = "PAUSED")
        if (action == "resume") status = status?.copy(state = "PLAYING")
    }
    override fun refresh(): CastStatus? {
        // A dead connection throws on its next request, as CastConnection's does.
        error?.let { throw IllegalStateException(it) }
        sight(status)
        return status
    }
    override fun close() {
        closed = true
    }
}

/** A manual clock, so a stall is a few lines, not twenty real seconds. */
internal class ManualClock {
    var now = 0L
    fun advance(seconds: Double) {
        now += (seconds * TimeUnit.SECONDS.toNanos(1)).toLong()
    }
}

/** Reports [position] (receiver time, relative to the last LOAD) in the current media session. */
internal fun FakeChannel.report(state: String, position: Double) {
    status = requireNotNull(status).copy(state = state, position = position)
}
