package one.rarebit.heyarr.desktop.feeds

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.feeds.*
import one.rarebit.heyarr.core.net.HttpTransport
import java.net.URLEncoder

/**
 * The FEEDS (Archive) browse reach — read-only:
 *
 *  - [listSources] `GET /api/v1/followed-sources`             — the tracked sources
 *  - [listItems]   `GET /api/v1/followed-sources/{id}/items`  — a source's archived entries
 *
 * Opening an item's archived bytes is a SECOND hop the UI does with the existing
 * `WorkDetailClient` (`GET /works/{item.workId}` → `primary_asset`), because a
 * followed-item carries no blob/content URL of its own.
 */
class FeedsClient(
    private val http: HttpTransport,
    private val baseUrl: String,
    private val credential: Credential,
) {
    /** All followed sources (the list endpoint is unpaged: `{ "followed_sources": [ … ] }`). */
    fun listSources(): List<FollowedSource> {
        val resp = http.get(sourcesUrl(baseUrl), credential.asHeader())
        require(resp.status == 200) { "feeds: GET /followed-sources failed: HTTP ${resp.status}" }
        return FollowedSourcesJson.parse(resp.body)
    }

    /** One source's archived items, paged on `next_cursor` to the end. */
    fun listItems(sourceId: String): List<FollowedItem> {
        val all = ArrayList<FollowedItem>()
        var cursor: String? = null
        var pages = 0
        do {
            val resp = http.get(itemsUrl(baseUrl, sourceId, cursor), credential.asHeader())
            require(resp.status == 200) { "feeds: GET /followed-sources/$sourceId/items failed: HTTP ${resp.status}" }
            all.addAll(FollowedItemsJson.parse(resp.body))
            cursor = FollowedItemsJson.nextCursor(resp.body)
            pages++
        } while (cursor != null && pages < MAX_PAGES)
        return all
    }

    companion object {
        const val PAGE_LIMIT = 200
        const val MAX_PAGES = 50

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        fun sourcesUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/api/v1/followed-sources"

        fun itemsUrl(baseUrl: String, sourceId: String, cursor: String? = null): String {
            val base = baseUrl.trimEnd('/') + "/api/v1/followed-sources/" + enc(sourceId) + "/items?limit=" + PAGE_LIMIT
            return if (cursor.isNullOrBlank()) base else "$base&cursor=${enc(cursor)}"
        }
    }
}
