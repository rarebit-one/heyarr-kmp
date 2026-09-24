package one.rarebit.heyarr.mobile.reader

import android.content.Context
import one.rarebit.heyarr.core.net.JsonScan

/**
 * The reader's exact position per asset — a Readium Locator, as JSON — kept locally
 * so a book reopens where it was left. The node gets the coarser `page` through the
 * consumption session (the Continue row needs no more than that).
 */
interface ReadingPositionStore {
    fun locator(assetId: String): String?
    fun put(assetId: String, locatorJson: String)
}

class PrefsReadingPositionStore(context: Context) : ReadingPositionStore {
    private val prefs = context.applicationContext.getSharedPreferences("heyarr-reading", Context.MODE_PRIVATE)
    override fun locator(assetId: String): String? = prefs.getString("locator.$assetId", null)
    override fun put(assetId: String, locatorJson: String) {
        prefs.edit().putString("locator.$assetId", locatorJson).apply()
    }
}

class InMemoryReadingPositionStore : ReadingPositionStore {
    private val m = HashMap<String, String>()
    override fun locator(assetId: String) = m[assetId]
    override fun put(assetId: String, locatorJson: String) {
        m[assetId] = locatorJson
    }
}

/** What the node is told from a Locator: its `locations.position` (a 1-based page-ish index), when Readium computed one. Pure. */
object ReaderPosition {
    fun pageOf(locatorJson: String): Int? {
        val locations = JsonScan.objectAt(locatorJson, "locations") ?: return null
        return JsonScan.intField(locations, "position")?.takeIf { it > 0 }
    }
}
