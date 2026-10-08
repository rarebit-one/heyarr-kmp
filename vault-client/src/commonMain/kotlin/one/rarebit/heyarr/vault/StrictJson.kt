package one.rarebit.heyarr.vault

/**
 * A strict RFC 8259 check of a sealed object, plus the two envelope fields `put-ref` reads.
 * [one.rarebit.heyarr.core.net.JsonScan] is a tolerant field scanner (it would pass
 * `{"v":1 "type":"x"}`), which is right for reading a node's responses but wrong for deciding
 * what this device seals: an object Go's `json.Unmarshal` refuses must be refused here too, or a
 * reader on another client cannot parse what was written.
 *
 * It mirrors heyarr-core's `readObject` (`internal/cli/vault_ref_cmd.go`), which unmarshals into
 * `{V *int "v"; Type string "type"}`:
 * - the whole input is one JSON value, with only whitespace around it;
 * - keys match `v` / `type` case-insensitively, as `encoding/json` does, and a repeated key's
 *   LAST occurrence wins (duplicates are not an error);
 * - `v` must be an integer literal (no fraction or exponent) or null; `type` a string or null —
 *   any other kind of value is an unmarshal error.
 *
 * One deliberate difference: nesting deeper than [MAX_DEPTH] is refused (Go allows 10000), so a
 * hostile object cannot overflow a phone's stack. An envelope never nests that deep.
 */
internal object StrictJson {
    const val MAX_DEPTH = 512

    /** What an envelope check needs from a valid top-level object. */
    class Envelope(val v: Long?, val type: String?, val wrongType: Boolean)

    /** Thrown for any input that is not exactly one valid JSON value. */
    class SyntaxException(message: String) : Exception(message)

    /** Whether [json] is exactly one valid JSON value. */
    fun isValid(json: String): Boolean = runCatching { Parser(json).document() }.isSuccess

    /**
     * The envelope fields of [json], or null when it is not valid JSON or not an object. Error
     * messages never quote the input: it may be plaintext.
     */
    fun envelope(json: String): Envelope? = runCatching { Parser(json).document() }.getOrNull()

    /**
     * The keys of [json]'s top-level object in order, duplicates kept, or null when it is not
     * exactly one valid JSON object. Lets a caller refuse what a tolerant first-match scanner and
     * Go's last-match, case-insensitive `encoding/json` would read differently.
     */
    fun topLevelKeys(json: String): List<String>? = runCatching {
        val p = Parser(json)
        p.document() ?: return null
        p.topKeys.toList()
    }.getOrNull()

    @Suppress("TooManyFunctions") // one small function per production of the grammar
    private class Parser(private val s: String) {
        val topKeys = ArrayList<String>()
        private var i = 0
        private var v: Long? = null
        private var type: String? = null
        private var wrongType = false

        /** Parse the whole input; the top-level object's envelope fields, or null for a non-object. */
        fun document(): Envelope? {
            ws()
            val isObject = i < s.length && s[i] == '{'
            value(0, top = true)
            ws()
            if (i != s.length) fail("trailing data")
            return if (isObject) Envelope(v, type, wrongType) else null
        }

        private fun value(depth: Int, top: Boolean = false) {
            if (depth > MAX_DEPTH) fail("nested too deeply")
            if (i >= s.length) fail("unexpected end")
            when (s[i]) {
                '{' -> obj(depth, top)
                '[' -> array(depth)
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> number()
            }
        }

        private fun obj(depth: Int, top: Boolean) {
            i++ // {
            ws()
            if (peek() == '}') {
                i++
                return
            }
            while (true) {
                ws()
                if (peek() != '"') fail("expected a key")
                val key = string()
                if (top) topKeys.add(key)
                ws()
                expect(':')
                ws()
                if (top && key.equals("v", ignoreCase = true)) {
                    topV()
                } else if (top && key.equals("type", ignoreCase = true)) {
                    topType(depth)
                } else {
                    value(depth + 1)
                }
                ws()
                when (next()) {
                    ',' -> continue
                    '}' -> return
                    else -> fail("expected , or }")
                }
            }
        }

        // `V *int`: an integer literal sets it, null clears it, anything else is an unmarshal error.
        private fun topV() {
            when (peek()) {
                'n' -> {
                    literal("null")
                    v = null
                }

                '-', in '0'..'9' -> {
                    val start = i
                    number()
                    val lit = s.substring(start, i)
                    val n = if (lit.any { it == '.' || it == 'e' || it == 'E' }) null else lit.toLongOrNull()
                    if (n == null) wrongType = true else v = n
                }

                else -> {
                    value(1)
                    wrongType = true
                }
            }
        }

        // `Type string`: a string sets it, null leaves it, anything else is an unmarshal error.
        private fun topType(depth: Int) {
            when (peek()) {
                '"' -> type = string()

                'n' -> literal("null")

                else -> {
                    value(depth + 1)
                    wrongType = true
                }
            }
        }

        private fun array(depth: Int) {
            i++ // [
            ws()
            if (peek() == ']') {
                i++
                return
            }
            while (true) {
                ws()
                value(depth + 1)
                ws()
                when (next()) {
                    ',' -> continue
                    ']' -> return
                    else -> fail("expected , or ]")
                }
            }
        }

        /** A string literal, decoded. */
        private fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> escape(sb)
                    c < ' ' -> fail("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun escape(sb: StringBuilder) {
            val c = next()
            if (c != 'u') {
                sb.append(SIMPLE_ESCAPES[c] ?: fail("bad escape"))
                return
            }
            if (i + HEX4 > s.length) fail("short \\u escape")
            val hex = s.substring(i, i + HEX4)
            val code = hex.toIntOrNull(HEX_RADIX)
            if (code == null || hex.any { it == '+' || it == '-' }) fail("bad \\u escape")
            sb.append(code.toChar())
            i += HEX4
        }

        // -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?
        private fun number() {
            if (peek() == '-') i++
            when (peek()) {
                '0' -> i++
                in '1'..'9' -> digits()
                else -> fail("bad value")
            }
            if (peek() == '.') {
                i++
                if (peek() !in '0'..'9') fail("bad fraction")
                digits()
            }
            if (peek() == 'e' || peek() == 'E') {
                i++
                if (peek() == '+' || peek() == '-') i++
                if (peek() !in '0'..'9') fail("bad exponent")
                digits()
            }
        }

        private fun digits() {
            while (peek() in '0'..'9') i++
        }

        private fun literal(word: String) {
            if (!s.startsWith(word, i)) fail("bad literal")
            i += word.length
        }

        private fun ws() {
            while (i < s.length && s[i] in WHITESPACE) i++
        }

        private fun peek(): Char = if (i < s.length) s[i] else END

        private fun next(): Char = if (i < s.length) s[i++] else fail("unexpected end")

        private fun expect(c: Char) {
            if (next() != c) fail("expected $c")
        }

        private fun fail(why: String): Nothing = throw SyntaxException("invalid JSON at offset $i: $why")
    }

    private const val HEX4 = 4
    private val WHITESPACE = setOf(' ', '\t', '\n', '\r')
    private val SIMPLE_ESCAPES = mapOf(
        '"' to '"',
        '\\' to '\\',
        '/' to '/',
        'b' to '\b',
        'f' to '\u000C',
        'n' to '\n',
        'r' to '\r',
        't' to '\t',
    )
    private const val END = '\u0000'
}
