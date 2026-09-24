package one.rarebit.heyarr.mobile.library

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpTransport
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
 * 200 per page) and served in `sort_title` order; [listWorks] follows `next_cursor`
 * to the end and returns the whole library **most-recently-touched first** — the
 * settings-free order the Library tab shows. Response parsing goes through
 * [WorksJson] so it is exercised on plain JVM in CI.
 */
class LibraryClient(
    private val http: HttpTransport,
    private val baseUrl: String,
    private val credential: Credential,
) {
    /** Fetch every work, recent first. Throws on a non-200 so the caller can surface the status. */
    fun listWorks(): List<Work> {
        val all = ArrayList<Work>()
        var cursor: String? = null
        var pages = 0
        do {
            val resp = http.get(worksUrl(baseUrl, cursor), credential.asHeader())
            require(resp.status == 200) { "library: GET /works failed: HTTP ${resp.status}" }
            all.addAll(WorksJson.parse(resp.body))
            cursor = WorksJson.nextCursor(resp.body)
            pages++
        } while (cursor != null && pages < MAX_PAGES)
        return Timestamps.recentFirst(all) { it.recency }
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
        /** The server's per-page maximum; asking for it minimises round trips. */
        const val PAGE_LIMIT = 200

        /** A guard so a misbehaving cursor can never loop forever (200 × 50 = 10k works). */
        const val MAX_PAGES = 50

        /** Pure URL builder — unit-tested. The bare route, no paging, for callers that want it. */
        fun worksUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/api/v1/works"

        /** The browse embeds every list row is asked for (heyarr-core ADR-0075): the poster and the one playable file. */
        private val INCLUDE = URLEncoder.encode("artwork,primary_asset", "UTF-8")

        /** `GET /works?limit=200&include=artwork,primary_asset[&cursor=…]` — one page of the list. */
        fun worksUrl(baseUrl: String, cursor: String?): String {
            val base = worksUrl(baseUrl) + "?limit=" + PAGE_LIMIT + "&include=" + INCLUDE
            return if (cursor.isNullOrBlank()) base else base + "&cursor=" + URLEncoder.encode(cursor, "UTF-8")
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
