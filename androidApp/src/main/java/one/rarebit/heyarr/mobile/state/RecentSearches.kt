package one.rarebit.heyarr.mobile.state

import one.rarebit.heyarr.core.mcp.JsonWrite
import one.rarebit.heyarr.core.net.JsonArrays
import one.rarebit.heyarr.core.net.JsonScan
import java.io.File

/**
 * Recent search queries — the one piece of "history" this client keeps outside the
 * encrypted personal state, and it keeps it LOCALLY and says so in the UI: nothing
 * here is synced or claimed to be. Stored in the app's private files dir as a small
 * JSON array, newest first, capped. Ported from heyarr-desktop's `state/RecentSearches.kt`.
 */
class RecentSearches(private val file: File, private val max: Int = 8) {

    fun load(): List<String> {
        val text = runCatching { if (file.exists()) file.readText() else null }.getOrNull() ?: return emptyList()
        val arr = JsonScan.arrayOf(text, listOf("recent")) ?: return emptyList()
        return JsonArrays.parseStrings(arr).distinct().take(max)
    }

    fun push(query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return load()
        val next = (listOf(q) + load().filterNot { it.equals(q, ignoreCase = true) }).take(max)
        save(next)
        return next
    }

    fun clear() = save(emptyList())

    private fun save(list: List<String>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(JsonWrite.obj(mapOf("recent" to list)))
        }
    }
}
