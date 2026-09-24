package one.rarebit.heyarr.desktop.library

import one.rarebit.heyarr.core.net.JsonScan

/**
 * A minimal, dependency-free parser for heyarr's `GET /api/v1/works` list body and the
 * single `GET /api/v1/works/{id}` object. Adapted from heyarr-mobile's
 * `library.WorksJson` (trimmed to this slice's fields).
 *
 * Tolerant of the two envelope shapes a list endpoint may return — a bare top-level
 * array `[ {…} ]` or an object wrapping one under `items` / `works` / `data` (the live
 * shape is `{ "items": [Work…], "next_cursor"? }`).
 */
object WorksJson {

    private val TITLE_KEYS = listOf("title", "name", "sort_title")
    private val KIND_KEYS = listOf("content_type", "kind", "type", "media_type")
    private val ENVELOPE_KEYS = listOf("items", "works", "data")

    /** Parse a works-list response body into [Work]s, skipping any element missing an id. */
    fun parse(body: String): List<Work> = JsonScan.objectsOf(body, ENVELOPE_KEYS).mapNotNull { parseObject(it) }

    /** Parse one `Work` object body (`GET /works/{id}`), or null if it has no id. */
    fun parseOne(body: String): Work? = JsonScan.rootObject(body)?.let { parseObject(it) }

    /** The page's `next_cursor`, when the server says there is another page. */
    fun nextCursor(body: String): String? =
        JsonScan.rootObject(body)?.let { JsonScan.stringField(it, "next_cursor") }?.takeIf { it.isNotBlank() }

    private fun parseObject(obj: String): Work? {
        val id = JsonScan.stringField(obj, "id") ?: return null
        val title = JsonScan.firstString(obj, TITLE_KEYS) ?: id
        val attributes = JsonScan.objectAt(obj, "attributes")
        return Work(
            id = id,
            title = title,
            kind = JsonScan.firstString(obj, KIND_KEYS),
            artist = attributes?.let { JsonScan.stringField(it, "artist") },
            author = attributes?.let { JsonScan.stringField(it, "author") },
            year = JsonScan.intField(obj, "year"),
            workKey = JsonScan.stringField(obj, "work_key"),
            sortTitle = JsonScan.stringField(obj, "sort_title"),
            createdAt = JsonScan.stringField(obj, "created_at"),
            updatedAt = JsonScan.stringField(obj, "updated_at"),
            artworkPath = JsonScan.objectAt(obj, "artwork")?.let { JsonScan.stringField(it, "content_url") },
        )
    }
}
