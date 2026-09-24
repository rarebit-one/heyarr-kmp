package one.rarebit.heyarr.mobile.search

import one.rarebit.heyarr.core.net.JsonScan
import one.rarebit.heyarr.mobile.net.Timestamps
import one.rarebit.heyarr.core.feeds.FollowedSource as FeedSource

/**
 * A source the user is subscribed to — one row of the "Following" list. Models the
 * live `FollowedSourceView` (`GET /api/v1/followed-sources`, heyarr-core
 * `internal/api/resources/followed.go`): a subscription that projects new
 * `DesiredItem`s over time. Its [id], the [workId] it follows, its inferred [type],
 * the [feedRef] it is polled through, the profile/monitor/backfill it was created
 * with, the archive's running counters ([itemsKnown] enumerated, [itemsArchived]
 * captured), [health] (`healthy`/`unhealthy`/`unknown`) and the poll timestamps.
 *
 * The server view carries no human title (only a `work_id`), so [title] falls back to
 * the work id for display until a work→title lookup is wired; that is what the
 * Following screen shows.
 */
data class FollowedSource(
    val id: String,
    val title: String,
    val workId: String? = null,
    val type: String? = null,
    val itemsKnown: Int? = null,
    val itemsArchived: Int? = null,
    val health: String? = null,
    /** The feed identity the node polls (a TVDB id, a channel id, a feed URL…). */
    val feedRef: String? = null,
    val qualityProfileId: String? = null,
    val monitor: Boolean? = null,
    /** `from_now` / `full`. */
    val backfill: String? = null,
    val reason: String? = null,
    val createdAt: String? = null,
    val lastPolledAt: String? = null,
    val nextPollAt: String? = null,
) {
    /** The timestamp "recent first" orders on: last polled, else created. */
    val recency: String? get() = lastPolledAt ?: createdAt
}

/**
 * This source as `:core`'s [FeedSource] (`core.feeds.FollowedSource`) — the model the
 * shared search grouping (`SearchGrouping.matchSources`, `SearchRow.SourceRow`) is written
 * over. The phone's own model keeps the extra subscription fields (profile, monitor,
 * backfill, poll times) for the Following screens; search reads only what both carry. The
 * archive counters the phone reads as absent display as 0, exactly as the search row
 * showed them before (`itemsArchived ?: 0`).
 */
fun FollowedSource.asFeedSource(): FeedSource = FeedSource(
    id = id,
    title = title,
    workId = workId,
    type = type,
    itemsKnown = itemsKnown ?: 0,
    itemsArchived = itemsArchived ?: 0,
    health = health,
    feedRef = feedRef,
)

/**
 * Parser for the `GET /api/v1/followed-sources` body (`{ "followed_sources": [ … ] }`)
 * — the same tolerant, dependency-free scanning as the other readers, over the
 * shared [JsonScan] primitives (no `org.json`, which is stubbed in unit tests). A
 * bare top-level array is also tolerated.
 */
object FollowedSourcesJson {

    private val ENVELOPE_KEYS = listOf("followed_sources", "items", "followed", "sources", "data")

    fun parse(body: String): List<FollowedSource> =
        JsonScan.objectsOf(body, ENVELOPE_KEYS).mapNotNull { parseObject(it) }

    /** One `FollowedSourceView` object (a `POST` response), or null. */
    fun parseOne(body: String): FollowedSource? = JsonScan.rootObject(body)?.let { parseObject(it) }

    /** Order most-recently-polled first (else created), unknowns last. */
    fun recentFirst(sources: List<FollowedSource>): List<FollowedSource> =
        Timestamps.recentFirst(sources) { it.recency }

    private fun parseObject(obj: String): FollowedSource? {
        val id = JsonScan.firstString(obj, listOf("id", "source_id")) ?: return null
        val workId = JsonScan.stringField(obj, "work_id")
        return FollowedSource(
            id = id,
            title = JsonScan.firstString(obj, listOf("title", "name", "sort_title")) ?: workId ?: id,
            workId = workId,
            type = JsonScan.firstString(obj, listOf("type", "content_type", "source_type", "kind")),
            itemsKnown = JsonScan.intField(obj, "items_known") ?: JsonScan.intField(obj, "known"),
            itemsArchived = JsonScan.intField(obj, "items_archived") ?: JsonScan.intField(obj, "archived"),
            health = JsonScan.firstString(obj, listOf("health", "status")),
            feedRef = JsonScan.stringField(obj, "feed_ref"),
            qualityProfileId = JsonScan.stringField(obj, "quality_profile_id"),
            monitor = JsonScan.boolField(obj, "monitor"),
            backfill = JsonScan.stringField(obj, "backfill"),
            reason = JsonScan.stringField(obj, "reason"),
            createdAt = JsonScan.stringField(obj, "created_at"),
            lastPolledAt = JsonScan.stringField(obj, "last_polled_at"),
            nextPollAt = JsonScan.stringField(obj, "next_poll_at"),
        )
    }
}
