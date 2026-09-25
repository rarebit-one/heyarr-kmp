package one.rarebit.heyarr.mobile.library

import one.rarebit.heyarr.core.net.JsonScan

/**
 * A minimal, dependency-free parser for heyarr's `GET /api/v1/works` list body and
 * the single `GET /api/v1/works/{id}` object, kept JVM-testable (no org.json, which
 * is stubbed in unit tests) for the same reason as the login `MiniJson`.
 *
 * It is tolerant of the two envelope shapes a list endpoint may return — a bare
 * top-level array `[ {…}, {…} ]` or an object wrapping one under `items` / `works` /
 * `data` (the live shape is `{ "items": [Work…], "next_cursor"? }`) — and extracts,
 * per element, an `id` and a display title (from `title`, else `name`, else
 * `sort_title`) plus an optional kind (`content_type` / `kind` / `type` /
 * `media_type`), `year`, `work_key` and the two timestamps. Anything richer is
 * intentionally dropped: this feeds a browse list and a detail header.
 *
 * This is a scaffold reader. When a shared, generated client (or kotlinx.serialization
 * against the published OpenAPI) lands, swap this for it.
 */
object WorksJson {

    private val TITLE_KEYS = listOf("title", "name", "sort_title")
    private val KIND_KEYS = listOf("content_type", "kind", "type", "media_type")

    // Playback handles, tolerantly read when a browse row inlines a primary asset.
    private val HASH_KEYS = listOf("blob_hash", "content_hash", "hash")
    private val MIME_KEYS = listOf("mime", "mime_type")
    private val ENVELOPE_KEYS = listOf("items", "works", "data")

    // The string attributes worth carrying to a detail screen (a provider's synopsis, the creator, a genre).
    private val ATTRIBUTE_KEYS =
        listOf("overview", "synopsis", "description", "summary", "author", "artist", "narrator", "genre", "runtime", "pages", "album", "series", "host")

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
        // The browse embeds (heyarr-core ADR-0075), when the row carries them. Each is
        // read from its OWN slice, so a `blob_hash` inside `primary_asset` is never
        // mistaken for a top-level one (JsonScan reads depth-1 only).
        val primary = JsonScan.objectAt(obj, "primary_asset")
        val artwork = JsonScan.objectAt(obj, "artwork")
        val attributes = JsonScan.objectAt(obj, "attributes")
        return Work(
            id = id,
            title = title,
            kind = JsonScan.firstString(obj, KIND_KEYS),
            blobHash = JsonScan.firstString(obj, HASH_KEYS) ?: primary?.let { JsonScan.stringField(it, "blob_hash") },
            mime = JsonScan.firstString(obj, MIME_KEYS) ?: primary?.let { JsonScan.stringField(it, "mime") },
            primaryAssetId = primary?.let { JsonScan.stringField(it, "asset_id") },
            artworkPath = artwork?.let { JsonScan.stringField(it, "content_url") },
            artist = attributes?.let { JsonScan.stringField(it, "artist") },
            author = attributes?.let { JsonScan.stringField(it, "author") },
            attributes =
            attributes?.let { a ->
                ATTRIBUTE_KEYS.mapNotNull { k -> JsonScan.stringField(a, k)?.let { k to it } }.toMap()
            }
                ?: emptyMap(),
            year = JsonScan.intField(obj, "year"),
            workKey = JsonScan.stringField(obj, "work_key"),
            sortTitle = JsonScan.stringField(obj, "sort_title"),
            externalIds = JsonScan.stringMap(obj, "external_ids"),
            createdAt = JsonScan.stringField(obj, "created_at"),
            updatedAt = JsonScan.stringField(obj, "updated_at"),
        )
    }
}
