package one.rarebit.heyarr.mobile.playback

/** One report from the player: the source position and what just happened. */
data class PlaybackProgress(val seconds: Double, val completed: Boolean, val event: Event) {
    /**
     * [CHECKPOINT] is no transition at all: where a paused item stands as the app goes to the
     * background, so its resume point is written then (heyarr-kmp#232). A consumption session
     * has nothing to send for it.
     */
    enum class Event { TICK, PAUSED, RESUMED, ENDED, LEFT, CHECKPOINT }
}
