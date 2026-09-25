package one.rarebit.heyarr.desktop.catalog

import one.rarebit.heyarr.core.net.JsonScan

/**
 * A browse grouping row — heyarr-core `GroupSummary` (ADR-0075), the shape returned by
 * both `GET /api/v1/artists` (music works grouped by `attributes.artist`) and
 * `GET /api/v1/authors` (book works grouped by `attributes.author`). The two endpoints
 * are byte-identical in shape, so Music and Books share this one model and reader.
 *
 * The `artwork` embed is the poster of the group's first work; only its `content_url` is
 * kept here (a relative `/api/v1/blobs/<hash>/content` path). This desktop v1 renders the
 * name + count as text and does not yet fetch cover images, so [artworkPath] is carried
 * for a later image pass but not otherwise used.
 */
data class Grouping(val name: String, val workCount: Int = 0, val artworkPath: String? = null)

/**
 * Dependency-free reader for the grouped browse bodies (`artists` / `authors`). Same
 * hand-rolled [JsonScan] stance as [one.rarebit.heyarr.desktop.library.WorksJson];
 * tolerant of the `{ "items": [...], "next_cursor"? }` envelope (the live shape) or a
 * bare top-level array. A row with no `name` is skipped.
 */
object GroupingJson {

    private val ENVELOPE_KEYS = listOf("items", "artists", "authors", "data")

    /** Parse a grouped-browse response body into [Grouping]s, skipping any nameless row. */
    fun parse(body: String): List<Grouping> = JsonScan.objectsOf(body, ENVELOPE_KEYS).mapNotNull { parseObject(it) }

    /** The page's `next_cursor`, when the server says there is another page. */
    fun nextCursor(body: String): String? =
        JsonScan.rootObject(body)?.let { JsonScan.stringField(it, "next_cursor") }?.takeIf { it.isNotBlank() }

    private fun parseObject(obj: String): Grouping? {
        val name = JsonScan.stringField(obj, "name")?.takeIf { it.isNotBlank() } ?: return null
        val artwork = JsonScan.objectAt(obj, "artwork")
        return Grouping(
            name = name,
            workCount = JsonScan.intField(obj, "work_count") ?: 0,
            artworkPath = artwork?.let { JsonScan.stringField(it, "content_url") },
        )
    }
}
