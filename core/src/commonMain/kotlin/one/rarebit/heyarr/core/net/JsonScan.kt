package one.rarebit.heyarr.core.net

/**
 * Dependency-free JSON scanning primitives behind the hand-rolled readers. Copied from
 * heyarr-mobile's `net.JsonScan` (the org's deliberate "no serialization library"
 * stance; a shared KMP extraction comes later). Tolerant, top-level field reads over a
 * balanced object slice — every reader is fed one object's own slice, so a field read
 * never strays into a nested object with the same key.
 */
object JsonScan {

    /** The top-level object slice of [body], or null if it is not an object. */
    fun rootObject(body: String): String? {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return null
        return sliceBalanced(trimmed, 0, '{', '}')
    }

    /**
     * The object substrings of a JSON array: the body itself if it is a bare array,
     * else the array under the first present key among [keys].
     */
    fun objectsOf(body: String, keys: List<String>): List<String> {
        val array = arrayOf(body, keys) ?: return emptyList()
        val out = ArrayList<String>()
        var i = 0
        while (i < array.length) {
            if (array[i] == '{') {
                val obj = sliceBalanced(array, i, '{', '}') ?: break
                out.add(obj)
                i += obj.length
            } else {
                i++
            }
        }
        return out
    }

    /** The `[ … ]` slice: the whole body if it's an array, else the array under a known key. */
    fun arrayOf(body: String, keys: List<String>): String? {
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) return sliceBalanced(trimmed, 0, '[', ']')
        for (key in keys) {
            val at = topLevelKey(trimmed, key) ?: continue
            val open = trimmed.indexOf('[', at)
            if (open < 0) continue
            return sliceBalanced(trimmed, open, '[', ']')
        }
        return null
    }

    /** The nested object under [key] (`"key": { … }`), or null when absent/null. */
    fun objectAt(json: String, key: String): String? {
        val i = valueStart(json, key) ?: return null
        if (json[i] != '{') return null
        return sliceBalanced(json, i, '{', '}')
    }

    /** The first present, non-null top-level string field among [keys]. */
    fun firstString(obj: String, keys: List<String>): String? {
        for (k in keys) stringField(obj, k)?.let { return it }
        return null
    }

    /** A top-level string field, escapes decoded through [JsonEscapes]. */
    fun stringField(json: String, key: String): String? {
        var i = valueStart(json, key) ?: return null
        if (json[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            when {
                c == '\\' && i + 1 < json.length -> i = JsonEscapes.append(sb, json, i)

                c == '"' -> return sb.toString()

                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return null
    }

    /**
     * A top-level object of string→string entries under [key] (e.g. heyarr's
     * `external_ids`: `{ "tmdb": "42", "imdb": "tt7" }`), in document order. Absent,
     * `null` or a non-object value → an empty map; a non-string value is skipped
     * rather than guessed at. Fed the object's own slice, so a nested `external_ids`
     * is never picked up.
     */
    fun stringMap(json: String, key: String): Map<String, String> {
        val obj = objectAt(json, key) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        var i = 1 // past the opening '{'
        while (i < obj.length) {
            val c = obj[i]
            when {
                c == '"' -> {
                    val k = readStringAt(obj, i) ?: break
                    var j = k.second
                    while (j < obj.length &&
                        (obj[j] == ' ' || obj[j] == '\t' || obj[j] == '\n' || obj[j] == '\r' || obj[j] == ':')
                    ) {
                        j++
                    }
                    if (j < obj.length && obj[j] == '"') {
                        val v = readStringAt(obj, j) ?: break
                        out[k.first] = v.first
                        i = v.second
                    } else {
                        // A non-string value for this key: skip it, keep scanning for more entries.
                        i = j + 1
                    }
                }

                c == '}' -> return out

                else -> i++
            }
        }
        return out
    }

    /**
     * A top-level array of plain JSON strings under [key] (e.g. a quality profile's
     * `content_types`: `["movie","series"]`), in document order. Absent, `null` or a
     * non-array value → an empty list; a non-string element is skipped rather than
     * guessed at.
     */
    fun stringArray(json: String, key: String): List<String> {
        val arr = arrayOf(json, listOf(key)) ?: return emptyList()
        val out = ArrayList<String>()
        var i = 0
        while (i < arr.length) {
            val c = arr[i]
            if (c == '"') {
                val v = readStringAt(arr, i) ?: break
                out.add(v.first)
                i = v.second
            } else {
                i++
            }
        }
        return out
    }

    /**
     * Read the JSON string literal starting at [start] (which must be `"`), returning
     * the decoded value and the index just past the closing quote, or null if unterminated.
     */
    private fun readStringAt(json: String, start: Int): Pair<String, Int>? {
        var i = start + 1
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            when {
                c == '\\' && i + 1 < json.length -> i = JsonEscapes.append(sb, json, i)

                c == '"' -> return sb.toString() to (i + 1)

                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return null
    }

    /** A top-level integer field (a JSON number; a quoted number is NOT accepted). */
    fun longField(json: String, key: String): Long? {
        var i = valueStart(json, key) ?: return null
        if (json[i] == '"') return null
        val start = i
        if (json[i] == '-' || json[i] == '+') i++
        while (i < json.length && json[i].isDigit()) i++
        return json.substring(start, i).toLongOrNull()
    }

    fun intField(json: String, key: String): Int? = longField(json, key)?.toInt()

    /** A top-level bare number field read as a double (`6960.5`, `12`, `-1e3`), or null when absent/null/quoted. */
    fun doubleField(json: String, key: String): Double? {
        val i = valueStart(json, key) ?: return null
        if (json.startsWith("null", i) || json[i] == '"') return null
        var j = i
        while (j < json.length && (json[j].isDigit() || json[j] in "+-.eE")) j++
        return json.substring(i, j).toDoubleOrNull()
    }

    /** The first present, non-null top-level integer field among [keys]. */
    fun firstInt(obj: String, keys: List<String>): Int? {
        for (k in keys) intField(obj, k)?.let { return it }
        return null
    }

    /** A top-level boolean field (`true`/`false`); null when absent, null, or not a bool. */
    fun boolField(json: String, key: String): Boolean? {
        val i = valueStart(json, key) ?: return null
        return when {
            json.startsWith("true", i) -> true
            json.startsWith("false", i) -> false
            else -> null
        }
    }

    /**
     * Index of the first char of the value for a **top-level** [key] (depth 1 of the
     * object slice), skipping `:` and whitespace; null when absent or `null`.
     */
    fun valueStart(json: String, key: String): Int? {
        var i = topLevelKey(json, key) ?: return null
        i += key.length + 2
        while (i < json.length &&
            (json[i] == ' ' || json[i] == '\t' || json[i] == '\n' || json[i] == '\r' || json[i] == ':')
        ) {
            i++
        }
        return if (i >= json.length || json.startsWith("null", i)) null else i
    }

    /**
     * Index of `"key"` as a depth-1 key of the object [json] (which must start with
     * `{`), or null. Walks the object honouring strings and nesting so a same-named
     * key inside a nested object or array is never matched.
     */
    private fun topLevelKey(json: String, key: String): Int? {
        val needle = "\"$key\""
        var depth = 0
        var inStr = false
        var i = 0
        while (i < json.length) {
            val c = json[i]
            if (inStr) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '"') inStr = false
                i++
                continue
            }
            when (c) {
                '{', '[' -> depth++

                '}', ']' -> depth--

                '"' -> {
                    if (depth == 1 && json.startsWith(needle, i)) {
                        var j = i + needle.length
                        while (j < json.length &&
                            (json[j] == ' ' || json[j] == '\t' || json[j] == '\n' || json[j] == '\r')
                        ) {
                            j++
                        }
                        if (j < json.length && json[j] == ':') return i
                    }
                    inStr = true
                }
            }
            i++
        }
        return null
    }

    /**
     * Slice a brace/bracket-balanced substring starting at [start] (which must be
     * [open]), respecting string literals and escapes. Returns null if unbalanced.
     */
    fun sliceBalanced(s: String, start: Int, open: Char, close: Char): String? {
        var depth = 0
        var inStr = false
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true

                    open -> depth++

                    close -> {
                        depth--
                        if (depth == 0) return s.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }
}
