package one.rarebit.heyarr.mobile.playback

import android.content.Context
import android.content.SharedPreferences
import one.rarebit.heyarr.core.mcp.JsonWrite
import one.rarebit.heyarr.core.net.JsonScan

/**
 * The music queue as it stood when the app last left it (heyarr-kmp#232): every track, the one
 * playing and where in it. The queue lives in `PlaybackService`, and after the process dies
 * nothing of it is left; this is what the next process puts back, paused, in the now-playing
 * bar. Cleared by Stop. Device-local, like the film's snapshot.
 */
data class AudioQueueSnapshot(
    val items: List<AudioItem>,
    val index: Int,
    val positionMs: Long,
    val updatedAtMs: Long,
) {
    fun toJson(): String = JsonWrite.obj(
        mapOf(
            "index" to index,
            "position_ms" to positionMs,
            "updated_at_ms" to updatedAtMs,
            "items" to items.map {
                mapOf(
                    "asset_id" to it.assetId,
                    "work_id" to it.workId,
                    "title" to it.title,
                    "artist" to it.artist,
                    "album" to it.album,
                    "artwork_url" to it.artworkUrl,
                    "content_url" to it.contentUrl,
                    "mime" to it.mime,
                )
            },
        ),
    )

    companion object {
        /** The [toJson] form back; null for anything that is not one, or an empty queue. */
        fun fromJson(json: String): AudioQueueSnapshot? {
            val items = JsonScan.objectsOf(json, listOf("items")).mapNotNull(::itemOf)
            // The track it was on must be one of the queue's.
            val index = JsonScan.intField(json, "index")?.takeIf { it in items.indices }
            val updated = JsonScan.longField(json, "updated_at_ms")
            if (index == null || updated == null) return null
            return AudioQueueSnapshot(items, index, JsonScan.longField(json, "position_ms") ?: 0L, updated)
        }

        private fun itemOf(obj: String): AudioItem? {
            val assetId = JsonScan.stringField(obj, "asset_id")
            val url = JsonScan.stringField(obj, "content_url")
            if (assetId == null || url == null) return null
            return AudioItem(
                assetId = assetId,
                workId = JsonScan.stringField(obj, "work_id") ?: "",
                title = JsonScan.stringField(obj, "title") ?: "",
                artist = JsonScan.stringField(obj, "artist"),
                album = JsonScan.stringField(obj, "album"),
                artworkUrl = JsonScan.stringField(obj, "artwork_url"),
                contentUrl = url,
                mime = JsonScan.stringField(obj, "mime"),
            )
        }
    }
}

/** Where the queue's snapshot is kept: the phone's [PrefsAudioQueueStore], or memory in tests. */
interface AudioQueueStore {
    fun read(): AudioQueueSnapshot?
    fun write(snapshot: AudioQueueSnapshot)
    fun clear()
}

class InMemoryAudioQueueStore : AudioQueueStore {
    private var snapshot: AudioQueueSnapshot? = null
    override fun read() = snapshot
    override fun write(snapshot: AudioQueueSnapshot) {
        this.snapshot = snapshot
    }
    override fun clear() {
        snapshot = null
    }
}

/**
 * SharedPreferences, one key, for the same reason as the film's snapshot: what is written as the
 * app goes to the background is on disk before the activity's stop completes.
 */
class PrefsAudioQueueStore(context: Context) : AudioQueueStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PrefsPlayerSnapshotStore.PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): AudioQueueSnapshot? = prefs.getString(KEY, null)?.let(AudioQueueSnapshot::fromJson)

    override fun write(snapshot: AudioQueueSnapshot) {
        prefs.edit().putString(KEY, snapshot.toJson()).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "audio"
    }
}
