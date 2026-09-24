package one.rarebit.heyarr.desktop.books

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.desktop.catalog.Grouping
import one.rarebit.heyarr.desktop.catalog.GroupingJson
import one.rarebit.heyarr.desktop.library.Work
import one.rarebit.heyarr.desktop.library.WorksJson
import java.net.URLEncoder

/**
 * The BOOKS browse reach, twin of [one.rarebit.heyarr.desktop.music.MusicClient]:
 *
 *  - [listAuthors] `GET /authors`                       — book works grouped by author (ADR-0075)
 *  - [listBooks]   `GET /works?content_type=book&author=…` — one author's book works
 *
 * The book file itself is resolved with the existing
 * [one.rarebit.heyarr.desktop.library.WorkDetailClient] (`GET /works/{id}` inlines
 * `primary_asset`), then downloaded + opened — see the Books section UI.
 */
class BooksClient(
    private val http: HttpTransport,
    private val baseUrl: String,
    private val credential: Credential,
) {
    /** Every book author, name-ordered as the server pages them. */
    fun listAuthors(): List<Grouping> = pageAll(
        url = { cursor -> authorsUrl(baseUrl, cursor) },
        parse = GroupingJson::parse,
        cursor = GroupingJson::nextCursor,
        what = "GET /authors",
    )

    /** One author's book works. Older nodes ignore `author=`, so re-filter defensively. */
    fun listBooks(author: String): List<Work> = pageAll(
        url = { cursor -> booksUrl(baseUrl, author, cursor) },
        parse = WorksJson::parse,
        cursor = WorksJson::nextCursor,
        what = "GET /works?author",
    ).filter { it.author == null || it.author == author }

    private fun <T> pageAll(
        url: (String?) -> String,
        parse: (String) -> List<T>,
        cursor: (String) -> String?,
        what: String,
    ): List<T> {
        val all = ArrayList<T>()
        var next: String? = null
        var pages = 0
        do {
            val resp = http.get(url(next), credential.asHeader())
            require(resp.status == 200) { "books: $what failed: HTTP ${resp.status}" }
            all.addAll(parse(resp.body))
            next = cursor(resp.body)
            pages++
        } while (next != null && pages < MAX_PAGES)
        return all
    }

    companion object {
        const val PAGE_LIMIT = 200
        const val MAX_PAGES = 50

        private val INCLUDE = URLEncoder.encode("artwork,primary_asset", "UTF-8")

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun page(base: String, cursor: String?) =
            if (cursor.isNullOrBlank()) base else "$base&cursor=${enc(cursor)}"

        fun authorsUrl(baseUrl: String, cursor: String? = null): String =
            page(baseUrl.trimEnd('/') + "/api/v1/authors?limit=" + PAGE_LIMIT, cursor)

        fun booksUrl(baseUrl: String, author: String, cursor: String? = null): String = page(
            baseUrl.trimEnd('/') + "/api/v1/works?limit=" + PAGE_LIMIT +
                "&content_type=book&author=" + enc(author) + "&sort=title&include=" + INCLUDE,
            cursor,
        )
    }
}
