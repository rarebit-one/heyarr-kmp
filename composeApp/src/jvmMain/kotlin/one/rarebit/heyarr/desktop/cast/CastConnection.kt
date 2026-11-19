package one.rarebit.heyarr.desktop.cast

import one.rarebit.heyarr.core.mcp.JsonWrite
import one.rarebit.heyarr.core.net.JsonScan
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal data class CastStatus(
    val state: String,
    val position: Double,
    val duration: Double?,
    val title: String?,
    val mediaSessionId: Int?,
    val error: String? = null,
    /**
     * Why an IDLE session ended: FINISHED, CANCELLED (a stop, from any sender or the TV's
     * remote), INTERRUPTED (a new LOAD) or ERROR. Whether a dropped film is resumed hangs on it.
     */
    val idleReason: String? = null,
)

/**
 * What one channel saw of a media session in any MEDIA_STATUS, a reply or the receiver's
 * unsolicited broadcast. [idleReason] is latched: once the session reported why it ended, a
 * later report that says nothing (the empty status after the receiver closes it) keeps it.
 */
internal data class SessionSighting(val idleReason: String?)

internal interface CastChannel : AutoCloseable {
    val status: CastStatus?
    val error: String?

    /**
     * [duration] is the length of what [url] plays, when known. A live repackage has no
     * duration in its header, so the receiver otherwise reports only the buffered end. On a
     * real Default Media Receiver the declaration holds for about a second after LOAD before
     * the media element replaces it with the buffered end again, so the TV's own scrub bar
     * stays short; only an HLS stream with a real playlist fixes that (heyarr-core#908).
     */
    fun load(url: String, title: String, autoplay: Boolean = true, start: Double = 0.0, duration: Double? = null)
    fun control(action: String, seconds: Double? = null)
    fun refresh(): CastStatus?

    /**
     * What this channel saw of [mediaSessionId] since the last [load] or [stopReceiver]: null
     * when it never saw it. The Default Media Receiver can close a session between two polls
     * (it played out the buffer of a stream that ended mid-film), so the next poll finds only
     * an empty status; the reason it broadcast on the way out is kept here.
     */
    fun sighting(mediaSessionId: Int): SessionSighting?

    /**
     * Quits the receiver's media app (a receiver-level STOP of its session), so the next
     * [load] launches a fresh one. A wedged Default Media Receiver keeps its broken
     * pipeline across every LOAD into the same session; only a new session clears it.
     * A no-op when the media app is not running.
     */
    fun stopReceiver()
}

/** One persistent Cast v2 channel. Request IDs correlate replies; broadcasts update status. */
@Suppress("TooManyFunctions") // Keep inbound identity validation beside this channel's stateful frame handling.
internal class CastConnection(private val device: CastDevice) : CastChannel {
    private val socket = Socket().apply { connect(InetSocketAddress(device.address, device.port), CONNECT_TIMEOUT_MS) }

    // Cast receivers present device-generated certificates. TLS encrypts the title and
    // media capability URL on this local channel; the mDNS address is the identity.
    private val tls = runCatching {
        (castTls.socketFactory.createSocket(socket, device.address.hostAddress, device.port, true) as SSLSocket)
            .apply {
                soTimeout = READ_TIMEOUT_MS
                startHandshake()
            }
    }.onFailure { socket.close() }.getOrThrow()
    private val sourceId = "sender-${SecureRandom().nextInt().toUInt()}"
    private data class Pending(val namespace: String, val source: String, val reply: CompletableFuture<String>)
    private val requests = ConcurrentHashMap<Int, Pending>()
    private val ids = AtomicInteger(SecureRandom().nextInt(Int.MAX_VALUE / REQUEST_ID_DIVISOR) + 1)

    @Volatile private var running = true

    @Volatile private var transportId: String? = null

    /** The media app's receiver session, which a receiver-level STOP names. */
    @Volatile private var appSessionId: String? = null

    @Volatile private var lastPong = System.nanoTime()

