package one.rarebit.heyarr.core.cast

import kotlin.math.roundToLong

/**
 * One report from a Google Cast receiver about the media in its session, in the receiver's
 * own terms: [position] is the receiver's clock, which restarts at zero on every LOAD, and
 * [state] / [idleReason] are the Cast media-status strings (`PLAYING`, `PAUSED`,
 * `BUFFERING`, `IDLE`; `FINISHED`, `CANCELLED`, `INTERRUPTED`, `ERROR`). Each app maps its
 * own transport onto it: the desktop's hand-rolled Cast v2 channel, Android's Cast SDK.
 */
data class ReceiverReport(
    val position: Double,
    val state: String,
    val mediaSessionId: Int?,
    val idleReason: String? = null,
)

/**
 * How a sender reads a Default Media Receiver playing heyarr's live fMP4 repackage
 * (ADR-0069), learned on real hardware (heyarr-kmp#248, #249) and shared by both apps
 * (heyarr-kmp#250). Pure: every caller passes its own monotonic clock, in nanoseconds.
 *
 * The repackage has no length and cannot be range-fetched, so the receiver reports only its
 * buffered end as the duration and cannot seek past it. A sender therefore restarts the
 * stream at a film-absolute time (`?start=`) and keeps an offset; it watches the receiver's
 * progress for a seek it cannot reach or a stall it will never leave; and it reads an IDLE
 * session's reason to tell a stop or the film's end (let go) from a dropped stream (resumed).
 */
object CastTimeline {
    /** Where a restart seek lands, clamped to the film, and whether it plays on. */
    data class RestartSeek(val position: Double, val autoplay: Boolean)

    /**
     * The receiver's last seen time, and when it last moved forward in play.
     * [jumped]: it leapt past where playback could have reached since, which only a
     * seek does, and has not played on from there yet.
     */
    data class Progress(
        val position: Double,
        val state: String,
        val seenAt: Long,
        val advancedAt: Long,
        val jumped: Boolean,
    )

    enum class Verdict { STEADY, UNREACHED_SEEK, STALLED }

    /**
     * What a receiver report means for the media we own. [OURS]: still ours. [FOREIGN]:
     * someone else's media replaced it. [ENDED]: let it go (a stop, the film's end).
     * [DROPPED]: it ended mid-film without anyone stopping it, so it is resumed.
     */
    enum class Fate { OURS, FOREIGN, ENDED, DROPPED }

    /** [played]: the clock moved forward in play since the last report, the only proof of health. */
    data class Judgement(val progress: Progress, val verdict: Verdict, val played: Boolean)

    /** A jump smaller than this is poll jitter or a short buffering catch-up, not a seek. */
    const val SEEK_TOLERANCE_SECONDS = 5.0

    /** A seek past the buffered end leaves the receiver waiting for bytes that never come. */
    const val SEEK_GRACE_SECONDS = 4L

    /**
     * No progress while meant to play for this long is a wedged receiver. The node's
     * first fragment of a restarted 4K tone-mapped transcode takes a few seconds; the
     * observed wedge lasted minutes.
     */
    const val STALL_SECONDS = 20L

    /** Relaunches without any progress in between before the user is asked to step in. */
    const val MAX_RECOVERIES = 2

    /** Resumes of a dropped or cut film without it playing [RESUME_PROOF_SECONDS] on in between. */
    const val MAX_RESUMES = 3

    /** How far a resumed film must play on before its resume counts as having worked. */
    const val RESUME_PROOF_SECONDS = 60.0

    /**
     * A session that ends this close to the film's end ended with the film: credits, or
     * the transcode's last frames, not a dropped stream. Not resumed.
     */
    const val END_MARGIN_SECONDS = 60.0

    /** Failed reconnects for this long (awake time) let the cast go. */
    const val RECONNECT_GIVE_UP_SECONDS = 600L

    /**
     * How long a paused cast keeps the sender awake. The TV's remote can resume it while the
     * sender is idle, and a sender that relays the stream must still be there to serve it.
     */
    const val PAUSE_GRACE_SECONDS = 600L

    private const val MAX_BACKOFF_SECONDS = 30L
    private const val PROGRESS_EPSILON = 0.25
    private const val MILLIS = 1_000.0
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private val STILL = setOf("PAUSED", "PAUSED_PLAYBACK", "IDLE")

    /** Our media with a pipeline behind it: what a cut stream restarts and a resume returns to. */
    val LIVE = setOf("PLAYING", "BUFFERING", "PAUSED", "PAUSED_PLAYBACK")

    /** Played or paused: the states a receiver is in when its session is healthy. */
    val HEALTHY = setOf("PLAYING", "PAUSED", "PAUSED_PLAYBACK")

