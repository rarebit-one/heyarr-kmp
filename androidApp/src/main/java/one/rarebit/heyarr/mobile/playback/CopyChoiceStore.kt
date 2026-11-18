package one.rarebit.heyarr.mobile.playback

import one.rarebit.heyarr.core.playback.PlaybackPreference

/**
 * The copy of each film the person picked on THIS phone — from the player's Version menu or
 * the detail screen's copies list (heyarr-kmp#225) — so the next play of that work starts on
 * it. Device-local by design: which copy suits a phone is a fact about the phone, and a synced
 * per-user preference is a later issue. Keyed by work id; the value is an asset id, which
 * `Copies.pick` ignores once that copy is gone. The phone's `SettingsStore` is one.
 */
interface CopyChoiceStore {
    fun chosen(workId: String): String?
    fun choose(workId: String, assetId: String)
}

/** Non-persistent store for tests and previews. */
class InMemoryCopyChoiceStore : CopyChoiceStore {
    private val m = HashMap<String, String>()
    override fun chosen(workId: String) = m[workId]
    override fun choose(workId: String, assetId: String) {
        m[workId] = assetId
    }
}

/**
 * What the coordinator asks before it plays a film's copy: the effective playback preference
 * (the person's synced default under this phone's override, read per play so a change applies
 * to the next one) and the copy picked for each work on this phone.
 */
class CopyPreferences(
    val preference: () -> PlaybackPreference = { PlaybackPreference() },
    val choices: CopyChoiceStore = InMemoryCopyChoiceStore(),
)
