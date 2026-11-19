package one.rarebit.heyarr.core.playback

/**
 * The restart-seek URL for a node-repackaged stream (ADR-0069, heyarr-core#433). A
 * `mode: "stream"` plan has no ranges, so the bytes ahead of the playhead do not exist yet;
 * to reach them the player re-requests the same token URL with `?start=<seconds>` and a fresh
 * ffmpeg seeks its input there. The desktop player, the phone's player and the desktop's
 * Cast restart all build this URL, and used to do it three times; this is the one copy.
 *
 * The node accepts `start=[0-9]+(\.[0-9]+)?` (the Cast relay enforces the same shape), so
 * [seconds] never emits an exponent or a sign for a position the players produce.
 */
object StreamStart {
    /**
     * [base] (the token URL with NO `start` of its own) restarted at [startSeconds]. At or
     * below zero it is [base] unchanged, so a first play is the plain URL the plan gave.
     * Always build off the base, never off a URL that already carries a `start`, or a second
     * seek stacks one param on another. The path token goes on the wire VERBATIM, never
     * percent-encoded (the `blake3:` → `blake3%3A` trap).
     */
    fun url(base: String, startSeconds: Double): String {
        if (startSeconds <= 0.0) return base
        val sep = if (base.contains('?')) '&' else '?'
        return base + sep + "start=" + seconds(startSeconds)
    }

    /**
     * Seconds as an integer when whole, else up to 3 decimals with no trailing zeros: what
     * the JVM's `"%.3f".format(Locale.ROOT, …)` printed before the zeros were trimmed. That
     * rounds the double's shortest decimal digits half-up (12.3455 → `12.346`, though the
     * binary value sits just below), so this does the same on [Double.toString]'s digits
     * rather than on the binary value; commonMain has no `String.format`.
     */
    fun seconds(value: Double): String {
        val whole = value.toLong()
        return when {
            value == whole.toDouble() -> whole.toString()

            !value.isFinite() -> value.toString()

            else -> {
                val (digits, point) = decimalDigits(if (value < 0) -value else value)
                val rounded = roundHalfUp(digits, point, DECIMALS)
                // The format kept the sign even when the digits rounded away (-0.0004 printed `-0`).
                (if (value < 0) "-" else "") + rounded.trimEnd('0').trimEnd('.')
            }
        }
    }

    /**
     * The significant digits of a positive finite [value] and where its decimal point falls
     * among them (`12.5` → `"125"`, 2; `1.0E-4` → `"10"`, -3), from [Double.toString], which
     * switches to exponent form below 10⁻³ and from 10⁷.
     */
    private fun decimalDigits(value: Double): Pair<String, Int> {
        val text = value.toString()
        val e = text.indexOf('E')
        val mantissa = if (e < 0) text else text.substring(0, e)
        val exponent = if (e < 0) 0 else text.substring(e + 1).toInt()
        val dot = mantissa.indexOf('.')
        val digits = mantissa.removeRange(dot, dot + 1)
        return digits to dot + exponent
    }

    /** `digits` with its point at [point], rounded half-up to [places] decimals, zero-padded. */
    private fun roundHalfUp(digits: String, point: Int, places: Int): String {
        // Left-pad so the point falls inside the digits, then keep integer part + places.
        val padded = "0".repeat((1 - point).coerceAtLeast(0)) + digits
        val at = point.coerceAtLeast(1)
        val keep = (padded + "0".repeat((at + places + 1 - padded.length).coerceAtLeast(0)))
        val kept = keep.substring(0, at + places)
        val carry = keep[at + places] >= '5'
        val bumped = if (carry) increment(kept) else kept
        val intLength = bumped.length - places
        return bumped.substring(0, intLength).trimStart('0').ifEmpty { "0" } + "." + bumped.substring(intLength)
    }

    /** A decimal digit string plus one, growing a digit on overflow (`"999"` → `"1000"`). */
    private fun increment(number: String): String {
        val out = number.toCharArray()
        var i = out.lastIndex
        while (i >= 0) {
            if (out[i] != '9') {
                out[i] = out[i] + 1
                return out.concatToString()
            }
            out[i] = '0'
            i--
        }
        return "1" + out.concatToString()
    }

    private const val DECIMALS = 3
}
