package one.rarebit.heyarr.core.net

/**
 * JSON string-escape decoder shared by the hand-rolled field readers. Copied from
 * heyarr-mobile's `net.JsonEscapes` (a shared KMP extraction comes later). Handles
 * `\"`, `\\`, `\/`, `\n`/`\t`/`\r`/`\b` and `\uXXXX` — Go's `encoding/json`
 * (heyarr-core) escapes `&`, `<`, `>`, so a title carrying one only round-trips if the
 * escape is decoded.
 */
object JsonEscapes {
    /**
     * [json]`[i]` is a backslash. Append the decoded character to [sb] and return the
     * index just past the escape sequence. An unknown escape appends the literal
     * following character (lenient).
     */
    fun append(sb: StringBuilder, json: String, i: Int): Int {
        if (i + 1 >= json.length) return i + 1
        val n = json[i + 1]
        if (n == 'u' && i + 5 < json.length) {
            val hex = json.substring(i + 2, i + 6).toIntOrNull(16)
            if (hex != null) {
                sb.append(hex.toChar())
                return i + 6
            }
        }
        sb.append(
            when (n) {
                'n' -> '\n'
                't' -> '\t'
                'r' -> '\r'
                'b' -> '\b'
                else -> n
            },
        )
        return i + 2
    }
}
