package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.desktop.cast.CastChannel
import one.rarebit.heyarr.desktop.cast.CastStatus
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Shared by the Cast manager's tests: a scripted receiver and a hand-driven clock.

/** [sessions] numbers media sessions receiver-wide, so a reconnected channel continues them. */
internal class FakeChannel(private val sessions: AtomicInteger = AtomicInteger(1)) : CastChannel {
    var closed = false
    var failLoad = false
    var lastLoad: Pair<String, Boolean>? = null
    var lastDuration: Double? = null
    var loadCount = 0
    var receiverStops = 0
    var refuseStop = false
    val events = mutableListOf<String>()
    var lastControl: Pair<String, Double?>? = null
    override var status: CastStatus? = CastStatus("PLAYING", 0.0, null, "Example", 1)
    override var error: String? = null
    override fun load(url: String, title: String, autoplay: Boolean, start: Double, duration: Double?) {
        if (failLoad) error("LOAD_FAILED")
        loadCount++
        events += "load"
        lastLoad = url to autoplay
        lastDuration = duration
        status = CastStatus(if (autoplay) "PLAYING" else "PAUSED", 0.0, null, title, sessions.incrementAndGet())
    }
    override fun stopReceiver() {
        if (status == null) return // no media app running: a no-op, as CastConnection's
        if (refuseStop) error("INVALID_REQUEST")
        receiverStops++
        events += "stop-receiver"
        status = null
    }
    override fun control(action: String, seconds: Double?) {
        lastControl = action to seconds
        if (action == "pause") status = status?.copy(state = "PAUSED")
        if (action == "resume") status = status?.copy(state = "PLAYING")
    }
    override fun refresh(): CastStatus? {
        // A dead connection throws on its next request, as CastConnection's does.
        error?.let { throw IllegalStateException(it) }
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
