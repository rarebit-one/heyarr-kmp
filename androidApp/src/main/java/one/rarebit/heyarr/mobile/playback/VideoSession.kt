package one.rarebit.heyarr.mobile.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import one.rarebit.heyarr.core.playback.Copy
import one.rarebit.heyarr.core.playback.SeekCoalescer
import one.rarebit.heyarr.mobile.library.WorkAsset

/** One episode the player can move on to after the current one (the work's other playable files). */
data class QueueEntry(
    val assetId: String,
    val blobHash: String,
    val title: String,
    val subtitle: String?,
    val mime: String?,
    val kind: String?,
    val thumbnailPath: String? = null,
    val sizeBytes: Long? = null,
)

/**
 * What is playing on THIS phone, app-wide — the mobile twin of the desktop's
 * `PlaybackSession`. One ExoPlayer lives for the session, owned here rather than by
 * the player screen, so leaving that screen does not stop playback: the persistent
 * now-playing bar keeps play/pause, ±10 s, seek and the title while you browse, and
 * coming back re-attaches the same player to a surface. Stop is what stops it.
 *
 * Streams through `HeyarrDataSource` (auth on every ranged read); the honest banners
 * of the old screen survive here as [State.issue] / [State.noFrame] / [State.error]
 * so the app can re-plan for a stream once (`PlaybackCoordinator.onIssue`).
 * Everything runs on the main thread (ExoPlayer's requirement); Compose reads the
 * state directly.
 *
 * A video holds a hardware decoder, which the system may take back from an app in the
 * background, so the shell's [onBackground] / [onForeground] release the player when the
 * app is sent away and rebuild it where it was on return (#231); a decoder failure is
 * re-prepared in place under [PlayerRecovery].
 */
