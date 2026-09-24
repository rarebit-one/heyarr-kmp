package one.rarebit.heyarr.mobile.search

import one.rarebit.heyarr.core.net.JsonEscapes
import one.rarebit.heyarr.core.net.JsonScan

/**
 * Dependency-free parser for heyarr's `POST /api/v1/search` response body
 * (`{ "works": [ { work_id, content_type, title, year? } ] }`, heyarr-core
 * `internal/api/resources/search.go` `SearchContent`; LIKE-matches `sort_title`,
 * filters on `content_type`).
 *
 * Kept JVM-testable (no `org.json`, which is stubbed in unit tests) for the same
 * reason as [one.rarebit.heyarr.mobile.library.WorksJson], whose scanning primitives
 * this mirrors. It tolerates a bare top-level array or an object wrapping one under
 * `items` / `works` / `data`, and per element extracts a `work_id` (or `id`), a
 * display title (`title` → `name` → `sort_title`), an optional `content_type` (→
 * `type` / `media_type` / `kind`), and an optional numeric `year`.
 * The poster is the `artwork` embed (ADR-0075); `WorkSummary` has no poster URL, so
 * none is read (docs/proposals/0001: the parsers stay hand-written, held to the spec
 * by `OpenApiContractTest`).
 */
object SearchResultsJson {

    private val TITLE_KEYS = listOf("title", "name", "sort_title")
    private val TYPE_KEYS = listOf("content_type", "type", "media_type", "kind")

    /** Parse a works-list response body into [SearchResult]s, skipping elements missing an id. */
    fun parse(body: String): List<SearchResult> {
        val array = extractArray(body) ?: return emptyList()
        return splitObjects(array).mapNotNull { obj ->
            val id = firstString(obj, listOf("id", "work_id")) ?: return@mapNotNull null
            SearchResult(
                workId = id,
                title = firstString(obj, TITLE_KEYS) ?: id,
                type = firstString(obj, TYPE_KEYS),
                year = firstInt(obj, listOf("year")),
                // The feed identity a one-tap follow needs (heyarr-core WorkSummary.tvdb_id,
                // omitempty — absent for a work with no stored external id).
                tvdbId = firstString(obj, listOf("tvdb_id", "feed_ref")),
                // The poster (ADR-0075), read from the embed's own slice.
                artworkPath = JsonScan.objectAt(obj, "artwork")?.let { JsonScan.stringField(it, "content_url") },
            )
        }
    }

    /** Both lists of a `POST /search` answer; `episodes` is empty against an older node. */
    fun parseHits(body: String): SearchHits = SearchHits(works = parse(body), episodes = parseEpisodes(body))

    /** The `episodes` list (ADR-0075), each read from its own slice; a hit missing an id is skipped. */
    fun parseEpisodes(body: String): List<EpisodeResult> =
        JsonScan.objectsOf(body, listOf("episodes")).mapNotNull { obj ->
            val id = JsonScan.stringField(obj, "id") ?: return@mapNotNull null
            val asset = JsonScan.objectAt(obj, "primary_asset")
            EpisodeResult(
                kind = JsonScan.stringField(obj, "kind") ?: "edition",
                id = id,
                workId = JsonScan.stringField(obj, "work_id") ?: "",
                workTitle = JsonScan.stringField(obj, "work_title") ?: "",
                contentType = JsonScan.stringField(obj, "content_type"),
                title = JsonScan.stringField(obj, "title") ?: id,
                season = JsonScan.intField(obj, "season"),
                episode = JsonScan.intField(obj, "episode"),
                assetId = asset?.let { JsonScan.stringField(it, "asset_id") },
                blobHash = asset?.let { JsonScan.stringField(it, "blob_hash") },
                mime = asset?.let { JsonScan.stringField(it, "mime") },
            )
        }

    private fun extractArray(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) return sliceBalanced(trimmed, 0, '[', ']')
        for (key in listOf("items", "works", "data")) {
            val at = trimmed.indexOf("\"$key\"")
            if (at < 0) continue
            val open = trimmed.indexOf('[', at)
            if (open < 0) continue
            return sliceBalanced(trimmed, open, '[', ']')
        }
        return null
    }

    private fun splitObjects(array: String): List<String> {
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

    private fun firstString(obj: String, keys: List<String>): String? {
        for (k in keys) stringField(obj, k)?.let { return it }
        return null
    }

    private fun firstInt(obj: String, keys: List<String>): Int? {
        for (k in keys) intField(obj, k)?.let { return it }
        return null
    }

    private fun sliceBalanced(s: String, start: Int, open: Char, close: Char): String? {
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

    private fun stringField(json: String, key: String): String? {
        var i = valueStart(json, key) ?: return null
        if (json.startsWith("null", i)) return null
        if (json[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i]
            when {
                c == '\\' && i + 1 < json.length -> {
                    i = JsonEscapes.append(sb, json, i)
                }

                c == '"' -> return sb.toString()

                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return null
    }

    /** Read a top-level bare (unquoted) integer field, or null when absent/null/non-numeric. */
    private fun intField(json: String, key: String): Int? {
        var i = valueStart(json, key) ?: return null
        if (json.startsWith("null", i)) return null
        if (json[i] == '"') return null // a quoted value is not a bare number
        val start = i
        if (i < json.length && (json[i] == '-' || json[i] == '+')) i++
        while (i < json.length && json[i].isDigit()) i++
        val slice = json.substring(start, i)
        return slice.toIntOrNull()
    }

    /** Index just past the `"key" :` whitespace, at the first char of the value; null if key absent. */
    private fun valueStart(json: String, key: String): Int? {
        val needle = "\"$key\""
        var i = json.indexOf(needle)
        if (i < 0) return null
        i += needle.length
        while (i < json.length && (json[i] == ' ' || json[i] == '\t' || json[i] == ':')) i++
        return if (i >= json.length) null else i
    }
}
