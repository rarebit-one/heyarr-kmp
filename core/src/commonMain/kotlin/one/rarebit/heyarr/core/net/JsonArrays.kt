package one.rarebit.heyarr.core.net

/**
 * Small pure helpers for the raw JSON-array shapes `JsonScan` hands back as slices.
 *
 * [parseStrings] used to live as a companion on the desktop-only `RecentSearches`
 * (which reads/writes a `java.io.File`); it was extracted here so the pure telemetry /
 * capabilities parsers in `:core` can decode `["a","b"]` string arrays without dragging
 * a file-backed, platform-bound class into `commonMain`.
 */
object JsonArrays {
    /** A bare `["a","b"]` string array → its decoded members. */
    fun parseStrings(array: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < array.length) {
            if (array[i] == '"') {
                val sb = StringBuilder()
                i++
                while (i < array.length && array[i] != '"') {
                    if (array[i] == '\\' && i + 1 < array.length) {
                        i = JsonEscapes.append(sb, array, i)
                    } else {
                        sb.append(array[i])
                        i++
                    }
                }
                out.add(sb.toString())
            }
            i++
        }
        return out
    }
}
