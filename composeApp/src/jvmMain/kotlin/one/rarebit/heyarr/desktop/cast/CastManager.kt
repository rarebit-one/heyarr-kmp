package one.rarebit.heyarr.desktop.cast

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import one.rarebit.heyarr.desktop.heyarr.HeyarrApi
import one.rarebit.heyarr.desktop.heyarr.PlaybackTarget
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/** Session-owned Cast transport and media doorway; the API itself is intentionally short-lived. */
@Suppress("TooGenericExceptionCaught") // Surface any planner, TLS, relay, or receiver failure to the user.
internal class CastManager(
    private val localMediaUrl: (String) -> String,
    private val connect: (CastDevice) -> CastChannel = ::CastConnection,
    private val dispatchReset: (() -> Unit) -> Unit = ::resetInBackground,
) : AutoCloseable {
    var devices by mutableStateOf<List<CastDevice>>(emptyList())
        private set
    var selected by mutableStateOf<CastDevice?>(null)
        private set
    var status by mutableStateOf<CastStatus?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var connection: CastChannel? = null
    private var relay: CastMediaRelay? = null
    private var target: PlaybackTarget? = null
    private var ownedMediaSessionId: Int? = null
    private var title = ""
    private var sourceOffset = 0.0
    private var playIntent = false
    private val invalidation = AtomicInteger()
    private val completedInvalidation = AtomicInteger()
    private val invalidating: Boolean get() = invalidation.get() != completedInvalidation.get()

    fun discover(): List<CastDevice> = CastDiscovery().discover().also { devices = it }

    /** Attach to an existing media app without launching or loading anything. */
    @Synchronized
    fun attach(device: CastDevice): CastStatus? {
        try {
            check(!invalidating) { "Cast connection is resetting" }
            if (selected?.id != device.id && ownedMediaSessionId != null) {
                refresh()
                check(ownedMediaSessionId == null) { "Stop casting on the current receiver before switching controls" }
            }
            ensureConnection(device)
            return refresh()
        } catch (failure: Exception) {
            error = failure.message ?: "Could not attach to Cast receiver"
            throw failure
        }
    }

    @Synchronized
    fun play(device: CastDevice, api: HeyarrApi, assetId: String, title: String = "Media") {
        try {
            check(!invalidating) { "Cast connection is resetting" }
            require(assetId.isNotBlank()) { "This item has no playable asset" }
            val plan = api.playbackTarget(assetId, "", strict = true)
            // A direct MP4 may still contain HEVC or multichannel AAC when the node
            // cannot transcode. Only the planner's profile-converted stream is known safe.
            require(plan.restartSeekable && plan.mime == "video/mp4") {
                "Playback planner did not provide a Cast-compatible H.264/stereo stream"
            }
            val next = ensureConnection(device)
            val doorway = CastMediaRelay(device.address, URI.create(localMediaUrl(plan.url)))
            val mediaSessionId = try {
                next.load(doorway.url, title)
                check(!invalidating) { "Cast connection was reset" }
                requireNotNull(next.status?.mediaSessionId) { "Cast receiver did not open a media session" }
            } catch (failure: Exception) {
                doorway.close()
                throw failure
            }
            relay?.close()
            relay = doorway
            target = plan
            ownedMediaSessionId = mediaSessionId
            this.title = title
            sourceOffset = 0.0
            playIntent = true
            selected = device
            status = next.status
            error = null
        } catch (failure: Exception) {
            error = failure.message ?: "Cast playback failed"
            throw failure
        }
    }

    @Synchronized
    fun refresh(): CastStatus? {
        val connection = connection ?: return status
        try {
            val fresh = connection.refresh()
            if (ownedMediaSessionId != null && fresh?.mediaSessionId != ownedMediaSessionId) {
                // Another sender can replace the media without replacing the receiver
                // app. Its status and seeks must never reuse our previous film's plan.
                clearOwnedMedia()
            }
            status = fresh?.copy(
                position = fresh.position + sourceOffset,
                duration = target?.durationSeconds ?: fresh.duration,
            )
            when (fresh?.state) {
                "PLAYING" -> playIntent = true
                "PAUSED", "PAUSED_PLAYBACK" -> playIntent = false
            }
            error = connection.error ?: status?.error?.let { "Cast receiver reported $it" }
        } catch (failure: Exception) {
            error = failure.message ?: "Cast status unavailable"
        }
        return status
    }

    @Synchronized
    fun control(action: String, seconds: Double? = null) {
        try {
            check(!invalidating) { "Cast connection is resetting" }
            val connection = connection ?: error("No Cast receiver connected")
            if (ownedMediaSessionId != null && connection.status?.mediaSessionId != ownedMediaSessionId) {
                clearOwnedMedia()
            }
            if (action == "seek" && target?.restartSeekable == true) {
                val restart = restartSeek(requireNotNull(seconds), target?.durationSeconds, playIntent)
                val position = restart.position
                val wasPlaying = restart.autoplay
                val plan = requireNotNull(target)
                val base = requireNotNull(plan.streamBaseUrl)
                val local = localMediaUrl(base)
                val doorway = CastMediaRelay(requireNotNull(selected).address, URI.create(local))
                val mediaSessionId = try {
                    connection.load("${doorway.url}?start=$position", title, autoplay = wasPlaying)
                    requireNotNull(connection.status?.mediaSessionId) { "Cast receiver did not open a media session" }
                } catch (failure: Exception) {
                    doorway.close()
                    throw failure
                }
                relay?.close()
                relay = doorway
                ownedMediaSessionId = mediaSessionId
                sourceOffset = position
                status = status?.copy(position = position, state = if (wasPlaying) "PLAYING" else "PAUSED")
            } else {
                connection.control(action, seconds)
                if (action == "pause") playIntent = false
                if (action == "resume") playIntent = true
            }
            if (action == "stop") {
                clearOwnedMedia()
            }
            refresh()
        } catch (failure: Exception) {
            error = failure.message ?: "Cast control failed"
            throw failure
        }
    }

    @Synchronized
    private fun ensureConnection(device: CastDevice): CastChannel {
        if (selected?.id == device.id && connection?.error == null) return requireNotNull(connection)
        connection?.close()
        connection = null
        clearOwnedMedia()
        selected = null
        status = null
        return connect(device).also {
            connection = it
            selected = device
        }
    }

    /** A node or credential change revokes the relay off the UI thread before another Cast starts. */
    fun invalidate() {
        val id = invalidation.incrementAndGet()
        dispatchReset { resetIfCurrent(id) }
    }

    @Synchronized
    private fun resetIfCurrent(id: Int) {
        // Reset callbacks can run out of order. A stale callback must not close a
        // new connection opened after a newer reset has already finished.
        if (invalidation.get() == id) {
            try {
                close()
            } finally {
                // If another invalidation arrived while close was running, marking
                // only this generation complete keeps new playback gated on it.
                completedInvalidation.set(id)
            }
        }
    }

    @Synchronized
    override fun close() {
        clearOwnedMedia()
        connection?.close()
        connection = null
        selected = null
        status = null
        error = null
    }

    private fun clearOwnedMedia() {
        relay?.close()
        relay = null
        target = null
        ownedMediaSessionId = null
        sourceOffset = 0.0
        title = ""
        playIntent = false
    }

    companion object {
        internal data class RestartSeek(val position: Double, val autoplay: Boolean)

        private fun resetInBackground(task: () -> Unit) {
            Thread(task, "heyarr-cast-reset").apply {
                isDaemon = true
                start()
            }
        }

        internal fun restartSeek(seconds: Double, duration: Double?, playIntent: Boolean): RestartSeek {
            require(seconds.isFinite()) { "Invalid Cast seek position" }
            val end = duration?.takeIf { it.isFinite() && it >= 0 } ?: Double.MAX_VALUE
            return RestartSeek(seconds.coerceIn(0.0, end), playIntent)
        }
    }
}
