package one.rarebit.heyarr.core.feeds

import one.rarebit.heyarr.core.net.JsonScan

/**
 * A followed source — heyarr-core `FollowedSourceView` from `GET /api/v1/followed-sources`
 * (the Archive: RSS feeds, podcasts, channels, series the node tracks). This desktop slice
 * is read-only; the write actions (follow/unfollow) are out of scope.
 */
data class FollowedSource(
    val id: String,
    val title: String,
    val workId: String? = null,
    val type: String? = null,
    val itemsKnown: Int = 0,
    val itemsArchived: Int = 0,
    val health: String? = null,
    val feedRef: String? = null,
) {
    /** A one-line subtitle: type · N archived / M known · health. */
    val subtitle: String
        get() = listOfNotNull(
            type?.takeIf { it.isNotBlank() },
            "$itemsArchived/$itemsKnown archived",
            health?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
}

/**
 * An archived entry under a source — heyarr-core `FollowedItemView` from
 * `GET /api/v1/followed-sources/{id}/items`. Crucially the item carries NO blob or
 * content URL of its own: the archived bytes hang off its [workId] (resolved via
 * `GET /works/{id}` → `primary_asset`), and [archived] says whether the node actually
 * holds them yet.
 */
data class FollowedItem(
    val id: String,
    val title: String,
    val workId: String? = null,
    val editionId: String? = null,
    val itemKey: String? = null,
    val publishedAt: String? = null,
    val archived: Boolean = false,
) {
    val subtitle: String
        get() = listOfNotNull(
            itemKey?.takeIf { it.isNotBlank() },
            publishedAt?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
}

/**
 * Reader for the followed-source LIST body: `{ "followed_sources": [ … ] }` (also tolerant
 * of `items` / `sources` / `data`, or a bare array), plus one bare object for
 * `GET /followed-sources/{id}`.
 */
object FollowedSourcesJson {

    private val ENVELOPE_KEYS = listOf("followed_sources", "items", "sources", "data")
    private val TITLE_KEYS = listOf("title", "name", "sort_title")
    private val TYPE_KEYS = listOf("type", "content_type", "source_type", "kind")
    private val HEALTH_KEYS = listOf("health", "status")

    fun parse(body: String): List<FollowedSource> =
        JsonScan.objectsOf(body, ENVELOPE_KEYS).mapNotNull { parseObject(it) }

    fun parseOne(body: String): FollowedSource? = JsonScan.rootObject(body)?.let { parseObject(it) }

    private fun parseObject(obj: String): FollowedSource? {
        val id = JsonScan.firstString(obj, listOf("id", "source_id")) ?: return null
        val workId = JsonScan.stringField(obj, "work_id")
        return FollowedSource(
            id = id,
            title = JsonScan.firstString(obj, TITLE_KEYS) ?: workId ?: id,
            workId = workId,
            type = JsonScan.firstString(obj, TYPE_KEYS),
            itemsKnown = JsonScan.firstInt(obj, listOf("items_known", "known")) ?: 0,
            itemsArchived = JsonScan.firstInt(obj, listOf("items_archived", "archived_count")) ?: 0,
            health = JsonScan.firstString(obj, HEALTH_KEYS),
            feedRef = JsonScan.stringField(obj, "feed_ref"),
        )
    }
}

/** Reader for the items body: `{ "items": [ … ], "next_cursor"? }` (tolerant of `followed_items` / `data`). */
object FollowedItemsJson {

    private val ENVELOPE_KEYS = listOf("items", "followed_items", "data")

    fun parse(body: String): List<FollowedItem> = JsonScan.objectsOf(body, ENVELOPE_KEYS).mapNotNull { parseObject(it) }

    fun nextCursor(body: String): String? =
        JsonScan.rootObject(body)?.let { JsonScan.stringField(it, "next_cursor") }?.takeIf { it.isNotBlank() }

    private fun parseObject(obj: String): FollowedItem? {
        val id = JsonScan.stringField(obj, "id") ?: return null
        return FollowedItem(
            id = id,
            title = JsonScan.firstString(obj, listOf("title", "item_key")) ?: id,
            workId = JsonScan.stringField(obj, "work_id"),
            editionId = JsonScan.stringField(obj, "edition_id"),
            itemKey = JsonScan.stringField(obj, "item_key"),
            publishedAt = JsonScan.stringField(obj, "published_at"),
            archived = JsonScan.boolField(obj, "archived") ?: false,
        )
    }
}
