package one.rarebit.heyarr.mobile.settings

import android.content.Context
import android.content.SharedPreferences
import one.rarebit.heyarr.mobile.playback.CopyChoiceStore
import one.rarebit.heyarr.mobile.playback.InMemoryCopyChoiceStore

/**
 * Persisted runtime overrides for [one.rarebit.heyarr.mobile.HeyarrConfig]. A null
 * value means "no override — use the build default". Kept as an interface so the
 * resolution logic can be exercised on the JVM with [InMemorySettingsStore].
 */
interface SettingsStore : CopyChoiceStore {
    var baseUrlOverride: String?
    var qualityProfileOverride: String?

    /** Appearance: the accent follows the media in focus (default on). */
    var adaptiveAccents: Boolean

    /** Appearance: no shimmer, no fades (default off). */
    var reduceMotion: Boolean

    /** Fetch cover art and synopses from keyless public sources where the node holds none (default on). */
    var externalMetadata: Boolean

    /**
     * Playback: which copy of a film to prefer on this phone — a `QualityPreference` key
     * (`best` / `fit` / `data_saver`); null is the default, "fit this screen" (heyarr-kmp#225).
     */
    var playbackPreference: String?
}

/** SharedPreferences-backed store (the phone). Values are plain origins, not secrets. */
class PrefsSettingsStore(context: Context) : SettingsStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var baseUrlOverride: String?
        get() = prefs.getString(KEY_BASE_URL, null)
        set(value) = put(KEY_BASE_URL, value)

    override var qualityProfileOverride: String?
        get() = prefs.getString(KEY_QUALITY_PROFILE, null)
        set(value) = put(KEY_QUALITY_PROFILE, value)

    override var adaptiveAccents: Boolean
        get() = prefs.getBoolean(KEY_ADAPTIVE_ACCENTS, true)
        set(value) = prefs.edit().putBoolean(KEY_ADAPTIVE_ACCENTS, value).apply()

    override var reduceMotion: Boolean
        get() = prefs.getBoolean(KEY_REDUCE_MOTION, false)
        set(value) = prefs.edit().putBoolean(KEY_REDUCE_MOTION, value).apply()

    override var externalMetadata: Boolean
        get() = prefs.getBoolean(KEY_EXTERNAL_METADATA, true)
        set(value) = prefs.edit().putBoolean(KEY_EXTERNAL_METADATA, value).apply()

    override var playbackPreference: String?
        get() = prefs.getString(KEY_PLAYBACK_PREFERENCE, null)
        set(value) = put(KEY_PLAYBACK_PREFERENCE, value)

    // The per-film copy choices live in their own file: one key per work, never read whole.
    private val copies: SharedPreferences =
        context.applicationContext.getSharedPreferences(COPIES_PREFS_NAME, Context.MODE_PRIVATE)

    override fun chosen(workId: String): String? = copies.getString("work.$workId", null)

    override fun choose(workId: String, assetId: String) {
        copies.edit().putString("work.$workId", assetId).apply()
    }

    private fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    companion object {
        const val PREFS_NAME = "heyarr-settings"
        const val COPIES_PREFS_NAME = "heyarr-copies"
        const val KEY_BASE_URL = "base_url"
        const val KEY_QUALITY_PROFILE = "quality_profile"
        const val KEY_ADAPTIVE_ACCENTS = "adaptive_accents"
        const val KEY_REDUCE_MOTION = "reduce_motion"
        const val KEY_EXTERNAL_METADATA = "external_metadata"
        const val KEY_PLAYBACK_PREFERENCE = "playback_preference"
    }
}

/** Non-persistent store for tests and previews. */
class InMemorySettingsStore(
    override var baseUrlOverride: String? = null,
    override var qualityProfileOverride: String? = null,
    override var adaptiveAccents: Boolean = true,
    override var reduceMotion: Boolean = false,
    override var externalMetadata: Boolean = true,
    override var playbackPreference: String? = null,
) : SettingsStore,
    CopyChoiceStore by InMemoryCopyChoiceStore()
