package one.rarebit.heyarr.mobile.search

import one.rarebit.heyarr.core.net.JsonScan

/**
 * One item a followed source's feed has yielded — heyarr-core `FollowedItem`
 * (`GET /api/v1/followed-sources/{id}/items`, #430, ADR-0056). It is the archive a
 * person reads to answer "what has this subscription actually got me": each item is
 * the source-stable [itemKey] (an `S02E05`, a podcast GUID, a video id) with a [title]
 * and, when the source projected one, the item-scoped [want] and whether heyarr holds
 * bytes for it ([archived]).
 *
 * [want] is **null** for an item no want was projected for — a `backfill=from_now`
 * source knows about back-catalogue episodes it deliberately did not ask for, and
 * reporting them rather than omitting them is what makes this an archive and not a
 * queue.
 */
data class FollowedItem(
    val id: String,
    val workId: String? = null,
    val editionId: String? = null,
    /** The feed's source-stable identity for this item; the listing's sort key. */
    val itemKey: String? = null,
    val title: String,
    /** When the source said it emitted the item; absent when the source did not say. */
    val publishedAt: String? = null,
    /** True when heyarr holds bytes for this item that satisfy the source's profile. */
    val archived: Boolean = false,
    /** The item-scoped want this source projected, or null when it projected none. */
    val want: ProjectedWant? = null,
    val createdAt: String? = null,
) {
    /** The projected want's id and §64's three acquisition axes (heyarr-core `FollowedItemWant`). */
    data class ProjectedWant(
        val desiredItemId: String? = null,
        val phase: String? = null,
        val content: String? = null,
        val placement: String? = null,
    ) {
        /** The one line the screen shows for the projected want. */
        val summary: String
            get() = listOfNotNull(
                phase?.takeIf { it.isNotBlank() },
                content?.takeIf { it.isNotBlank() },
                placement?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
    }

    /** The line the archive shows under the title: item key, published-at hint, archived tick. */
    val subtitle: String
        get() = listOfNotNull(
            itemKey?.takeIf { it.isNotBlank() },
            if (archived) "archived" else null,
        ).joinToString(" · ")
}

/**
 * Parser for the `{ items: [FollowedItem…], next_cursor? }` page — the same tolerant,
 * dependency-free scanning as the other readers, over the shared [JsonScan] primitives
 * (no `org.json`, which is stubbed in unit tests). A bare top-level array is tolerated.
 */
object FollowedItemsJson {

    private val ENVELOPE_KEYS = listOf("items", "followed_items", "data")

    fun parse(body: String): List<FollowedItem> = JsonScan.objectsOf(body, ENVELOPE_KEYS).mapNotNull { parseObject(it) }

    fun nextCursor(body: String): String? =
        JsonScan.rootObject(body)?.let { JsonScan.stringField(it, "next_cursor") }?.takeIf { it.isNotBlank() }

    private fun parseObject(obj: String): FollowedItem? {
        val id = JsonScan.stringField(obj, "id") ?: return null
        val want = JsonScan.objectAt(obj, "want")?.let { w ->
            FollowedItem.ProjectedWant(
                desiredItemId = JsonScan.stringField(w, "desired_item_id"),
                phase = JsonScan.stringField(w, "phase"),
                content = JsonScan.stringField(w, "content"),
                placement = JsonScan.stringField(w, "placement"),
            )
        }
        return FollowedItem(
            id = id,
            workId = JsonScan.stringField(obj, "work_id"),
            editionId = JsonScan.stringField(obj, "edition_id"),
            itemKey = JsonScan.stringField(obj, "item_key"),
            title = JsonScan.firstString(obj, listOf("title", "item_key")) ?: id,
            publishedAt = JsonScan.stringField(obj, "published_at"),
            archived = JsonScan.boolField(obj, "archived") ?: false,
            want = want,
            createdAt = JsonScan.stringField(obj, "created_at"),
        )
    }
}
