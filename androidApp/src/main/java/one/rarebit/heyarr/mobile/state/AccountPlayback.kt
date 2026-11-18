package one.rarebit.heyarr.mobile.state

import one.rarebit.heyarr.core.playback.PlaybackPreference

/**
 * The person's synced playback default as Settings shows it (heyarr-core#887). [enabled] is
 * false without personal state (a guest, or a device with no key): the default is encrypted
 * personal state, and nothing here fakes it.
 */
data class AccountPlayback(
    val preference: PlaybackPreference? = null,
    val enabled: Boolean = false,
    val onChange: (PlaybackPreference) -> Unit = {},
)
