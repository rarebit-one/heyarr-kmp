package one.rarebit.heyarr.mobile.library

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.catalog.WorksPage
import one.rarebit.heyarr.mobile.net.Timestamps
import java.net.URLEncoder

/**
 * Browses heyarr's **native** library reach — `GET /api/v1/works` — authenticated
 * with the caller's [Credential] (a `Device` cert+proof, or a bootstrap `Bearer`
 * session token). This is the reach surface the scaffold DEMONSTRATES end-to-end
 * (URL + auth header + parse).
 *
 * `/api/v1/works` and `/api/v1/works/{id}` are the real server routes
 * (internal/api/resources). The list is paged (`{items, next_cursor?}`, server max
 * 200 per page). [page] fetches one page at a time in `sort=recent` order — the
 * server sorts by `(created_at, id)` descending (ADR-0075); the Library tab
 * streams pages incrementally through [LibraryPager]. [listWorks] walks every page
 * (without the `sort` parameter, so the server uses its default alphabetical order)
 * and then sorts the full result client-side via [one.rarebit.heyarr.mobile.net.Timestamps.recentFirst].
 * Response parsing goes through [WorksJson] so it is exercised on plain JVM in CI.
 */
class LibraryClient(private val http: HttpTransport, private val baseUrl: String, private val credential: Credential) {
    /**
     * Fetch one page of the library in `sort=recent` order. The first page should be
     * requested with [FIRST_PAGE_LIMIT] so the first paint arrives quickly; subsequent
     * pages can use the full [PAGE_LIMIT]. [cursor] null starts from the beginning.
     * Returns a [WorksPage] so the caller can thread the next cursor back in.
     */
    fun page(cursor: String? = null, limit: Int = PAGE_LIMIT): WorksPage {
        val resp = http.get(worksUrl(baseUrl, cursor, limit, "recent"), credential.asHeader())
        require(resp.status == 200) { "library: GET /works failed: HTTP ${resp.status}" }
        return WorksPage(items = WorksJson.parse(resp.body), nextCursor = WorksJson.nextCursor(resp.body))
    }

    /**
     * Fetch the first page of works that match [q] as a sort_title substring
     * (`GET /works?q=…&content_type=…&limit=…`) — no cursor, no paging. Used to gather
     * variant siblings for a single canonical work without walking the whole catalogue
     * (heyarr-core#470). [contentType] null omits the content_type filter. Throws on
     * a non-200.
     */
    fun queryWorks(q: String, contentType: String?, limit: Int = PAGE_LIMIT): List<Work> {
        val resp = http.get(worksUrl(baseUrl, q, contentType, limit), credential.asHeader())
        require(resp.status == 200) { "library: GET /works failed: HTTP ${resp.status}" }
        return WorksJson.parse(resp.body)
    }

    /** Fetch one work (`GET /works/{id}`); null on a 404, throws on any other non-200. */
    fun getWork(id: String): Work? {
        val resp = http.get(workUrl(baseUrl, id), credential.asHeader())
        if (resp.status == 404) return null
        require(resp.status == 200) { "library: GET /works/$id failed: HTTP ${resp.status}" }
        return WorksJson.parseOne(resp.body)
    }

    /**
     * Fetch one asset (`GET /assets/{id}`); null on a 404, throws on any other non-200.
     * The single-asset shape carries its `edition_id`, which is how an asset id is
     * resolved back to its work (asset → edition → work) — see [ItemResolver].
     */
    fun getAsset(id: String): WorkAsset? {
        val resp = http.get(assetUrl(baseUrl, id), credential.asHeader())
        if (resp.status == 404) return null
        require(resp.status == 200) { "library: GET /assets/$id failed: HTTP ${resp.status}" }
        return WorkDetailJson.parseAsset(resp.body)
    }

    /** Fetch one edition (`GET /editions/{id}`) — its `work_id` closes the asset→work join; null on 404. */
    fun getEdition(id: String): Edition? {
        val resp = http.get(editionUrl(baseUrl, id), credential.asHeader())
        if (resp.status == 404) return null
        require(resp.status == 200) { "library: GET /editions/$id failed: HTTP ${resp.status}" }
        return WorkDetailJson.parseEdition(resp.body)
    }

    companion object {
        /** The server's per-page maximum; asking for it minimises round trips for [page]. */
        const val PAGE_LIMIT = 200

        /**
         * Limit for the very first page: small enough to paint quickly, large enough to
         * fill a phone screen with a margin. Subsequent pages load at [PAGE_LIMIT].
         */
        const val FIRST_PAGE_LIMIT = 50

        /** A guard so a misbehaving cursor can never loop forever (200 × 50 = 10k works). */
        const val MAX_PAGES = 50

        /** Pure URL builder — unit-tested. The bare route, no paging, for callers that want it. */
        fun worksUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/api/v1/works"

        /** The browse embeds every list row is asked for (heyarr-core ADR-0075): the poster and the one playable file. */
        private val INCLUDE = URLEncoder.encode("artwork,primary_asset", "UTF-8")

        /**
         * Paged URL with an explicit [limit] and [sort] wire value — used by [page] with
         * `sort=recent` (ADR-0075). [INCLUDE] is already percent-encoded; no double-encoding.
         */
        fun worksUrl(baseUrl: String, cursor: String?, limit: Int, sort: String): String {
            val sb = StringBuilder(worksUrl(baseUrl))
                .append("?limit=").append(limit)
                .append("&sort=").append(sort)
                .append("&include=").append(INCLUDE)
            if (!cursor.isNullOrBlank()) sb.append("&cursor=").append(URLEncoder.encode(cursor, "UTF-8"))
            return sb.toString()
        }

        /**
         * Keyword-filter URL — `GET /works?q=<q>&content_type=<ct>&limit=<n>&include=…`.
         * [q] is a case-insensitive sort_title substring match (server-side); [contentType]
         * null omits the filter. Used by [queryWorks] to fetch variant siblings for one
         * canonical work without walking the whole catalogue (heyarr-core#470).
         * Unit-tested; both [q] and [contentType] are percent-encoded.
         */
        fun worksUrl(baseUrl: String, q: String, contentType: String?, limit: Int): String {
            val sb = StringBuilder(worksUrl(baseUrl))
                .append("?q=").append(URLEncoder.encode(q, "UTF-8"))
                .append("&limit=").append(limit)
                .append("&include=").append(INCLUDE)
            if (!contentType.isNullOrBlank()) sb.append("&content_type=").append(URLEncoder.encode(contentType, "UTF-8"))
            return sb.toString()
        }

        /** Pure URL builder for a single work — unit-tested. */
        fun workUrl(baseUrl: String, id: String): String =
            baseUrl.trimEnd('/') + "/api/v1/works/" + URLEncoder.encode(id, "UTF-8")

        /** `GET /assets/{id}` — a single asset (carries its `edition_id`). */
        fun assetUrl(baseUrl: String, id: String): String =
            baseUrl.trimEnd('/') + "/api/v1/assets/" + URLEncoder.encode(id, "UTF-8")

        /** `GET /editions/{id}` — a single edition (carries its `work_id`). */
        fun editionUrl(baseUrl: String, id: String): String =
            baseUrl.trimEnd('/') + "/api/v1/editions/" + URLEncoder.encode(id, "UTF-8")
    }
}
