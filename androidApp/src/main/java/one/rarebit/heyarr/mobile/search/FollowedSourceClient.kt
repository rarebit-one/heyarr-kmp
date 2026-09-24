package one.rarebit.heyarr.mobile.search

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.library.LibraryClient
import java.net.URLEncoder

/**
 * The two reads behind one **followed source's** detail (heyarr-core #430,
 * `internal/api/resources/followed.go`):
 *
 * - `GET /api/v1/followed-sources/{id}` → the subscription itself, with its derived
 *   counts and health — so the detail screen no longer re-reads the whole list and
 *   picks its id out (the pre-#430 workaround).
 * - `GET /api/v1/followed-sources/{id}/items?limit=200[&cursor]` → what the source has
 *   archived and what it merely knows about ([FollowedItem]s), paged by `item_key`.
 *
 * Both are under the `read` floor. A 404 on the source read is "no longer followed"
 * ([SourceResult.Gone]); anything else non-200 throws so the caller can surface it.
 */
class FollowedSourceClient(
    private val http: HttpTransport,
    private val baseUrl: String,
    private val credential: Credential,
) {
    /** The outcome of the source read — a value the VM reads directly. */
    sealed interface SourceResult {
        data class Found(val source: FollowedSource) : SourceResult

        /** A `404` — the subscription is gone (unfollowed elsewhere, or never existed). */
        data object Gone : SourceResult
    }

    /** `GET /followed-sources/{id}`; a 404 is [SourceResult.Gone], any other non-200 throws. */
    fun source(id: String): SourceResult {
        val resp = http.get(sourceUrl(baseUrl, id), credential.asHeader())
        if (resp.status == 404) return SourceResult.Gone
        require(resp.status == 200) { "source: GET /followed-sources/$id failed: HTTP ${resp.status}" }
        val parsed = FollowedSourcesJson.parseOne(resp.body)
            ?: throw IllegalStateException("source: GET /followed-sources/$id returned no source object")
        return SourceResult.Found(parsed)
    }

    /**
     * Every archived/known item for [id], oldest→newest as the feed is stable
     * (`item_key` order). Pages `next_cursor` to the end. Throws on a non-200.
     */
    fun items(id: String): List<FollowedItem> {
        val all = ArrayList<FollowedItem>()
        var cursor: String? = null
        var pages = 0
        do {
            val resp = http.get(itemsUrl(baseUrl, id, cursor), credential.asHeader())
            require(resp.status == 200) { "source items: GET /followed-sources/$id/items failed: HTTP ${resp.status}" }
            all.addAll(FollowedItemsJson.parse(resp.body))
            cursor = FollowedItemsJson.nextCursor(resp.body)
            pages++
        } while (cursor != null && pages < LibraryClient.MAX_PAGES)
        return all
    }

    companion object {
        private fun base(baseUrl: String) = baseUrl.trimEnd('/') + "/api/v1/followed-sources"
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        fun sourceUrl(baseUrl: String, id: String): String = base(baseUrl) + "/" + enc(id)

        fun itemsUrl(baseUrl: String, id: String, cursor: String? = null): String {
            val u = base(baseUrl) + "/" + enc(id) + "/items?limit=" + LibraryClient.PAGE_LIMIT
            return if (cursor.isNullOrBlank()) u else u + "&cursor=" + enc(cursor)
        }
    }
}