    /**
     * Every media session a MEDIA_STATUS named, newest last, bounded: written on the reader
     * thread, read by the manager's poll. Cleared per LOAD and receiver STOP, because a freshly
     * launched media app numbers its sessions from the start again, and an old session's
     * CANCELLED must never be read as the stop of a new one.
     */
    private val sightings = object : LinkedHashMap<Int, SessionSighting>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, SessionSighting>?) = size > MAX_SIGHTINGS
    }

    @Volatile override var status: CastStatus? = null
        private set

    @Volatile override var error: String? = null
        private set

    init {
        Thread(::readLoop, "heyarr-cast-reader").apply {
            isDaemon = true
            start()
        }
        Thread(::heartbeatLoop, "heyarr-cast-heartbeat").apply {
            isDaemon = true
            start()
        }
        runCatching {
            send(CONNECTION, RECEIVER, mapOf("type" to "CONNECT"))
            val reply = request(RECEIVER_NS, RECEIVER, mapOf("type" to "GET_STATUS"))
            adopt(reply)
            if (transportId != null) {
                send(CONNECTION, requireNotNull(transportId), mapOf("type" to "CONNECT"))
                request(MEDIA, requireNotNull(transportId), mapOf("type" to "GET_STATUS"))
            }
        }.onFailure { close() }.getOrThrow()
    }

    override fun load(url: String, title: String, autoplay: Boolean, start: Double, duration: Double?) {
        forgetSightings()
        if (transportId == null) {
            adopt(request(RECEIVER_NS, RECEIVER, mapOf("type" to "LAUNCH", "appId" to DEFAULT_RECEIVER)))
            checkNotNull(transportId) { "Cast receiver did not launch the media application" }
            send(CONNECTION, requireNotNull(transportId), mapOf("type" to "CONNECT"))
        }
        val media = mutableMapOf<String, Any?>(
            "contentId" to url,
            "contentType" to "video/mp4",
            "streamType" to "BUFFERED",
            "metadata" to mapOf("metadataType" to 0, "title" to title),
        )
        duration?.takeIf { it.isFinite() && it > 0 }?.let { media["duration"] = it }
        val reply = request(
            MEDIA,
            requireNotNull(transportId),
            mapOf("type" to "LOAD", "media" to media, "autoplay" to autoplay, "currentTime" to start),
        )
        requireResponse(reply, "MEDIA_STATUS")
        check(status?.state != "IDLE" || status?.error == null) { "Cast receiver failed to play the media" }
    }

    override fun control(action: String, seconds: Double?) {
        val sessionId = status?.mediaSessionId ?: error("Cast receiver has no active media session")
        val type = when (action) {
            "pause" -> "PAUSE"
            "resume" -> "PLAY"
            "seek" -> "SEEK"
            "stop" -> "STOP"
            else -> error("Unsupported Cast action")
        }
        val args = mutableMapOf<String, Any?>("type" to type, "mediaSessionId" to sessionId)
        if (type == "SEEK") args["currentTime"] = requireNotNull(seconds)
        val reply = request(MEDIA, requireNotNull(transportId), args)
        requireResponse(reply, "MEDIA_STATUS")
    }

    override fun refresh(): CastStatus? {
        if (transportId == null) {
            adopt(request(RECEIVER_NS, RECEIVER, mapOf("type" to "GET_STATUS")))
            if (transportId != null) send(CONNECTION, requireNotNull(transportId), mapOf("type" to "CONNECT"))
        }
        if (transportId != null) request(MEDIA, requireNotNull(transportId), mapOf("type" to "GET_STATUS"))
        return status
    }

    override fun stopReceiver() {
        val session = appSessionId ?: return
        request(RECEIVER_NS, RECEIVER, mapOf("type" to "STOP", "sessionId" to session))
        // The old transport's late MEDIA_STATUS frames are now refused by acceptsInbound,
        // and its media session can no longer be controlled or reported as ours.
        transportId = null
        appSessionId = null
        status = null
        forgetSightings()
    }

    override fun sighting(mediaSessionId: Int): SessionSighting? = synchronized(sightings) { sightings[mediaSessionId] }

    private fun forgetSightings() = synchronized(sightings) { sightings.clear() }

    /** Latches each session [body] names, keeping an earlier idleReason when this report has none. */
    private fun sight(body: String) = synchronized(sightings) {
        for ((session, reason) in sightingsOf(body)) {
            sightings[session] = SessionSighting(reason ?: sightings[session]?.idleReason)
        }
    }

    /** Takes the Default Media Receiver's transport and session from a RECEIVER_STATUS, or forgets them. */
    private fun adopt(reply: String) {
        val app = application(reply)?.takeIf { it.first == DEFAULT_RECEIVER }
        transportId = app?.second
        appSessionId = app?.let { sessionOf(reply) }
    }

    private fun request(namespace: String, destination: String, values: Map<String, Any?>): String {
        check(running) { error ?: "Cast connection closed" }
        val id = ids.getAndIncrement()
        val pending = CompletableFuture<String>()
        requests[id] = Pending(namespace, destination, pending)
        try {
            send(namespace, destination, values + ("requestId" to id))
            return pending.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS).also {
                requireResponse(it, if (namespace == RECEIVER_NS) "RECEIVER_STATUS" else "MEDIA_STATUS")
            }
        } finally {
            requests.remove(id)
        }
    }

    private fun send(namespace: String, destination: String, values: Map<String, Any?>) {
        synchronized(tls.outputStream) {
            CastWire.write(tls.outputStream, CastFrame(sourceId, destination, namespace, JsonWrite.obj(values)))
        }
    }

    private fun readLoop() {
        runCatching {
            while (running) {
                handleFrame(CastWire.read(tls.inputStream))
            }
        }.onFailure { failure ->
            if (running) error = failure.message ?: "Cast connection lost"
        }.also {
            running = false
            requests.values.forEach {
                it.reply.completeExceptionally(IllegalStateException(error ?: "Cast connection closed"))
            }
            requests.clear()
            runCatching { tls.close() }
        }
    }

    private fun handleFrame(frame: CastFrame) {
        if (!acceptsInbound(frame)) return
        val type = JsonScan.stringField(frame.payload, "type")
        if (frame.namespace == HEARTBEAT) {
            if (type == "PING") send(HEARTBEAT, RECEIVER, mapOf("type" to "PONG"))
            if (type == "PONG") lastPong = System.nanoTime()
        } else {
            if (type == "CLOSE") error("Cast receiver closed the connection")
            if (frame.namespace == MEDIA && type == "MEDIA_STATUS") {
                // Broadcasts too: they carry the idleReason a poll can miss (heyarr-kmp#249).
                sight(frame.payload)
                status = mergeStatus(status, parseStatus(frame.payload))
            }
            completeReply(frame)
        }
    }

    private fun acceptsInbound(frame: CastFrame): Boolean =
        (frame.destination == sourceId || frame.destination == "*") &&
            when (frame.namespace) {
                HEARTBEAT, RECEIVER_NS -> frame.source == RECEIVER
                MEDIA -> frame.source == transportId
                CONNECTION -> frame.source == RECEIVER || frame.source == transportId
                else -> false
            }

    private fun completeReply(frame: CastFrame) {
        val id = JsonScan.longField(frame.payload, "requestId")?.toInt() ?: return
        val matched = requests[id] ?: return
        if (matchesReply(frame, matched.namespace, matched.source, sourceId) && requests.remove(id, matched)) {
            matched.reply.complete(frame.payload)
        }
    }

    private fun heartbeatLoop() {
        while (running) {
            try {
                Thread.sleep(HEARTBEAT_MS)
                if (running && System.nanoTime() - lastPong > TimeUnit.SECONDS.toNanos(HEARTBEAT_TIMEOUT_SECONDS)) {
                    error = "Cast receiver stopped responding"
                    close()
                }
                if (running) send(HEARTBEAT, RECEIVER, mapOf("type" to "PING"))
            } catch (_: Exception) {
                // A failed PING is a dead socket. Say so: the manager reconnects on an error,
                // and a channel that closed silently would look healthy to it.
                if (running) {
                    error = error ?: "Cast connection lost"
                    close()
                }
            }
        }
    }

    override fun close() {
        running = false
        runCatching { tls.close() }
        runCatching { socket.close() }
    }

    companion object {
        private const val RECEIVER = "receiver-0"
        private const val DEFAULT_RECEIVER = "CC1AD845"
        private const val CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        private const val HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        private const val RECEIVER_NS = "urn:x-cast:com.google.cast.receiver"
        private const val MEDIA = "urn:x-cast:com.google.cast.media"
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 10_000
        private const val REQUEST_TIMEOUT_SECONDS = 10L
        private const val HEARTBEAT_MS = 5_000L
        private const val HEARTBEAT_TIMEOUT_SECONDS = 25L
        private const val REQUEST_ID_DIVISOR = 2

        /** Ours, the one before it across a restart, and a few foreign ones: plenty. */
        private const val MAX_SIGHTINGS = 8

        private val castTls = SSLContext.getInstance("TLS").apply {
            init(
                null,
                arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }),
                SecureRandom(),
            )
        }

        internal fun application(body: String): Pair<String, String>? = JsonScan.objectAt(body, "status")
            ?.let { JsonScan.objectsOf(it, listOf("applications")).firstOrNull() }
            ?.let { app ->
                val id = JsonScan.stringField(app, "appId")
                val transport = JsonScan.stringField(app, "transportId")
                if (id == null || transport == null) null else id to transport
            }

        internal fun sessionOf(body: String): String? = JsonScan.objectAt(body, "status")
            ?.let { JsonScan.objectsOf(it, listOf("applications")).firstOrNull() }
            ?.let { JsonScan.stringField(it, "sessionId") }

        internal fun parseStatus(body: String): CastStatus? {
            val first = JsonScan.objectsOf(body, listOf("status")).firstOrNull() ?: return null
            val media = JsonScan.objectAt(first, "media")
            val metadata = media?.let { JsonScan.objectAt(it, "metadata") }
            return CastStatus(
                JsonScan.stringField(first, "playerState") ?: "UNKNOWN",
                JsonScan.doubleField(first, "currentTime") ?: 0.0,
                media?.let { JsonScan.doubleField(it, "duration") },
                metadata?.let { JsonScan.stringField(it, "title") },
                JsonScan.longField(first, "mediaSessionId")?.toInt(),
                JsonScan.stringField(first, "idleReason")?.takeIf { it == "ERROR" || it == "INTERRUPTED" },
                JsonScan.stringField(first, "idleReason"),
            )
        }

        /** Each media session [body] reports on, with the idleReason it gave (null: none). */
        internal fun sightingsOf(body: String): List<Pair<Int, String?>> =
            JsonScan.objectsOf(body, listOf("status")).mapNotNull { entry ->
                val session = JsonScan.longField(entry, "mediaSessionId")?.toInt()
                session?.let { it to JsonScan.stringField(entry, "idleReason") }
            }

        internal fun mergeStatus(previous: CastStatus?, next: CastStatus?): CastStatus? =
            if (next?.mediaSessionId == null || next.mediaSessionId != previous?.mediaSessionId) {
                next
            } else {
                next.copy(duration = next.duration ?: previous.duration, title = next.title ?: previous.title)
            }

        internal fun requireResponse(body: String, expected: String) {
            val type = JsonScan.stringField(body, "type") ?: error("Cast response has no type")
            require(type == expected) { "Cast receiver replied $type instead of $expected" }
        }

        internal fun matchesReply(frame: CastFrame, namespace: String, source: String, sender: String): Boolean =
            frame.namespace == namespace && frame.source == source &&
                (frame.destination == sender || frame.destination == "*")
    }
}
