package one.rarebit.heyarr.mobile.cast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.rarebit.heyarr.mobile.playback.NowPlaying
import one.rarebit.heyarr.mobile.playback.PlaybackCoordinator

/**
 * How a film moves between this phone's player and a Cast receiver (heyarr-kmp#250).
 *
 * Two doors, one rule: a film is never streamed here and on the TV at once (see [CastGate]).
 *  - A play pressed while a session is up arrives through the coordinator's [sink]: the
 *    coordinator has planned it and found its resume point, and [CastManager] casts it there.
 *  - A session that comes up while a film plays here takes that film ([takeOver]): the local
 *    player stops — not pauses, so its stream is closed — and the film continues on the TV
 *    from the position the player had reached.
 */
object CastHandoff {
    /** The film [np] as a cast, starting at [startSeconds] (film-absolute). */
    fun itemOf(np: NowPlaying, startSeconds: Double = np.startSeconds): CastItem? {
        val assetId = np.assetId ?: return null
        return CastItem(assetId, np.title, startSeconds.coerceAtLeast(0.0), np.resumeItem(), autoplay = true)
    }

    fun sink(cast: CastManager, scope: CoroutineScope, onFailure: (String) -> Unit): PlaybackCoordinator.CastSink =
        object : PlaybackCoordinator.CastSink {
            override fun route(np: NowPlaying): CastGate.Route =
                CastGate.route(np.target.isVideo, np.assetId, np.startPaused, cast.sessionLive, cast.castingAsset)

            override fun send(np: NowPlaying) {
                val item = itemOf(np) ?: return onFailure("This film has no asset to cast")
                scope.launch {
                    cast.play(item).onFailure { onFailure(it.message ?: "Couldn't cast it") }
                }
            }

            override val device: String? get() = cast.castingTo
        }

    /**
     * Whether a film playing here at [playing] moves to a session that just came up: a video,
     * with an asset to plan, that the person had not put back paused. Pure.
     */
    fun takesOver(playing: NowPlaying?): Boolean = playing != null && playing.target.isVideo && playing.assetId != null

    /**
     * Moves the film playing here to the session that just came up. [stopLocal] closes the
     * local player (and writes its resume point) before the receiver opens the stream, so
     * the node never repackages it twice at once.
     */
    suspend fun takeOver(
        cast: CastManager,
        playing: NowPlaying?,
        positionSeconds: Double,
        stopLocal: () -> Unit,
    ): Result<Unit>? {
        val item = playing?.takeIf(::takesOver)?.let { itemOf(it, positionSeconds) } ?: return null
        stopLocal()
        return cast.play(item)
    }
}
