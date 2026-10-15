package one.rarebit.heyarr.desktop.preview

import one.rarebit.heyarr.core.net.JsonScan
import java.net.URI
import java.net.URLDecoder

/** Keep screenshot responses scoped the same way as the works API. */
internal object FixtureWorksList {
    fun forUrl(url: String): String {
        val params = URI(url).rawQuery.orEmpty().split('&').mapNotNull { pair ->
            pair.split('=', limit = 2).takeIf { it.size == 2 }?.let {
                URLDecoder.decode(it[0], "UTF-8") to URLDecoder.decode(it[1], "UTF-8")
            }
        }.toMap()
        val works = if (params.containsKey("include")) {
            JsonScan.objectsOf(Fixtures.worksList(), listOf("items"))
        } else {
            Fixtures.works
        }
        val contentType = params["content_type"]
        val query = params["q"]
        val rows = works.filter { work ->
            (contentType == null || JsonScan.stringField(work, "content_type") == contentType) &&
                (query == null || JsonScan.stringField(work, "sort_title")?.contains(query, ignoreCase = true) == true)
        }
        return """{"items":[${rows.joinToString(",")}],"next_cursor":null}"""
    }
}
