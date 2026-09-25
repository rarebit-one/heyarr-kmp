package one.rarebit.heyarr.mobile.catalog

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.WorksJson
import one.rarebit.heyarr.mobile.net.Timestamps
import java.net.URLEncoder

/** One page of the catalog: the rows and, when there are more, the opaque cursor. */
data class WorksPage(val items: List<Work>, val nextCursor: String?)

/**
 * The browse reads over `GET /api/v1/works` (heyarr-core ADR-0075): one page at a time,
 * by content type, in title or recent-first order, with the poster and playable-file
 * embeds asked for. Against an older node that ignores `sort=` and `include=`, the
 * rows come back plain and in title order — so [recent] always re-sorts client-side
 * (idempotent when the node already did), and the embeds are simply absent.
 */
class CatalogClient(private val http: HttpTransport, private val baseUrl: String, private val credential: Credential) {
    enum class Sort(val wire: String) { TITLE("title"), RECENT("recent") }

    /** One page. Throws on a non-200 so the caller can surface the status. */
    fun page(
        contentType: String?,
        sort: Sort = Sort.TITLE,
        limit: Int = DEFAULT_LIMIT,
        cursor: String? = null,
        artist: String? = null,
        author: String? = null,
    ): WorksPage {
        val resp = http.get(pageUrl(baseUrl, contentType, sort, limit, cursor, artist, author), credential.asHeader())
        require(resp.status == 200) { "catalog: GET /works failed: HTTP ${resp.status}" }
        return WorksPage(items = WorksJson.parse(resp.body), nextCursor = WorksJson.nextCursor(resp.body))
    }

    /**
     * The newest [limit] works of [contentType] (all types when null), newest first —
     * re-sorted here whatever order the node used.
     */
    fun recent(contentType: String?, limit: Int = DEFAULT_LIMIT): List<Work> =
        Timestamps.recentFirst(page(contentType, Sort.RECENT, limit).items) { it.createdAt ?: it.updatedAt }.take(limit)

    companion object {
        const val DEFAULT_LIMIT = 24
        const val INCLUDE = "artwork,primary_asset"

        /** Pure, unit-tested: the page URL. Parameter order is fixed so a URL is a stable test subject. */
        fun pageUrl(
            baseUrl: String,
            contentType: String?,
            sort: Sort,
            limit: Int,
            cursor: String?,
            artist: String? = null,
            author: String? = null,
        ): String {
            val sb = StringBuilder(baseUrl.trimEnd('/')).append("/api/v1/works?limit=").append(limit)
            contentType?.takeIf { it.isNotBlank() }?.let { sb.append("&content_type=").append(enc(it)) }
            artist?.takeIf { it.isNotBlank() }?.let { sb.append("&artist=").append(enc(it)) }
            author?.takeIf { it.isNotBlank() }?.let { sb.append("&author=").append(enc(it)) }
            sb.append("&sort=").append(sort.wire)
            sb.append("&include=").append(enc(INCLUDE))
            cursor?.takeIf { it.isNotBlank() }?.let { sb.append("&cursor=").append(enc(it)) }
            return sb.toString()
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    }
}
