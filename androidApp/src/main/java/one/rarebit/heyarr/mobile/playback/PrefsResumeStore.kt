package one.rarebit.heyarr.mobile.playback

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import one.rarebit.heyarr.core.playback.PlayerSnapshotStore
import one.rarebit.heyarr.core.playback.ResumePoint
import one.rarebit.heyarr.core.playback.ResumeStore

/**
 * This phone's resume points (heyarr-kmp#232), one SharedPreferences key per asset holding
 * the point's JSON. Device-local by design for now: the position belongs in personal state
 * once that syncs, which is a later issue.
 *
 * SharedPreferences rather than a file of our own for one reason: Android waits for its
 * pending `apply()` writes before an activity's stop completes (QueuedWork), so the point
 * written as the app goes to the background is on disk before the process can be killed
 * there. The points are held in memory too, so a screen reading them on every composition
 * parses nothing; [revision] is Compose state, so the Home row and the detail screen redraw
 * when a point is written. Written on the main thread, as the player reports.
 */
class PrefsResumeStore(context: Context) : ResumeStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val points: MutableMap<String, ResumePoint> by lazy {
        prefs.all.entries.mapNotNull { (key, value) ->
            if (!key.startsWith(PREFIX)) return@mapNotNull null
            (value as? String)?.let(ResumePoint::fromJson)?.let { it.assetId to it }
        }.toMap(HashMap())
    }

    /** Bumped on every write; reading it (as [get] and [all] do) subscribes a composition. */
    private val revision = mutableIntStateOf(0)

    override fun get(assetId: String): ResumePoint? {
        revision.intValue
        return points[assetId]
    }

    override fun put(point: ResumePoint) {
        points[point.assetId] = point
        prefs.edit().putString(PREFIX + point.assetId, point.toJson()).apply()
        revision.intValue++
    }

    override fun remove(assetId: String) {
        if (points.remove(assetId) == null) return
        prefs.edit().remove(PREFIX + assetId).apply()
        revision.intValue++
    }

    override fun all(): List<ResumePoint> {
        revision.intValue
        return points.values.toList()
    }

    companion object {
        const val PREFS_NAME = "heyarr-resume"
        private const val PREFIX = "asset."
    }
}

/**
 * The one item the in-app player held when the app last left it (heyarr-kmp#232), for the
 * restore after process death. Its own file, one key; SharedPreferences for the same reason as
 * [PrefsResumeStore]: what the background writes is on disk before the stop completes.
 */
class PrefsPlayerSnapshotStore(context: Context) : PlayerSnapshotStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): ResumePoint? = prefs.getString(KEY, null)?.let(ResumePoint::fromJson)

    override fun write(point: ResumePoint) {
        prefs.edit().putString(KEY, point.toJson()).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        const val PREFS_NAME = "heyarr-player"
        private const val KEY = "video"
    }
}
