package one.rarebit.heyarr.mobile.playback

import one.rarebit.heyarr.core.playback.Reconnect

/**
 * When a failed player is re-prepared, and when it is left for the person to see (#231).
 * Pure, so the whole state machine is unit-tested; [VideoSession] feeds it the player's
 * errors, the Play button and the position, and does the re-preparing itself.
 *
 * The case behind it: a Nothing Phone back from Recents, with the hardware HEVC decoder
 * reclaimed while heyarr sat in the background. Media3 reported ERROR_CODE_DECODING_FAILED
 * and stopped in its error state, where `play()` does nothing, so Play looked dead and only
 * Stop then Play again recovered. A decoder that failed once usually works on a fresh
 * prepare, so:
 *
 *  - a decoder failure ([recoverable]) is re-prepared automatically, ONCE;
 *  - a second failure in a row is shown, with the film's other copy offered when it has one
 *    (the copy itself may be what this phone's decoder cannot take);
 *  - Play on a shown error re-prepares again, every time, for any error: a re-prepare is
 *    Media3's own recovery, and doing nothing is what made Play look broken;
 *  - only playing on [recoveredAfterMs] past the point that failed counts as recovered.
 *    A first frame is not enough: a file that breaks the decoder at one sample would
 *    otherwise render, fail and auto-retry for ever, flickering, with nothing shown;
 *  - a refusal that means the stream's URL or lease lapsed (401 / 403 / 410, [Reconnect.expired];
 *    heyarr-kmp#232) is planned again ONCE, at the position: a stream token running out
 *    in a long film is the stream's age, not a fault. Failing again straight away is shown.
 */
class PlayerRecovery(private val autoRetries: Int = 1, private val recoveredAfterMs: Long = RECOVERED_AFTER_MS) {
    /** What to do about one error. */
    sealed interface Decision {
        /** Re-prepare at the current position, keeping the copy, audio track and captions. */
        data object Retry : Decision

        /** Plan the item again and carry on at the position: its URL or lease lapsed. */
        data object Refresh : Decision

        /** Leave the error up. [offerOtherCopy]: failing twice in a row, so the copy may be the problem. */
        data class Show(val offerOtherCopy: Boolean) : Decision
    }

    /** Failures since playback last got [recoveredAfterMs] past one. */
    var consecutiveFailures: Int = 0
        private set

    /** Whether an error is up, waiting for Play. */
    var showing: Boolean = false
        private set

    private var failedAtMs = 0L
    private var refreshed = false
    private var lastExpired = false

    /**
     * The player failed at [positionMs] (source time) with Media3's [errorCodeName], and the
     * HTTP status behind it when the node refused a read ([httpStatus]).
     */
    fun onError(errorCodeName: String, positionMs: Long, httpStatus: Int? = null): Decision {
        consecutiveFailures++
        failedAtMs = positionMs
        lastExpired = Reconnect.expired(httpStatus)
        val decision = when {
            lastExpired && !refreshed -> Decision.Refresh.also { refreshed = true }

            recoverable(errorCodeName) && consecutiveFailures <= autoRetries -> Decision.Retry

            // The other copy is the answer to a decoder that cannot take this one, not to a refusal.
            else -> Decision.Show(offerOtherCopy = recoverable(errorCodeName) && consecutiveFailures > autoRetries)
        }
        showing = decision is Decision.Show
        return decision
    }

    /** The person pressed Play: [Decision.Retry] while an error is up, null for an ordinary play. */
    fun onPlay(): Decision? {
        if (!showing) return null
        showing = false
        // A lapsed URL would only be refused again: Play plans it afresh, once per press.
        return if (lastExpired) Decision.Refresh else Decision.Retry
    }

    /** The playhead, from the session's ticker: playing on well past the failure clears the count. */
    fun onPosition(positionMs: Long, playing: Boolean) {
        val retried = consecutiveFailures > 0 && !showing
        if (retried && playing && positionMs >= failedAtMs + recoveredAfterMs) reset()
    }

    /** A new item, a new copy or Stop: nothing that failed before counts against it. */
    fun reset() {
        consecutiveFailures = 0
        showing = false
        failedAtMs = 0L
        refreshed = false
        lastExpired = false
    }

    companion object {
        /** How far past the failure playback must get before it counts as recovered. */
        const val RECOVERED_AFTER_MS = 5_000L

        /**
         * The errors a fresh prepare can cure: the decoder failed, could not be set up or
         * queried, or was taken back by the system. Not "this format is unsupported" or
         * "exceeds this phone's capabilities": those fail the same way every time, and the
         * re-plan in [PlaybackCoordinator.onIssue] is their answer.
         */
        private val RECOVERABLE = setOf(
            "ERROR_CODE_DECODING_FAILED",
            "ERROR_CODE_DECODER_INIT_FAILED",
            "ERROR_CODE_DECODER_QUERY_FAILED",
            "ERROR_CODE_DECODING_RESOURCES_RECLAIMED",
        )

        fun recoverable(errorCodeName: String): Boolean = errorCodeName in RECOVERABLE
    }
}
