package one.rarebit.heyarr.core.playback

/**
 * A position or duration as a player shows it: `m:ss` under an hour, `h:mm:ss` from an hour on
 * (`0:05`, `59:59`, `1:00:00`). Both apps and every screen that prints a clock (the desktop
 * player and Now Playing, the phone's Cast screen, the Continue rows) used to carry their own
 * copy of this; it lives here once so they cannot drift.
 *
 * Built with `padStart` rather than `String.format`, which commonMain does not have. A negative
 * value is not clamped (`-5` reads `0:-5`, as every copy rendered it); callers holding a
 * position that can briefly read below zero use the [Double] overload, which clamps.
 */
fun clockLabel(seconds: Long): String {
    val h = seconds / SECONDS_PER_HOUR
    val m = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val ss = (seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$ss" else "$m:$ss"
}

/**
 * A player position in fractional seconds (libmpv's, a Cast receiver's): truncated to the whole
 * second it is in, and clamped at zero so a receiver's momentary negative reading shows `0:00`.
 * NaN reads `0:00` too (`NaN.toLong()` is 0).
 */
fun clockLabel(seconds: Double): String = clockLabel(seconds.toLong().coerceAtLeast(0))

private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_MINUTE = 60L
