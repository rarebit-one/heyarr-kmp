package one.rarebit.heyarr.desktop.library

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.library.Variants
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.core.theme.MediaType
import java.net.URLEncoder

data class WorksListOptions(
    val contentType: String? = null,
    val query: String? = null,
    val includeEmbeds: Boolean = true,
)

/**
 * Browses heyarr's native library reach — `GET /api/v1/works` — authenticated with the
 * caller's [Credential] (here a `Bearer` token). Adapted from heyarr-mobile's
 * `library.LibraryClient`: the list is paged (`{items, next_cursor?}`, server max 500
 * per page); [listWorks] follows `next_cursor` to the end and returns the whole library
 * most-recently-touched first.
 */
class LibraryClient(private val http: HttpTransport, private val baseUrl: String, private val credential: Credential) {
    /** Fetch every work, recent first. Throws on a non-200 so the caller can surface the status. */
    fun listWorks(
        contentTypes: Set<MediaType>? = null,
        query: String? = null,
        includeEmbeds: Boolean = true,
    ): List<Work> {
        val all = ArrayList<Work>()
        val types = contentTypes?.flatMap(::serverTypes) ?: listOf(null)
        for (type in types) {
            var cursor: String? = null
            var pages = 0
            do {
                val resp = http.get(
                    worksUrl(baseUrl, cursor, WorksListOptions(type, query, includeEmbeds)),
                    credential.asHeader(),
                )
                require(resp.status == 200) { "library: GET /works failed: HTTP ${resp.status}" }
                all.addAll(WorksJson.parse(resp.body))
                cursor = WorksJson.nextCursor(resp.body)
                pages++
            } while (cursor != null && pages < MAX_PAGES)
            check(cursor == null) { "library: /works exceeded $MAX_PAGES pages" }
        }
        // Recent-first: RFC-3339 timestamps sort lexicographically, so a plain reverse
        // string sort is the order the Library shows (nulls last).
        return all.sortedWith(compareByDescending(nullsFirst()) { it.recency })
    }

    /** Fetch one work (`GET /works/{id}`); null on a 404, throws on any other non-200. */
    fun getWork(id: String): Work? {
        val resp = http.get(workUrl(baseUrl, id), credential.asHeader())
        if (resp.status == 404) return null
        require(resp.status == 200) { "library: GET /works/$id failed: HTTP ${resp.status}" }
        return WorksJson.parseOne(resp.body)
    }

    /** Resolve a small want list by id, reusing a full catalog when one is already loaded. */
    fun titlesFor(ids: List<String>, catalog: List<Work>? = null): Map<String, String> {
        val wanted = ids.toSet()
        val cached = catalog.orEmpty().filter { it.id in wanted }
        val known = cached.map { it.id }.toSet()
        val missing = wanted.filter { it !in known }
        val fetched = when {
            missing.isEmpty() -> emptyList()
            missing.size <= MAX_DIRECT_TITLE_READS -> missing.mapNotNull(::getWork)
            else -> listWorks(includeEmbeds = false)
        }
        return (cached + fetched).filter { it.id in wanted }.associate { it.id to it.title }
    }

    /** Find a series' scanner variants by title instead of walking every work. */
    fun seriesVariantsOf(work: Work, catalog: List<Work>? = null): List<Work> {
        if (MediaType.from(work.kind) != MediaType.SERIES) return emptyList()
        val key = work.sortTitle?.takeIf { it.isNotBlank() } ?: work.title
        val query = Variants.rawBase(key) ?: key
        val candidates = catalog?.takeIf { works -> works.any { it.id == work.id } }
            ?: listWorks(setOf(MediaType.SERIES), query, includeEmbeds = false)
        return Variants.group(candidates)[work.id].orEmpty()
    }

    /** Home needs only enough recent cards to fill its two rails. */
    fun recentWorks(count: Int = 24): List<Work> {
        val all = ArrayList<Work>()
        var cursor: String? = null
        var pages = 0
        var playable = 0
        do {
            val url = worksUrl(baseUrl, cursor, limit = 50) + "&sort=recent"
            val resp = http.get(url, credential.asHeader())
            require(resp.status == 200) { "library: GET /works failed: HTTP ${resp.status}" }
            val page = WorksJson.parse(resp.body)
            all.addAll(page)
            playable += page.count { it.playable != false && it.kind != "document" }
            cursor = WorksJson.nextCursor(resp.body)
            pages++
        } while (cursor != null && pages < MAX_PAGES && playable < count)
        return all
    }

    companion object {
        private const val MAX_DIRECT_TITLE_READS = 10

        /** The server's per-page maximum; asking for it minimises round trips. */
        const val PAGE_LIMIT = 500

        /** A guard so a misbehaving cursor can never loop forever. */
        const val MAX_PAGES = 50

        /** The bare route, no paging. */
        fun worksUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/api/v1/works"

        /** `GET /works?limit=500[&cursor=…]` — one page of the list. Older nodes clamp the limit. */
        fun worksUrl(
            baseUrl: String,
            cursor: String?,
            options: WorksListOptions = WorksListOptions(),
            limit: Int = PAGE_LIMIT,
        ): String {
            val base =
                worksUrl(baseUrl) + "?limit=" + limit +
                    if (options.includeEmbeds) "&include=artwork,primary_asset" else ""
            val filtered = options.contentType?.let { base + "&content_type=" + URLEncoder.encode(it, "UTF-8") } ?: base
            val searched = options.query?.let { filtered + "&q=" + URLEncoder.encode(it, "UTF-8") } ?: filtered
            return if (cursor.isNullOrBlank()) searched else searched + "&cursor=" + URLEncoder.encode(cursor, "UTF-8")
        }

        private fun serverTypes(type: MediaType): List<String> = when (type) {
            MediaType.FEED -> listOf("document")
            MediaType.UNKNOWN -> emptyList()
            else -> listOf(type.apiName ?: type.name.lowercase())
        }

        fun workUrl(baseUrl: String, id: String): String =
            baseUrl.trimEnd('/') + "/api/v1/works/" + URLEncoder.encode(id, "UTF-8")
    }
}
