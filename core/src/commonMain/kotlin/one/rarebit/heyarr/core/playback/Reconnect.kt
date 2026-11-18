package one.rarebit.heyarr.core.playback

/**
 * How a stream that lost its node keeps trying (heyarr-kmp#232). Pure, so the schedule is
 * unit-tested; each app's player feeds it its own load errors.
 *
 * A player's own default gives up after a handful of quick retries, a few seconds in all: a
 * 20 s Wi-Fi drop rode out on the buffer, but anything longer than the buffer ended in an
 * error and a dead player. A phone walking from Wi-Fi into a lift, or between networks, is
 * offline for longer than that, and nothing is wrong with the film. So a connection failure is
 * retried with a growing wait, [FIRST_DELAY_MS] doubling to [MAX_DELAY_MS], for
 * [GIVE_UP_AFTER_MS] of continuous outage; the position is the player's, and is kept. Past
 * that, the error is shown, and Play tries again.
 *
 * A refusal that means the stream's URL or lease has lapsed ([expired]: a stream token that
 * expired in a long film, a Device proof the node no longer accepts) is not retried as it is:
 * the same request would be refused again. The player plans the item afresh instead.
 */
object Reconnect {
    const val FIRST_DELAY_MS = 1_000L
    const val MAX_DELAY_MS = 10_000L
    const val GIVE_UP_AFTER_MS = 5 * 60 * 1000L

    /** The wait before retry number [attempt] (1 for the first): 1, 2, 4, 8, then 10 s each. */
    fun delayMs(attempt: Int): Long {
        val doublings = (attempt - 1).coerceIn(0, MAX_DOUBLINGS)
        return (FIRST_DELAY_MS shl doublings).coerceAtMost(MAX_DELAY_MS)
    }

    /** Whether an outage that began [outageMs] ago is still worth riding out. */
    fun keepTrying(outageMs: Long): Boolean = outageMs < GIVE_UP_AFTER_MS

    /** 401, 403 and 410: the URL or the lease behind it lapsed. Plan again; do not retry. */
    fun expired(httpStatus: Int?): Boolean = httpStatus == UNAUTHORIZED || httpStatus == FORBIDDEN || httpStatus == GONE

    private const val MAX_DOUBLINGS = 4
    private const val UNAUTHORIZED = 401
    private const val FORBIDDEN = 403
    private const val GONE = 410
}
