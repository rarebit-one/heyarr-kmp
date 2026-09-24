package one.rarebit.heyarr.mobile.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The [AudioPlayer] seam over a [MediaController] bound to [PlaybackService]. Commands
 * issued before the controller connects are queued and replayed on connection, so the
 * first tap on an album never lands on nothing. The queue list is kept here (the
 * controller only knows MediaItems), and the position ticks while playing.
 */
@UnstableApi
class SessionAudioPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
) : AudioPlayer {

    private val _state = MutableStateFlow(AudioState())
    override val state: StateFlow<AudioState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()
    private var ticker: Job? = null
    private var connecting = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(playing = isPlaying) }
            if (isPlaying) startTicker() else stopTicker()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val c = controller ?: return
            _state.update { it.copy(index = c.currentMediaItemIndex, positionMs = 0, durationMs = c.duration.coerceAtLeast(0), error = null) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val c = controller ?: return
            _state.update { it.copy(durationMs = c.duration.coerceAtLeast(0)) }
            if (playbackState == Player.STATE_ENDED) _state.update { it.copy(playing = false) }
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(playing = false, error = error.errorCodeName) }
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let {
            block(it)
            return
        }
        pending.addLast(block)
        if (connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull()
            connecting = false
            if (c == null) {
                pending.clear()
                _state.update { it.copy(error = "could not reach the playback service") }
                return@addListener
            }
            c.addListener(listener)
            controller = c
            while (pending.isNotEmpty()) pending.removeFirst()(c)
        }, MoreExecutors.directExecutor())
    }

    override fun playQueue(items: List<AudioItem>, startIndex: Int) {
        if (items.isEmpty()) return
        val start = startIndex.coerceIn(0, items.size - 1)
        _state.value = AudioState(queue = items, index = start)
        withController { c ->
            c.setMediaItems(items.map { it.toMediaItem() }, start, 0L)
            c.prepare()
            c.play()
        }
    }

    override fun play() = withController { it.play() }
    override fun pause() = withController { it.pause() }
    override fun togglePlayPause() = withController { if (it.isPlaying) it.pause() else it.play() }
    override fun next() = withController { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    override fun previous() = withController {
        if (it.currentPosition > 3_000 || !it.hasPreviousMediaItem()) it.seekTo(0) else it.seekToPreviousMediaItem()
    }
    override fun seekTo(positionMs: Long) {
        _state.update { it.copy(positionMs = positionMs.coerceAtLeast(0)) }
        withController { it.seekTo(positionMs.coerceAtLeast(0)) }
    }
    override fun skipTo(index: Int) {
        if (index !in _state.value.queue.indices) return
        withController {
            it.seekTo(index, 0L)
            it.play()
        }
    }

    override fun stop() {
        stopTicker()
        _state.value = AudioState()
        controller?.let {
            it.stop()
            it.clearMediaItems()
        }
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (isActive) {
                controller?.let { c -> _state.update { it.copy(positionMs = c.currentPosition.coerceAtLeast(0), durationMs = c.duration.coerceAtLeast(0)) } }
                delay(500)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun AudioItem.toMediaItem(): MediaItem {
        val builder = MediaItem.Builder().setMediaId(assetId).setUri(Uri.parse(contentUrl))
        MediaMime.of(mime)?.let { builder.setMimeType(it) }
        builder.setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
                .setArtworkUri(artworkUrl?.let { Uri.parse(it) }).build(),
        )
        return builder.build()
    }
}
