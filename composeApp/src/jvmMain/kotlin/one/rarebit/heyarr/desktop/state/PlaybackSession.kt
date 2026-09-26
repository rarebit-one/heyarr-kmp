package one.rarebit.heyarr.desktop.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import one.rarebit.heyarr.core.state.*
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.heyarr.HeyarrApi
import one.rarebit.heyarr.desktop.heyarr.PlaybackTarget
import one.rarebit.heyarr.desktop.library.Episode
import one.rarebit.heyarr.desktop.playback.EmbeddedPlayer
import one.rarebit.heyarr.desktop.ui.Route
import one.rarebit.heyarr.desktop.ui.isListening

/**
 * What is playing, app-wide. One mpv lives for the whole session (started by
 * `PlaybackHost` at the root), producing frames any screen may draw with `VideoSurface`.
 * So leaving the player screen does not stop playback: the now-playing bar shows the
 * same picture small, and music or a podcast keeps going while you browse. Closing
 * the bar is what stops it.
 */
class PlaybackSession {
    val player = EmbeddedPlayer()

    /** The item playing, null when idle. Same shape the player screen navigates with. */
    var current: Route.Player? by mutableStateOf(null)
        private set

    /** The work's playable episodes, for "next". */
    var queue: List<Episode> by mutableStateOf(emptyList())

    /** Audio queue belongs to the session, not the detail/reader screen. */
    var audioQueue: List<Route.Player> by mutableStateOf(emptyList())
        private set
    var loadedAssetId: String? = null

    fun queueAudio(items: List<Route.Player>, startIndex: Int = 0) {
        val playable = items.filter { it.typeHint.isListening() }
        val start = playable.getOrNull(startIndex) ?: return
        audioQueue = playable
        queue = emptyList()
        play(start)
    }

    fun previousAudio(): Route.Player? {
        val i = audioQueue.indexOfFirst { it.assetId == current?.assetId }
        return audioQueue.getOrNull(i - 1)
    }

    /** The player screen is showing; the bar hides itself then. */
    var onPlayerScreen: Boolean by mutableStateOf(false)

    var fullscreen: Boolean by mutableStateOf(false)

    /** Bumped by anything that should show the fullscreen transport again (a key, the pointer). */
    var controlsTick: Int by mutableStateOf(0)
        private set
    fun wakeControls() {
        controlsTick++
    }
    var popout: Boolean by mutableStateOf(false)
    var startError: String? by mutableStateOf(null)

    val active: Boolean get() = current != null
    val type: MediaType get() = current?.typeHint ?: MediaType.MOVIE

    /** Begin (or replace) playback. The host starts mpv when one is needed. */
    fun play(item: Route.Player) {
        val same = current?.assetId == item.assetId
        current = item
        startError = null
        if (same && player.isRunning) {
            player.play()
            return
        }
        if (!item.typeHint.isListening()) {
            audioQueue = emptyList()
        } else if (audioQueue.none { it.assetId == item.assetId }) {
            audioQueue = listOf(item)
        }
        // The host resolves/loads on IO, including changes between tracks.
        pendingStart = true
        refreshSubtitles() // adds now if the queue is already known + player up; else a no-op re-run does it
    }

    /** The subtitle-sidecar blob URLs for [item], from its episode in the loaded [queue] (empty when unknown yet). */
    private fun subtitleUrlsFor(item: Route.Player): List<String> = queue.firstOrNull { it.asset.id == item.assetId }
        ?.subtitles.orEmpty()
        .mapNotNull { it.blobHash }
        .map { HeyarrApi.blobUrl(baseUrl, it) }

    /**
     * Attach the current item's `.srt`/`.vtt` sidecars to the running player. Idempotent
     * and safe before the player is up or the queue has loaded, so the two producers of
     * that knowledge — the host that starts mpv and the screen that fetches the assets —
     * both call it and whichever completes last wins.
     */
    fun refreshSubtitles() {
        current?.let { player.addExternalSubtitles(subtitleUrlsFor(it)) }
    }

    fun next(): Route.Player? {
        val c = current ?: return null
        if (c.typeHint.isListening()) {
            val i = audioQueue.indexOfFirst { it.assetId == c.assetId }
            return if (i < 0) null else audioQueue.getOrNull(i + 1)
        }
        val eps = queue.filter { it.isPlayable }
        val i = eps.indexOfFirst { it.asset.id == c.assetId }
        if (i < 0) return null
        val n = eps.getOrNull(i + 1) ?: return null
        val blob = n.asset.blobHash ?: return null
        return c.copy(assetId = n.asset.id, blobHash = blob, subtitle = n.label)
    }

    fun stop() {
        player.close()
        current = null
        queue = emptyList()
        audioQueue = emptyList()
        loadedAssetId = null
        pendingStart = false
        fullscreen = false
        popout = false
    }

    /** Cleared by the host once it started mpv; set when a new item needs a start. */
    var pendingStart: Boolean by mutableStateOf(false)
    var baseUrl: String = ""
    var token: String = ""

    /**
     * Resolves the URL to play an item from. The default hands over the direct blob
     * (the behaviour before device-aware playback, and what tests and screenshots
     * want); [AppSession] replaces it with one that asks `POST /playback/plan`, so a
     * 4K/HEVC asset is transcoded down to a smoothly-decodable stream. It runs a
     * network call, so callers on the start path invoke it off the UI thread.
     */
    var resolvePlaybackTarget: (
        Route.Player,
    ) -> PlaybackTarget = { PlaybackTarget(HeyarrApi.blobUrl(baseUrl, it.blobHash)) }
    var accentHex: String = "#599C7B"

    fun title(item: Route.Player): String = item.title + (item.subtitle?.let { " — $it" } ?: "")
}
