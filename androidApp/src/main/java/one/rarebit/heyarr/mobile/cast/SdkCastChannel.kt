package one.rarebit.heyarr.mobile.cast

import android.content.Context
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastStatusCodes
import com.google.android.gms.cast.HlsSegmentFormat
import com.google.android.gms.cast.HlsVideoSegmentFormat
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.PendingResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import one.rarebit.heyarr.core.cast.ReceiverReport
import java.net.Inet4Address
import kotlin.coroutines.resume
import com.google.android.gms.common.api.Result as GmsResult

/**
 * [CastChannel] over the Cast SDK (heyarr-kmp#250): the framework's current [CastSession] and
 * its [RemoteMediaClient], with the session's lifecycle passed on to [events]. The SDK wants
 * the main thread for everything here, so [CastManager] runs on it; only the waits suspend.
 *
 * [castContext] is null until the framework has started, and stays null on a phone without
 * Google Play services: no session is ever live then, and the app shows no Cast button.
 *
 * Phone-gated: nothing here runs in CI. The manager it serves is tested over a script.
 */
@Suppress(
    "TooManyFunctions", // CastChannel's operations, one each, plus the SDK's listener.
    "ReturnCount", // No session, no client, no status: each is its own early answer.
)
class SdkCastChannel(private val context: Context) : CastChannel {
    /** What [CastManager] hears of the session. Its calls launch coroutines; they never block the SDK. */
    interface Events {
        fun live()

        fun suspended()

        fun ended(lost: Boolean)
    }

    var events: Events? = null

    var castContext: CastContext? = null
        set(value) {
            field?.sessionManager?.removeSessionManagerListener(listener, CastSession::class.java)
            field = value
            value?.sessionManager?.addSessionManagerListener(listener, CastSession::class.java)
        }

    private val session: CastSession? get() = castContext?.sessionManager?.currentCastSession
    private val client: RemoteMediaClient? get() = session?.remoteMediaClient

    /**
     * Set while this channel itself ends or starts a session ([relaunch], [rejoin]): the
     * session events that causes are not a user stopping the cast, and the manager, which
     * is waiting on the call, is told by its result instead.
     */
    private var quiet = false
    private var started: CompletableDeferred<Boolean>? = null
    private var ending: CompletableDeferred<Unit>? = null

    override val receiver: CastReceiver?
        get() = session?.castDevice?.let { device ->
            CastReceiver(device.deviceId, device.friendlyName, device.inetAddress as? Inet4Address)
        }

    override val live: Boolean get() = session?.isConnected == true

    override fun report(): ReceiverReport? {
        val client = client ?: return null
        val status = client.mediaStatus ?: return null
        return ReceiverReport(
            position = client.approximateStreamPosition / MILLIS,
            state = stateName(status.playerState),
            mediaSessionId = status.mediaInfo?.contentId?.let { loads[it] ?: FOREIGN_MEDIA },
            idleReason = idleName(status.idleReason),
        )
    }