@UnstableApi
class VideoSession(
    private val context: Context,
    private val okHttp: OkHttpClient,
    private val scope: CoroutineScope,
    /** The `Authorization` value in force right now (net/AuthHeaderSource); null before sign-in. */
    private val liveAuthorization: () -> String? = { null },
) {
    /** One selectable subtitle track: the Media3 group + index behind a human label. */
    data class TextTrack(
        val id: String,
        val label: String,
        val language: String?,
        val group: Tracks.Group,
        val trackIndex: Int,
    )

    /**
     * One selectable embedded audio track (a dub, a commentary, a stereo downmix): the Media3
     * group + index behind a language label, with its format for the picker's detail line.
     */
    data class AudioTrack(
        val id: String,
        val label: String,
        val language: String?,
        val channelCount: Int,
        val sampleMimeType: String?,
        val group: Tracks.Group,
        val trackIndex: Int,
    )

    data class State(
        val paused: Boolean = true,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val bufferedMs: Long = 0,
        val buffering: Boolean = false,
        val ended: Boolean = false,
        val audioTracks: List<AudioTrack> = emptyList(),
        val selectedAudioTrackId: String? = null,
        val textTracks: List<TextTrack> = emptyList(),
        val selectedTrackId: String? = null,
        val error: String? = null,
        /** [error] is the second decoder failure in a row: the film's other copy is worth offering (#231). */
        val offerOtherCopy: Boolean = false,
        val issue: String? = null,
        val noFrame: Boolean = false,
        val renderedFrame: Boolean = false,
    ) {
        val fraction: Float get() = if (durationMs >
            0
        ) {
            (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }

        /**
         * How far ahead the stream has cached, as a fraction of the runtime — the
         * lighter band behind the scrubber's played portion (mirrors heyarr-desktop's
         * `PlayerState.bufferedFraction`, which reads mpv's `demuxer-cache-time`).
         * Relative to the same [durationMs] basis as [fraction], so the two line up.
         */
        val bufferedFraction: Float get() = if (durationMs >
            0
        ) {
            (bufferedMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }
    }

    var current: NowPlaying? by mutableStateOf(null)
        private set
    var state: State by mutableStateOf(State())
        private set

    /** The work's other playable episodes, for "next". */
    var queue: List<QueueEntry> by mutableStateOf(emptyList())
    var fullscreen: Boolean by mutableStateOf(false)

    /** The live player, as Compose state so a surface re-attaches when a stream restarts. */
    var player: ExoPlayer? by mutableStateOf(null)
        private set
    private var trackSelector: DefaultTrackSelector? = null
    private var target: PlaybackTarget? = null

    /**
     * The source runtime the plan reported, for a stream whose own duration ExoPlayer
     * cannot know (no `Content-Length`, and the transcode is still being produced).
     * When set it PINS [State.durationMs], so the scrubber spans the whole film and a
     * fractional seek resolves against the real total instead of zero.
     */
    private var pinnedDurationMs = 0L
    private var streamStart = 0.0
    private var resumed = true
    private var startSeconds = 0.0
    private var ticker: Job? = null

    /** When a failed player is re-prepared and when its error is left up (#231). */
    private val recovery = PlayerRecovery()

    /** Rebuilds wait for the failing player's own callback to return (see [onPlayerError]). */
    private val main = Handler(Looper.getMainLooper())

    /**
     * Where a video stood when the app went to the background and its player was
     * released ([onBackground]); [onForeground] rebuilds it from here. Null while the
     * player is live, and for audio, which keeps playing.
     */
    private data class Suspended(val positionMs: Long, val play: Boolean, val selections: TrackSelectionParameters?)

    private var suspended: Suspended? = null

    private val seekCoalescer = SeekCoalescer(scope) { restartStream(it) }

    /** Where playback reached, for the consumption reporter — set by the shell. */
    var onProgress: (PlaybackProgress) -> Unit = {}

    /** A renderer type Media3 cannot decode — the shell may re-plan for a stream. */
    var onIssue: (PlaybackDiagnostics.Issue) -> Unit = {}

    /** The Version menu picked another copy of the film (heyarr-kmp#225) — the shell re-plans it here. */
    var onSwitchCopy: (Copy<WorkAsset>) -> Unit = {}

    val active: Boolean get() = current != null

    /** Begin (or replace) playback of [np]. The same asset on the same URL just resumes. */
    fun load(np: NowPlaying) {
        val same =
            current?.assetId != null && current?.assetId == np.assetId && target?.contentUrl == np.target.contentUrl
        current = np
        if (same && suspended != null) return // the app is in the background; onForeground resumes it
        if (same && player != null) {
            player?.play()
            return
        }
        // A new item or a new copy: nothing that failed before counts against it.
        recovery.reset()
        suspended = null
        val t = np.target
        pinnedDurationMs = t.sourceDurationSeconds?.takeIf { it > 0 }?.let { (it * 1000).toLong() } ?: 0L
        streamStart = if (t.restartSeekable && np.startSeconds > 0) np.startSeconds else t.streamStartSeconds
        startSeconds = np.startSeconds
        resumed = np.startSeconds <= 0 || t.restartSeekable
        val effective = if (t.restartSeekable && t.streamBaseUrl != null) t.atStreamStart(streamStart) else t
        start(effective, np.title)
    }

    /**
     * Build a fresh player for [t]. A rebuild in place ([restartAt]) hands over the old
     * player's track [selections], so the audio track and captions picked survive it, the
     * playing state to resume in ([play]), and the position to [hold] on the scrubber until
     * the new player has sought there.
     */
    private fun start(
        t: PlaybackTarget,
        title: String,
        selections: TrackSelectionParameters? = null,
        play: Boolean = true,
        hold: Long? = null,
    ) {
        val heldDurationMs = state.durationMs
        release()
        target = t
        // Show the target position and the pinned total straight away: a re-cut takes a
        // moment to produce its first frame, and a scrubber that snaps back to zero in
        // the meantime reads as "the seek failed".
        state = State(
            positionMs = hold ?: streamOffsetMs(),
            bufferedMs = hold ?: streamOffsetMs(),
            durationMs = if (pinnedDurationMs > 0 || hold == null) pinnedDurationMs else heldDurationMs,
        )
        val selector = DefaultTrackSelector(context)
        selections?.let { selector.setParameters(it) }
        trackSelector = selector
        val p = ExoPlayer.Builder(context)
            .setTrackSelector(selector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(HeyarrDataSource.factory(okHttp, t, liveAuthorization)))
            .setHandleAudioBecomingNoisy(true)
            // Declare this as MOVIE audio (not the default UNKNOWN). Left
            // undeclared, Nothing OS can't tell video playback from a music
            // player and drives the Glyph LEDs' music-visualisation over it
            // (issue #42); a video app like YouTube declares movie audio and is
            // left alone. handleAudioFocus so a call or another app ducks/pauses
            // us the way the audio queue already does.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */
                true,
            )
            .build()
        p.addListener(listener)
        val item = mediaItem(t, title)
        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = play
        player = p
        startTicker()
        if (t.isVideo) {
            scope.launch {
                delay(PlaybackDiagnostics.NO_FRAME_GRACE_MS)
                if (player === p && p.playbackState == Player.STATE_READY &&
                    !state.renderedFrame
                ) {
                    state = state.copy(noFrame = true)
                }
            }
        }
    }

    /** The media item for [t]: its URL, MIME hint and title, and its subtitle sidecars. */
    private fun mediaItem(t: PlaybackTarget, title: String): MediaItem =
        MediaItem.Builder().setUri(t.contentUrl).setMediaId(t.contentUrl)
            .apply { MediaMime.of(t.mimeType)?.let { setMimeType(it) } }
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .apply {
                // External subtitle sidecars (ingested .srt/.vtt). Media3 pulls these through
                // the same DefaultMediaSourceFactory data source as the video, so the auth
                // header rides along; the first is flagged DEFAULT so captions show.
                if (t.subtitles.isNotEmpty()) {
                    setSubtitleConfigurations(
                        t.subtitles.mapIndexed { i, s ->
                            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(s.url))
                                .apply {
                                    s.mimeType?.let { setMimeType(it) }
                                    s.language?.let { setLanguage(it) }
                                    s.label?.let { setLabel(it) }
                                    setSelectionFlags(
                                        if (i ==
                                            0
                                        ) {
                                            androidx.media3.common.C.SELECTION_FLAG_DEFAULT
                                        } else {
                                            0
                                        },
                                    )
                                }
                                .build()
                        },
                    )
                }
            }
            .build()

    /**
     * The scrubber total: the plan's source runtime when it pinned one, else whatever
     * the player worked out from the container. A stream has no length of its own, so
     * without the pin this is 0 — and a 0 total is what made every fractional seek
     * resolve to the start of the film.
     */
    private fun durationMs(p: ExoPlayer): Long =
        if (pinnedDurationMs > 0) pinnedDurationMs else p.duration.coerceAtLeast(0)

    /**
     * Position and buffer in SOURCE time. A re-cut stream's own clock restarts at zero
     * however far into the film it actually is, so the offset it was cut at is added
     * back — otherwise a seek to 30:00 would report 0:00 and drag the playhead back to
     * the left edge the moment it landed. Zero for a direct blob.
     */
    private fun streamOffsetMs(): Long =
        if (target?.origin == PlaybackTarget.Origin.STREAM) (streamStart * 1000).toLong() else 0L

    private fun positionMs(p: ExoPlayer): Long = streamOffsetMs() + p.currentPosition.coerceAtLeast(0)

    private fun bufferedMs(p: ExoPlayer): Long = streamOffsetMs() + p.bufferedPosition.coerceAtLeast(0)

    /** The source position: a stream's own clock starts at its offset. */
    fun sourceSeconds(): Double {
        val p = player ?: return suspended?.let { it.positionMs / 1000.0 } ?: streamStart
        val base = if (target?.origin == PlaybackTarget.Origin.STREAM) streamStart else 0.0
        return base + p.currentPosition.coerceAtLeast(0L) / 1000.0
    }

    private val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            val t = target ?: return
            val groups = tracks.groups.map { g ->
                val f = if (g.length > 0) g.getTrackFormat(0) else null
                PlaybackDiagnostics.TrackGroup(
                    type = g.type,
                    supported = g.isSupported,
                    sampleMime = f?.sampleMimeType,
                    channels =
                    f?.channelCount ?: 0,
                )
            }
            val issue = PlaybackDiagnostics.assess(groups, t)
            if (issue != null && state.issue == null) {
                state = state.copy(issue = issue.message)
                onIssue(issue)
            }
            val audio = collectAudio(tracks)
            val collected = ArrayList<TextTrack>()
            var n = 0
            tracks.groups.forEachIndexed { gi, g ->
                if (g.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
                for (ti in 0 until g.length) {
                    val f = g.getTrackFormat(ti)
                    collected.add(TextTrack("g${gi}t$ti", Subtitles.label(f.language, f.label, n), f.language, g, ti))
                    n++
                }
            }
            state =
                state.copy(
                    audioTracks = audio,
                    selectedAudioTrackId = audio.firstOrNull { it.group.isTrackSelected(it.trackIndex) }?.id,
                    textTracks = collected,
                    selectedTrackId = collected.firstOrNull {
                        it.group.isTrackSelected(it.trackIndex)
                    }?.id,
                )
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            val t = target ?: return
            val at = sourcePositionMs(p)
            when (val decision = recovery.onError(error.errorCodeName, at)) {
                // Not from inside the failing player's own callback: it is released by the
                // rebuild, so let this dispatch return first.
                PlayerRecovery.Decision.Retry -> {
                    val play = p.playWhenReady
                    main.post { if (player === p) restartAt(at, play) }
                }

                is PlayerRecovery.Decision.Show ->
                    state =
                        state.copy(
                            error = PlaybackDiagnostics.describeError(error.errorCodeName, error.message, t),
                            offerOtherCopy = decision.offerOtherCopy,
                            paused = true,
                            buffering = false,
                        )
            }
        }

        override fun onRenderedFirstFrame() {
            state = state.copy(renderedFrame = true, noFrame = false)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val p = player ?: return
            state =
                state.copy(
                    buffering = playbackState == Player.STATE_BUFFERING,
                    durationMs = durationMs(p),
                    ended =
                    playbackState == Player.STATE_ENDED,
                )
            if (playbackState == Player.STATE_READY && !resumed) {
                resumed = true
                p.seekTo((startSeconds * 1000).toLong())
            }
            if (playbackState ==
                Player.STATE_ENDED
            ) {
                onProgress(PlaybackProgress(sourceSeconds(), true, PlaybackProgress.Event.ENDED))
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            state = state.copy(paused = !isPlaying)
            val p = player ?: return
            if (p.playbackState == Player.STATE_ENDED) return
            onProgress(
                PlaybackProgress(
                    sourceSeconds(),
                    false,
                    if (isPlaying) PlaybackProgress.Event.RESUMED else PlaybackProgress.Event.PAUSED,
                ),
            )
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            var lastReport = 0L
            while (isActive) {
                player?.let { p ->
                    // Until a rebuilt or resumed player has made its first seek, its clock reads
                    // zero: keep the position it was started at instead of flicking back to 0.
                    // A total it does not know yet keeps the last one for the same reason.
                    state = if (resumed) {
                        state.copy(
                            positionMs = positionMs(p),
                            durationMs = durationMs(p).takeIf { it > 0 } ?: state.durationMs,
                            bufferedMs = bufferedMs(p),
                        )
                    } else {
                        state.copy(durationMs = durationMs(p).takeIf { it > 0 } ?: state.durationMs)
                    }
                    recovery.onPosition(state.positionMs, p.isPlaying)
                    val now = System.currentTimeMillis()
                    if (p.isPlaying && now - lastReport >= PROGRESS_TICK_MS) {
                        lastReport = now
                        onProgress(PlaybackProgress(sourceSeconds(), false, PlaybackProgress.Event.TICK))
                    }
                }
                delay(500)
            }
        }
    }

    // ── transport ────────────────────────────────────────────────────────────────

    fun togglePause() {
        if (retryOnPlay()) return
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.play()
        }
    }
    fun pause() {
        player?.pause()
    }
    fun play() {
        if (retryOnPlay()) return
        player?.play()
    }

    /**
     * Play on a failed player: re-prepare it where it stopped. Media3 leaves a player that
     * has failed in an error state where `play()` does nothing, which is what made Play look
     * dead after a decoder failure (#231). True when it did; false for an ordinary play.
     */
    private fun retryOnPlay(): Boolean {
        if (recovery.onPlay() == null) return false
        restartAt(player?.let(::sourcePositionMs) ?: state.positionMs, play = true)
        return true
    }

    /**
     * The activity stopped: the app is in the background, or the screen went off. A video
     * pauses and its player is released, so no hardware decoder is held, or driven, while
     * there is no surface to draw on. That is what failed on return in #231: the system took
     * the decoder back while heyarr sat behind other apps, and the player drove it again
     * the moment it came back. Audio is left alone: it holds no picture, and keeps playing
     * as before.
     *
     * Before this, a film kept playing its sound in the background with nothing to show for
     * it: no notification, no media session, no foreground service, so the system was free
     * to freeze it at any moment. That was never a feature; pausing is.
     */
    fun onBackground() {
        val p = player
        if (p == null || target?.isVideo != true) return
        val at = sourcePositionMs(p)
        // The error showing, or a pause, comes back paused; playing comes back playing.
        val play = p.playWhenReady && state.error == null
        if (p.isPlaying) onProgress(PlaybackProgress(at / 1000.0, false, PlaybackProgress.Event.PAUSED))
        suspended = Suspended(at, play, trackSelector?.parameters)
        release()
        state = state.copy(paused = true, buffering = false, positionMs = at)
    }

    /**
     * The activity is visible again: rebuild what [onBackground] released, at the same
     * position, on the same copy with the same audio track and captions, playing if it was.
     * The `PlayerView` picks the new player up and attaches it to its fresh surface.
     */
    fun onForeground() {
        val s = suspended ?: return
        suspended = null
        if (current == null) return
        // A fresh player is a fresh try at whatever error was up when the app went away.
        recovery.onPlay()
        restartAt(s.positionMs, s.play, s.selections)
    }

    /**
     * [onBackground] / [onForeground] on an activity's stop and start. A rotation stops and
     * restarts the activity too, but the film should not rebuffer for it, so a stop while
     * [changingConfigurations] is skipped.
     */
    fun lifecycleObserver(changingConfigurations: () -> Boolean): LifecycleEventObserver =
        LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> if (!changingConfigurations()) onBackground()
                Lifecycle.Event.ON_START -> onForeground()
                else -> Unit
            }
        }

    /**
     * Fail the live player as a decoder failure does (#231's acceptance; the instrumented
     * test): it drops to Media3's idle state, where `play()` does nothing, and [error] goes
     * through the same handling as a real one. A codec cannot be made to fail on cue.
     */
    @VisibleForTesting
    internal fun failForTest(error: PlaybackException) {
        val p = player ?: return
        p.stop()
        listener.onPlayerError(error)
    }

    /**
     * Rebuild the player for the item playing, at [atMs] (source time), keeping its copy,
     * its audio track and captions ([selections]) and playing if [play]. The Version menu's
     * path minus the re-plan: the same target, a fresh player. A restart-seekable stream is
     * re-requested at the position (as a seek does); anything else is the same URL again,
     * sought to the position once ready (as a start offset is).
     */
    private fun restartAt(
        atMs: Long,
        play: Boolean,
        selections: TrackSelectionParameters? = trackSelector?.parameters,
    ) {
        val np = current ?: return
        val t = np.target
        val base = t.streamBaseUrl
        val atSeconds = atMs.coerceAtLeast(0) / 1000.0
        if (t.restartSeekable && base != null) {
            streamStart = atSeconds
            resumed = true
            start(t.atStreamStart(streamStart).copy(streamBaseUrl = base), np.title, selections, play, atMs)
            return
        }
        // The seek lands in the player's own clock: a stream's starts at its offset.
        val offset = if (t.origin == PlaybackTarget.Origin.STREAM) streamStart else 0.0
        startSeconds = (atSeconds - offset).coerceAtLeast(0.0)
        resumed = startSeconds <= 0
        start(t, np.title, selections, play, atMs)
    }

    /**
     * Where [p] stands in source time. Before its first seek it has not got there yet, so
     * the position it was started at stands in for its clock.
     */
    private fun sourcePositionMs(p: ExoPlayer): Long = if (resumed) positionMs(p) else state.positionMs

    fun seekTo(positionMs: Long) {
        val t = target ?: return
        if (t.restartSeekable) {
            seekCoalescer.seekTo(positionMs / 1000.0)
            return
        }
        if (!t.seekable) return
        player?.seekTo(positionMs.coerceAtLeast(0))
    }

    /**
     * Seek to a fraction of the runtime. A no-op when nothing knows the runtime: with
     * a zero total every fraction is zero, so honouring it would silently restart the
     * film on any drag — refusing is the honest answer, and the UI shows no scrubber
     * to drag in that case anyway.
     */
    fun seekFraction(f: Float) {
        if (state.durationMs <= 0) return
        seekTo((f.coerceIn(0f, 1f) * state.durationMs).toLong())
    }

    fun seekBy(seconds: Double) {
        val t = target ?: return
        if (t.restartSeekable) {
            seekCoalescer.seekBy(sourceSeconds(), seconds)
            return
        }
        if (!t.seekable) return
        val p = player ?: return
        p.seekTo((p.currentPosition + (seconds * 1000).toLong()).coerceAtLeast(0))
    }

    /** A restart-seekable stream (#433): re-request the token URL at a new source offset. */
    private fun restartStream(atSeconds: Double) {
        val np = current ?: return
        val base = np.target.streamBaseUrl ?: return
        streamStart = atSeconds.coerceAtLeast(0.0)
        resumed = true
        start(np.target.atStreamStart(streamStart).copy(streamBaseUrl = base), np.title)
    }

    /**
     * The embedded audio tracks the device can decode. The ordinal counts every audio track,
     * supported or not, so an unnamed track's "Track N" matches its position in the file.
     */
    private fun collectAudio(tracks: Tracks): List<AudioTrack> {
        val audio = ArrayList<AudioTrack>()
        var ordinal = 0
        tracks.groups.forEachIndexed { gi, g ->
            if (g.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
            for (ti in 0 until g.length) {
                val index = ordinal++
                if (!g.isTrackSupported(ti)) continue
                val f = g.getTrackFormat(ti)
                audio.add(
                    AudioTrack(
                        id = "g${gi}t$ti",
                        label = Subtitles.label(f.language, f.label, index),
                        language = f.language,
                        channelCount = f.channelCount,
                        sampleMimeType = f.sampleMimeType,
                        group = g,
                        trackIndex = ti,
                    ),
                )
            }
        }
        return audio
    }

    /** Switch the audio language/track in place, leaving the text-track selection alone. */
    fun selectAudio(track: AudioTrack) {
        val selector = trackSelector ?: return
        selector.setParameters(
            selector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.trackIndex))
                .build(),
        )
        state = state.copy(selectedAudioTrackId = track.id)
    }

    /** Pick a text track (or turn subtitles off): an override + enabling the text renderer. */
    fun selectText(track: TextTrack?) {
        val selector = trackSelector ?: return
        val params = selector.buildUponParameters()
        if (track == null) {
            params.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            params.setTrackTypeDisabled(
                C.TRACK_TYPE_TEXT,
                false,
            ).setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.trackIndex))
        }
        selector.setParameters(params.build())
        state = state.copy(selectedTrackId = track?.id)
    }

    /** The episode after the current one, if the queue knows one. */
    fun next(): QueueEntry? {
        val c = current ?: return null
        val i = queue.indexOfFirst { it.assetId == c.assetId }
        if (i < 0) return null
        return queue.getOrNull(i + 1)
    }

    /** Stop and release: a stop that keeps the node's position, then nothing is playing. */
    fun stop() {
        if (player != null || suspended != null) {
            onProgress(PlaybackProgress(sourceSeconds(), false, PlaybackProgress.Event.LEFT))
        }
        suspended = null
        recovery.reset()
        release()
        current = null
        queue = emptyList()
        fullscreen = false
        pinnedDurationMs = 0L
        streamStart = 0.0
        state = State()
    }

    private fun release() {
        main.removeCallbacksAndMessages(null)
        seekCoalescer.cancel()
        ticker?.cancel()
        ticker = null
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
        trackSelector = null
        target = null
    }

    companion object {
        private const val PROGRESS_TICK_MS = 5_000L
    }
}
