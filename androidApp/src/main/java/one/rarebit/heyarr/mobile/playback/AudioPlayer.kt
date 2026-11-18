package one.rarebit.heyarr.mobile.playback

import kotlinx.coroutines.flow.StateFlow

/**
 * One track in the audio queue: what to fetch (the authenticated blob route — the
 * shared client stamps the credential, so the URL is enough) and what to show.
 */
data class AudioItem(
    val assetId: String,
    val workId: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val artworkUrl: String? = null,
    val contentUrl: String,
    val mime: String? = null,
)

/** What the mini-player and the now-playing screen draw. */
data class AudioState(
    val queue: List<AudioItem> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Float = 1f,
    val error: String? = null,
) {
    val item: AudioItem? get() = queue.getOrNull(index)
    val hasNext: Boolean get() = index in 0 until queue.size - 1
    val fraction: Float get() = if (durationMs >
        0
    ) {
        (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()
    } else {
        0f
    }
}

/**
 * The audio seam: a queue that outlives any one screen, so music keeps playing while
 * the user browses. Implemented by [SessionAudioPlayer] over a MediaController bound to
 * [PlaybackService] (notification controls, background survival); the screens only
 * ever see this interface.
 */
@Suppress("TooManyFunctions") // the transport, plus putting a queue back after process death
interface AudioPlayer {
    val state: StateFlow<AudioState>
    fun playQueue(items: List<AudioItem>, startIndex: Int = 0)
    fun play()
    fun pause()
    fun togglePlayPause()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun skipTo(index: Int)
    fun setVolume(volume: Float)
    fun stop()

    /**
     * Put back the queue a process that died left (heyarr-kmp#232): [items], on [startIndex] at
     * [positionMs], paused, waiting in the now-playing bar.
     */
    fun restoreQueue(items: List<AudioItem>, startIndex: Int, positionMs: Long) = Unit

    /** Write down where the queue stands now: the app is going to the background (#232). */
    fun checkpoint() = Unit
}
