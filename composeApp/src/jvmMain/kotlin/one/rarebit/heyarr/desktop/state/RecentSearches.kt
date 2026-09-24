package one.rarebit.heyarr.desktop.state

import one.rarebit.heyarr.core.mcp.JsonWrite
import one.rarebit.heyarr.core.net.JsonArrays
import one.rarebit.heyarr.core.net.JsonScan
import one.rarebit.heyarr.core.state.*
import java.io.File

/**
 * Recent search queries — the one piece of "history" this client keeps, and it keeps it
 * LOCALLY and says so in the UI: heyarr's personal state is controller-side and
 * unreachable, so nothing here is synced or claimed to be. Stored next to the config
 * file as a small JSON array, newest first, capped.
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

    companion object {
        fun defaultFile(): File = File(one.rarebit.heyarr.desktop.settings.FileSettingsStore.defaultConfigFile().parentFile, "recent-searches.json")
    }
}
