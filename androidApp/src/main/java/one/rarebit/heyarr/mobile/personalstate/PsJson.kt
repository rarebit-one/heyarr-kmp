package one.rarebit.heyarr.mobile.personalstate

import one.rarebit.heyarr.core.net.JsonEscapes
import one.rarebit.heyarr.core.net.JsonScan

/**
 * The JSON primitives the personal-state port needs beyond `net/JsonScan`:
 *  - reading an **unsigned 64-bit** number (a Lamport counter can be MaxUint64,
 *    which overflows the `Long` `JsonScan.longField` returns);
 *  - reading a top-level **array of strings** (a change's `Observed`, a snapshot's
 *    `tombstones`, an envelope's `parents`);
 *  - writing a string escaped exactly like Go's `encoding/json` ([goJsonString]),
 *    so a snapshot this device serialises is byte-identical to Go's `json.Marshal`;
 *  - quoting a string exactly like Go's `strconv.Quote` / `%q` ([goQuote]), the
 *    format `ReadingPositions.Encode` uses.
 *
 * Reads lean on `JsonScan` for object/field slicing; only these gaps are new.
 */
internal object PsJson {
    /** An unsigned-64 field, or null if absent/not a bare number. */
    fun ulong(json: String, key: String): ULong? {
        val start = JsonScan.valueStart(json, key) ?: return null
        var i = start
        val n = json.length
        val sb = StringBuilder()
        while (i < n && json[i] in '0'..'9') {
            sb.append(json[i])
            i++
        }
        return sb.toString().toULongOrNull()
    }

    /** The top-level string elements of the array at [key] (empty if absent/null). */
    fun stringArray(json: String, key: String): List<String> {
        val arr = JsonScan.arrayOf(json, listOf(key)) ?: return emptyList()
        return topLevelStrings(arr)
    }

    /** The string elements of an array slice `[ "a", "b" ]`, honouring escapes and skipping nested structures. */
    fun topLevelStrings(arraySlice: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        val n = arraySlice.length
        var depth = 0
        while (i < n) {
            when (val c = arraySlice[i]) {
                '[', '{' -> {
                    depth++
                    i++
                }

                ']', '}' -> {
                    depth--
                    i++
                }

                '"' -> {
                    if (depth == 1) {
                        val sb = StringBuilder()
                        i++
                        while (i < n && arraySlice[i] != '"') {
                            i = if (arraySlice[i] == '\\') {
                                JsonEscapes.append(sb, arraySlice, i)
                            } else {
                                sb.append(arraySlice[i])
                                i + 1
                            }
                        }
                        i++ // past closing quote
                        out.add(sb.toString())
                    } else {
                        i++
                        while (i < n && arraySlice[i] != '"') {
                            i += if (arraySlice[i] == '\\') 2 else 1
                        }
                        i++
                    }
                }

                else -> {
                    @Suppress("UNUSED_EXPRESSION")
                    c
                    i++
                }
            }
        }
        return out
    }

    /** Escape [s] exactly like Go's `encoding/json` (HTML-escaping default). */
    fun goJsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '<' -> sb.append("\\u003c")
                '>' -> sb.append("\\u003e")
                '&' -> sb.append("\\u0026")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> if (ch.code < 0x20) sb.append("\\u00").append(hex2(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * Quote [s] like Go's `strconv.Quote` (`%q`) for the ASCII range — the format
     * `ReadingPositions.Encode` uses. Reading-position locators (CFI, page,
     * percentage) are ASCII, so the ASCII rules are exact; a rune >= 0x80 is passed
     * through literally (Go prints printable runes literally too).
     */
    fun goQuote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")

                '\\' -> sb.append("\\\\")

                '\u0007' -> sb.append("\\a")

                '\b' -> sb.append("\\b")

                '\u000B' -> sb.append("\\v")

                '\u000C' -> sb.append("\\f")

                '\n' -> sb.append("\\n")

                '\r' -> sb.append("\\r")

                '\t' -> sb.append("\\t")

                else -> when {
                    ch.code in 0x20..0x7E -> sb.append(ch)
                    ch.code < 0x20 || ch.code == 0x7F -> sb.append("\\x").append(hex2(ch.code))
                    else -> sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun hex2(v: Int): String {
        val d = "0123456789abcdef"
        return "" + d[(v ushr 4) and 0xF] + d[v and 0xF]
    }
}
