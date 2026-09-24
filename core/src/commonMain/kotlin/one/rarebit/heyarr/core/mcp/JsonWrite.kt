package one.rarebit.heyarr.core.mcp

/**
 * The write-side twin of [one.rarebit.heyarr.core.net.JsonScan]: a tiny, dependency-free
 * JSON encoder for the request bodies this client sends (JSON-RPC envelopes and REST
 * writes). Same org stance as the readers — no serialization library.
 *
 * Accepts the small vocabulary a tool call needs: `String`, `Boolean`, `Int`/`Long`/
 * `Double`, `null`, a `List` of those, and a `Map<String, *>` of those. Null values are
 * DROPPED from objects on purpose: heyarr's `explain_release` reads an absent attribute
 * as `undetermined` and a present one as a claim, so "I don't know" must be encoded as
 * absence, never as `null`.
 */
object JsonWrite {

    fun obj(fields: Map<String, Any?>): String = buildString { writeObject(this, fields) }

    fun value(v: Any?): String = buildString { writeValue(this, v) }

    private fun writeObject(sb: StringBuilder, fields: Map<String, Any?>) {
        sb.append('{')
        var first = true
        for ((k, v) in fields) {
            if (v == null) continue
            if (!first) sb.append(',')
            first = false
            writeString(sb, k)
            sb.append(':')
            writeValue(sb, v)
        }
        sb.append('}')
    }

    @Suppress("UNCHECKED_CAST")
    private fun writeValue(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")

            is String -> writeString(sb, v)

            is Boolean -> sb.append(if (v) "true" else "false")

            is Int, is Long, is Short, is Byte -> sb.append(v.toString())

            is Double -> sb.append(if (v.isFinite()) v.toString() else "null")

            is Float -> sb.append(if (v.isFinite()) v.toString() else "null")

            is Map<*, *> -> writeObject(sb, v as Map<String, Any?>)

            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeValue(sb, item)
                }
                sb.append(']')
            }

            is Array<*> -> writeValue(sb, v.asList())

            else -> writeString(sb, v.toString())
        }
    }

    fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u" + c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
