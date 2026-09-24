package one.rarebit.heyarr.mobile.personalstate

import android.content.Context

/**
 * This device's own knowledge of WHICH space holds WHICH CRDT — the client-side
 * mapping heyarr-core's device gateway keeps as `SpaceRoles`, never on the wire (a
 * space's kind is structural; which CRDT it holds is the device's to decide). The
 * gateway serves every space it can decrypt AS A PLAYLIST unless it is the
 * configured starred or history space; this registry keeps the same convention so
 * the phone and the Mac gateway agree — a space the phone records here as its
 * starred/history/reading space is exactly the id an operator points the gateway's
 * `--starred-space` / `--history-space` at.
 *
 * It also holds an optional local display name per playlist space (a playlist's
 * name is not yet a CRDT field — `playlistName` on the gateway is `"Playlist <id>"`,
 * the interop-parity default here).
 */
internal interface SpaceRegistry {
    fun starredSpace(): String?
    fun setStarredSpace(id: String)
    fun historySpace(): String?
    fun setHistorySpace(id: String)
    fun readingSpace(): String?
    fun setReadingSpace(id: String)

    fun playlistName(id: String): String?
    fun setPlaylistName(id: String, name: String)
    fun clearPlaylistName(id: String)

    /** The ids reserved for non-playlist roles, so a playlist listing can exclude them. */
    fun roleSpaceIds(): Set<String> = listOfNotNull(starredSpace(), historySpace(), readingSpace()).toSet()

    /** The display name for a playlist space — a local rename, else the gateway-parity `"Playlist <id>"`. */
    fun displayName(id: String): String = playlistName(id) ?: "Playlist $id"

    companion object {
        const val PREFS = "heyarr-spaces"
    }
}

/** In-memory registry for tests and previews. */
internal class InMemorySpaceRegistry : SpaceRegistry {
    private var starred: String? = null
    private var history: String? = null
    private var reading: String? = null
    private val names = HashMap<String, String>()

    override fun starredSpace(): String? = starred
    override fun setStarredSpace(id: String) {
        starred = id
    }
    override fun historySpace(): String? = history
    override fun setHistorySpace(id: String) {
        history = id
    }
    override fun readingSpace(): String? = reading
    override fun setReadingSpace(id: String) {
        reading = id
    }
    override fun playlistName(id: String): String? = names[id]
    override fun setPlaylistName(id: String, name: String) {
        names[id] = name
    }
    override fun clearPlaylistName(id: String) {
        names.remove(id)
    }
}

/** SharedPreferences-backed registry (the real device store). */
internal class PrefsSpaceRegistry(context: Context) : SpaceRegistry {
    private val prefs = context.getSharedPreferences(SpaceRegistry.PREFS, Context.MODE_PRIVATE)

    override fun starredSpace(): String? = prefs.getString(KEY_STARRED, null)
    override fun setStarredSpace(id: String) = prefs.edit().putString(KEY_STARRED, id).apply()
    override fun historySpace(): String? = prefs.getString(KEY_HISTORY, null)
    override fun setHistorySpace(id: String) = prefs.edit().putString(KEY_HISTORY, id).apply()
    override fun readingSpace(): String? = prefs.getString(KEY_READING, null)
    override fun setReadingSpace(id: String) = prefs.edit().putString(KEY_READING, id).apply()

    override fun playlistName(id: String): String? = prefs.getString(nameKey(id), null)
    override fun setPlaylistName(id: String, name: String) = prefs.edit().putString(nameKey(id), name).apply()
    override fun clearPlaylistName(id: String) = prefs.edit().remove(nameKey(id)).apply()

    private companion object {
        const val KEY_STARRED = "role.starred"
        const val KEY_HISTORY = "role.history"
        const val KEY_READING = "role.reading"
        fun nameKey(id: String) = "name.$id"
    }
}