    /** 1, 2, 4, … seconds between reconnect attempts, at most [MAX_BACKOFF_SECONDS]. */
    fun backoffSeconds(failures: Int): Long =
        (1L shl (failures - 1).coerceIn(0, Long.SIZE_BITS - 2)).coerceAtMost(MAX_BACKOFF_SECONDS)

    /**
     * Where a seek to [seconds] of the film restarts it: inside the film, and to the
     * millisecond, since offset plus receiver time is binary noise past what the node's
     * `-ss` keeps.
     */
    fun restartSeek(seconds: Double, duration: Double?, playIntent: Boolean): RestartSeek {
        require(seconds.isFinite()) { "Invalid Cast seek position" }
        val end = duration?.takeIf { it.isFinite() && it >= 0 } ?: Double.MAX_VALUE
        return RestartSeek(millis(seconds.coerceIn(0.0, end)), playIntent)
    }

    /** [seconds] to the millisecond. */
    fun millis(seconds: Double): Double = (seconds * MILLIS).roundToLong() / MILLIS

    /**
     * Reads one receiver report against the last. The Default Media Receiver plays a
     * live repackage as a progressive download it cannot range-fetch: a seek inside
     * what it buffered works natively and needs nothing from us, but a seek past it
     * jumps the clock and then waits forever. So a forward jump only matters once the
     * receiver sits on it for [SEEK_GRACE_SECONDS]; any other lack of progress while
     * meant to play is a stall after [STALL_SECONDS]. Paused time never counts.
     */
    fun judge(previous: Progress?, next: ReceiverReport, now: Long, wantsPlay: Boolean): Judgement {
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

    /**
     * Reads a receiver report against the media session we own. A session that went IDLE
     * says why: CANCELLED is a stop (from the TV's remote too) and INTERRUPTED is another
     * sender's LOAD over our film (our own restart LOADs move ownership to the new session at
     * once, so on the session we own it is always someone else's choice); neither is ever
     * undone, and neither is an error (heyarr-kmp#242 review, the desktop's rule too).
     * ERROR mid-film is resumed; FINISHED mid-film is the receiver playing
     * out a buffer the node stopped filling, resumed when the film's length shows it
     * ended early (or the stream is known to be [cut]). A session that vanished without
     * a word, typically while our channel was down, is resumed only when the stream is
     * known to be cut; otherwise it is taken as stopped. Nothing near the film's end is
     * resumed. An IDLE report with no reason is the moment around a LOAD, still ours.
     */
    fun fateOf(fresh: ReceiverReport?, owned: Int, lastFilm: Double, duration: Double?, cut: Boolean): Fate {
        val session = fresh?.mediaSessionId
        val ended = fresh?.state == "IDLE" && fresh.idleReason != null
        val nearEnd = duration != null && lastFilm >= duration - END_MARGIN_SECONDS
        return when {
            session != null && session != owned -> Fate.FOREIGN
            session == owned && !ended -> Fate.OURS
            !nearEnd && droppedFor(fresh?.idleReason, timed = duration != null, cut) -> Fate.DROPPED
            else -> Fate.ENDED
        }
    }

    /** Whether a session that ended for [reason] mid-film was dropped rather than stopped. */
    private fun droppedFor(reason: String?, timed: Boolean, cut: Boolean): Boolean = when (reason) {
        "CANCELLED", "INTERRUPTED" -> false
        "ERROR" -> true
        "FINISHED" -> timed || cut
        else -> cut
    }

    /**
     * Whether the sender should hold itself awake (a wake lock, a Wi-Fi lock, a sleep
     * inhibitor) for the media it owns: while it plays, and for [PAUSE_GRACE_SECONDS] after
     * a pause ([pausedAt], null while playing). A longer pause lets go; a resume takes it again.
     */
    fun holdWanted(owned: Boolean, pausedAt: Long?, now: Long): Boolean =
        owned && (pausedAt == null || now - pausedAt < PAUSE_GRACE_SECONDS * NANOS_PER_SECOND)

    /** Whole seconds between two monotonic readings. */
    fun secondsBetween(from: Long, to: Long): Long = (to - from) / NANOS_PER_SECOND

    /** The furthest the receiver's clock could have run since [previous] without a seek. */
    private fun reach(previous: Progress, next: ReceiverReport, now: Long): Double {
        val elapsed = (now - previous.seenAt).toDouble() / NANOS_PER_SECOND
        val moving = previous.state == "PLAYING" || next.state == "PLAYING"
        return previous.position + if (moving) elapsed else 0.0
    }

    private fun verdictOf(progress: Progress, now: Long): Verdict {
        val stuck = secondsBetween(progress.advancedAt, now)
        return when {
            progress.jumped && stuck >= SEEK_GRACE_SECONDS -> Verdict.UNREACHED_SEEK
            stuck >= STALL_SECONDS -> Verdict.STALLED
            else -> Verdict.STEADY
        }
    }
}
