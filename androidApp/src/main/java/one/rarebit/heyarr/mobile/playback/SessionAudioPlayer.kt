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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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
    private val capabilities: RenderCapabilityCache? = null,
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
            if (isPlaying) {
                // A resume after a long pause is a moment the phone can likely sign.
                renewCapabilities()
                startTicker()
            } else {
                stopTicker()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            renewCapabilities()
            val c = controller ?: return
            _state.update {
                it.copy(
                    index = c.currentMediaItemIndex,
                    positionMs = 0,
                    durationMs = c.duration.coerceAtLeast(0),
                    error = null,
                )
            }
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
            _state.update { it.copy(volume = c.volume.coerceIn(0f, 1f)) }
            while (pending.isNotEmpty()) pending.removeFirst()(c)
        }, MoreExecutors.directExecutor())
    }

    override fun playQueue(items: List<AudioItem>, startIndex: Int) {
        if (items.isEmpty()) return
        val start = startIndex.coerceIn(0, items.size - 1)
        val volume = _state.value.volume
        _state.value = AudioState(queue = items, index = start, volume = volume)
        // Now, while the phone is necessarily unlocked, mint the whole queue's render
        // capabilities, so its tracks still load once the screen locks (RenderCapabilityCache).
        // In parallel with the first load, which does not wait for it.
        capabilities?.let { caps -> scope.launch(Dispatchers.IO) { caps.prefetch(items) } }
        withController { c ->
            c.volume = volume
            c.setMediaItems(items.map { it.toMediaItem() }, start, 0L)
            c.prepare()
            c.play()
        }
    }

    // One renewal at a time: a track change during a renewal would repeat its request. A new
    // queue's own mint (playQueue) is never held back by this.
    private val renewing = AtomicBoolean(false)

    /**
     * Mint the queue again when its capabilities are running out (RenderCapabilityCache
     * .needsRenewal). Asked on every track change, on play/resume and once a minute while
     * playing; on a locked phone the mint fails quietly on the device, with no request sent,
     * and the first ask after an unlock succeeds.
     */
    private fun renewCapabilities() {
        val caps = capabilities ?: return
        val queue = _state.value.queue
        if (!caps.needsRenewal(queue) || !renewing.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                caps.prefetch(queue)
            } finally {
                renewing.set(false)
            }
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

    override fun setVolume(volume: Float) {
        val level = volume.coerceIn(0f, 1f)
        _state.update { it.copy(volume = level) }
        withController { it.volume = level }
    }

    override fun stop() {
        stopTicker()
        _state.value = AudioState(volume = _state.value.volume)
        controller?.let {
            it.stop()
            it.clearMediaItems()
        }
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            var sinceRenewalCheck = 0L
            while (isActive) {
                // A track can outlast the renewal window by itself (a single-file audiobook),
                // so renewal is also checked on a clock, not only on track changes.
                sinceRenewalCheck += TICK_MS
                if (sinceRenewalCheck >= RENEWAL_CHECK_MS) {
                    sinceRenewalCheck = 0
                    renewCapabilities()
                }
                controller?.let { c ->
                    _state.update {
                        it.copy(
                            positionMs = c.currentPosition.coerceAtLeast(0),
                            durationMs = c.duration.coerceAtLeast(0),
                        )
                    }
                }
                delay(TICK_MS)
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

    private companion object {
        const val TICK_MS = 500L

        /** How often a playing queue checks whether its capabilities need renewing. */
        const val RENEWAL_CHECK_MS = 60_000L
    }
}