    override suspend fun load(load: CastLoad): Int {
        val client = client ?: error("No Cast session")
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, load.title)
        }
        val id = nextLoad++
        // A presentation fetched straight from the node has one URL for every LOAD, so its
        // content id is made unique per LOAD, as a relay's fresh capability or `?start=` is.
        val contentId = if (load.hls) "${load.url}#load-$id" else load.url
        val info = MediaInfo.Builder(contentId)
            .setContentUrl(load.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(load.mime)
            .setMetadata(metadata)
            // The film's length, so the TV's own scrubber spans the film, not the buffered end.
            .apply { load.durationSeconds?.let { setStreamDuration((it * MILLIS).toLong()) } }
            .apply {
                if (load.hls) {
                    // fMP4 segments: without these the receiver takes them for MPEG-TS.
                    setHlsSegmentFormat(HlsSegmentFormat.FMP4)
                    setHlsVideoSegmentFormat(HlsVideoSegmentFormat.FMP4)
                }
            }
            .build()
        val request = MediaLoadRequestData.Builder().setMediaInfo(
            info,
        ).setAutoplay(load.autoplay).setCurrentTime((load.startSeconds * MILLIS).toLong()).build()
        // A wedged receiver never answers a LOAD; the manager's recovery needs to hear that it failed.
        loads[contentId] = id
        while (loads.size > KEPT_LOADS) loads.remove(loads.keys.first())
        val result = withTimeout(LOAD_TIMEOUT_MS) { client.load(request).await() }
        check(result.status.isSuccess) {
            "The TV did not take the film (${CastStatusCodes.getStatusCodeString(result.status.statusCode)})"
        }
        return id
    }

    /**
     * Which LOAD the receiver's media is. The SDK does not expose the receiver's media session
     * id, so each LOAD is numbered here and known by its URL, which is unique per LOAD (a fresh
     * relay capability, or a new `?start=`): media another sender loaded, or one from before
     * this process, reads as [FOREIGN_MEDIA] and is never taken for ours.
     */
    private val loads = LinkedHashMap<String, Int>()
    private var nextLoad = 1

    override suspend fun play() = command { it.play() }

    override suspend fun pause() = command { it.pause() }

    override suspend fun seek(seconds: Double) =
        command { it.seek(MediaSeekOptions.Builder().setPosition((seconds * MILLIS).toLong()).build()) }

    override suspend fun stop() = command { it.stop() }

    private suspend fun command(send: (RemoteMediaClient) -> PendingResult<RemoteMediaClient.MediaChannelResult>) {
        val client = client ?: error("No Cast session")
        val result = withTimeout(COMMAND_TIMEOUT_MS) { send(client).await() }
        check(result.status.isSuccess) {
            "The TV refused (${CastStatusCodes.getStatusCodeString(result.status.statusCode)})"
        }
    }

    override suspend fun relaunch(): Boolean {
        val sessions = castContext?.sessionManager ?: return false
        val device = session?.castDevice ?: return false
        quiet = true
        try {
            val ended = CompletableDeferred<Unit>().also { ending = it }
            // true: stop the receiver's media app, not just leave it (setStopReceiverApplicationWhenEndingSession).
            sessions.endCurrentSession(true)
            withTimeoutOrNull(END_TIMEOUT_MS) { ended.await() }
            return select(device.deviceId)
        } finally {
            ending = null
            quiet = false
        }
    }

    override suspend fun rejoin(receiver: CastReceiver): Boolean {
        quiet = true
        try {
            return select(receiver.id)
        } finally {
            quiet = false
        }
    }

    /**
     * Starts a session on the receiver [deviceId] the way the Cast button does: by selecting its
     * MediaRouter route, which the framework turns into a session (and, with relaunch-if-running,
     * a freshly launched media app). Discovery runs only while something asks for it, so it is
     * asked for here until the route turns up.
     */
    private suspend fun select(deviceId: String): Boolean {
        val selector = castContext?.mergedSelector ?: return false
        val router = MediaRouter.getInstance(context)
        val discovery = object : MediaRouter.Callback() {}
        router.addCallback(selector, discovery, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        try {
            val route = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                var found: MediaRouter.RouteInfo? = null
                while (found == null) {
                    found = router.routes.firstOrNull { CastDevice.getFromBundle(it.extras)?.deviceId == deviceId }
                    if (found == null) delay(DISCOVERY_POLL_MS)
                }
                found
            } ?: return false
            val start = CompletableDeferred<Boolean>().also { started = it }
            router.selectRoute(route)
            return withTimeoutOrNull(START_TIMEOUT_MS) { start.await() } ?: false
        } finally {
            started = null
            router.removeCallback(discovery)
        }
    }

    override fun volume(): Double? = session?.volume

    override fun setVolume(level: Double) {
        runCatching { session?.volume = level }
    }

    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            started?.complete(true)
            if (!quiet) events?.live()
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            started?.complete(false)
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            if (!quiet) events?.live()
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            if (!quiet) events?.ended(lost = true)
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            if (!quiet) events?.suspended()
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            ending?.complete(Unit)
            if (!quiet) events?.ended(lost = lostEnd(error))
        }

        override fun onSessionStarting(session: CastSession) = Unit

        override fun onSessionEnding(session: CastSession) = Unit

        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
    }

    companion object {
        private const val MILLIS = 1_000.0
        private const val FOREIGN_MEDIA = 0
        private const val KEPT_LOADS = 8
        private const val LOAD_TIMEOUT_MS = 30_000L
        private const val COMMAND_TIMEOUT_MS = 15_000L
        private const val END_TIMEOUT_MS = 10_000L
        private const val START_TIMEOUT_MS = 25_000L
        private const val DISCOVERY_TIMEOUT_MS = 15_000L
        private const val DISCOVERY_POLL_MS = 500L

        /**
         * Whether a session the SDK ended was lost rather than stopped: a network failure or a
         * timeout, after its own reconnection gave up. A user's "Stop casting" ends with
         * SUCCESS, and a receiver app quit from the TV (its Home button, another sender) with an
         * application error; both are stops, never revived. Pure.
         */
        fun lostEnd(error: Int): Boolean = error == CastStatusCodes.NETWORK_ERROR || error == CastStatusCodes.TIMEOUT ||
            error == CastStatusCodes.INTERRUPTED

        /** The Cast media-status names `:core`'s [one.rarebit.heyarr.core.cast.CastTimeline] reads. Pure. */
        fun stateName(playerState: Int): String = when (playerState) {
            MediaStatus.PLAYER_STATE_PLAYING -> "PLAYING"
            MediaStatus.PLAYER_STATE_PAUSED -> "PAUSED"
            MediaStatus.PLAYER_STATE_BUFFERING, MediaStatus.PLAYER_STATE_LOADING -> "BUFFERING"
            MediaStatus.PLAYER_STATE_IDLE -> "IDLE"
            else -> "UNKNOWN"
        }

        fun idleName(idleReason: Int): String? = when (idleReason) {
            MediaStatus.IDLE_REASON_FINISHED -> "FINISHED"
            MediaStatus.IDLE_REASON_CANCELED -> "CANCELLED"
            MediaStatus.IDLE_REASON_INTERRUPTED -> "INTERRUPTED"
            MediaStatus.IDLE_REASON_ERROR -> "ERROR"
            else -> null
        }

        private suspend fun <R : GmsResult> PendingResult<R>.await(): R = suspendCancellableCoroutine { cont ->
            setResultCallback { cont.resume(it) }
            cont.invokeOnCancellation { cancel() }
        }
    }
}
