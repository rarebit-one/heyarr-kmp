package one.rarebit.heyarr.core.contract

/**
 * A strict, minimal JSON reader for the vendored OpenAPI document — test code only.
 *
 * The contract test needs to walk the spec as a tree (resolve `$ref`, follow `allOf`,
 * collect `properties`), which [one.rarebit.heyarr.core.net.JsonScan] deliberately cannot
 * do: it is a tolerant field scanner, not a parser. Rather than add a serialization or YAML
 * library to the build for one test, `scripts/refresh-openapi-spec.sh` converts the YAML to
 * JSON when it vendors it, and this ~80-line reader turns that into plain `Map` / `List` /
 * `String` / `Number` / `Boolean` / `null`. Objects keep document order.
 */
internal class SpecJson private constructor(private val s: String) {
    private var i = 0

    companion object {
        fun parse(text: String): Any? = SpecJson(text).run {
            val v = value()
            ws()
            require(i == s.length) { "SpecJson: trailing content at offset $i" }
            v
        }
    }

    private fun ws() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun peek(): Char {
        require(i < s.length) { "SpecJson: unexpected end of input" }
        return s[i]
    }

    private fun expect(c: Char) {
        require(peek() == c) { "SpecJson: expected '$c' at offset $i, found '${s[i]}'" }
        i++
    }

    private fun consume(c: Char): Boolean = (i < s.length && s[i] == c).also { if (it) i++ }

    private fun value(): Any? {
        ws()
        return when (peek()) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> word("true", true)
            'f' -> word("false", false)
            'n' -> word("null", null)
            else -> num()
        }
    }

    private fun word(w: String, v: Any?): Any? {
        require(s.startsWith(w, i)) { "SpecJson: bad literal at offset $i" }
        i += w.length
        return v
    }

    private fun obj(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        expect('{')
        ws()
        if (peek() != '}') {
            do {
                ws()
                val k = str()
                ws()
                expect(':')
                out[k] = value()
                ws()
            } while (consume(','))
        }
        expect('}')
        return out
    }

    private fun arr(): List<Any?> {
        val out = ArrayList<Any?>()
        expect('[')
        ws()
        if (peek() != ']') {
            do {
                out.add(value())
                ws()
            } while (consume(','))
        }
        expect(']')
        return out
    }

    private fun str(): String {
        expect('"')
        val sb = StringBuilder()
        while (peek() != '"') {
            val c = s[i++]
            if (c != '\\') {
                sb.append(c)
                continue
            }
            when (val e = s[i++]) {
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> sb.append(s.substring(i, i + 4).toInt(16).toChar()).also { i += 4 }
                else -> sb.append(e) // \" \\ \/
            }
        }
        i++
        return sb.toString()
    }

    private fun num(): Number {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        val t = s.substring(start, i)
        require(t.isNotEmpty()) { "SpecJson: unexpected '${s[start]}' at offset $start" }
        return t.toLongOrNull() ?: t.toDouble()
    }
}
