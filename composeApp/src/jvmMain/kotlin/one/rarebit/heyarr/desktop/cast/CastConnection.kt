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
)

internal interface CastChannel : AutoCloseable {
    val status: CastStatus?
    val error: String?
    fun load(url: String, title: String, autoplay: Boolean = true, start: Double = 0.0)
    fun control(action: String, seconds: Double? = null)
    fun refresh(): CastStatus?
}

/** One persistent Cast v2 channel. Request IDs correlate replies; broadcasts update status. */
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

    @Volatile private var lastPong = System.nanoTime()

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
            val app = application(reply)
            transportId = if (app?.first == DEFAULT_RECEIVER) app.second else null
            if (transportId != null) {
                send(CONNECTION, requireNotNull(transportId), mapOf("type" to "CONNECT"))
                request(MEDIA, requireNotNull(transportId), mapOf("type" to "GET_STATUS"))
            }
        }.onFailure { close() }.getOrThrow()
    }

    override fun load(url: String, title: String, autoplay: Boolean, start: Double) {
        if (transportId == null) {
            val launched = request(RECEIVER_NS, RECEIVER, mapOf("type" to "LAUNCH", "appId" to DEFAULT_RECEIVER))
            transportId = application(launched)?.takeIf { it.first == DEFAULT_RECEIVER }?.second
                ?: error("Cast receiver did not launch the media application")
            send(CONNECTION, requireNotNull(transportId), mapOf("type" to "CONNECT"))
        }
        val reply = request(
            MEDIA,
            requireNotNull(transportId),
            mapOf(
                "type" to "LOAD",
                "media" to mapOf(
                    "contentId" to url,
                    "contentType" to "video/mp4",
                    "streamType" to "BUFFERED",
                    "metadata" to mapOf("metadataType" to 0, "title" to title),
                ),
                "autoplay" to autoplay,
                "currentTime" to start,
            ),
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
            val app = application(request(RECEIVER_NS, RECEIVER, mapOf("type" to "GET_STATUS")))
            transportId = if (app?.first == DEFAULT_RECEIVER) app.second else null
            if (transportId != null) send(CONNECTION, requireNotNull(transportId), mapOf("type" to "CONNECT"))
        }
        if (transportId != null) request(MEDIA, requireNotNull(transportId), mapOf("type" to "GET_STATUS"))
        return status
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
        val type = JsonScan.stringField(frame.payload, "type")
        if (frame.namespace == HEARTBEAT) {
            if (type == "PING") send(HEARTBEAT, RECEIVER, mapOf("type" to "PONG"))
            if (type == "PONG") lastPong = System.nanoTime()
        } else {
            if (type == "CLOSE") error("Cast receiver closed the connection")
            if (frame.namespace == MEDIA && type == "MEDIA_STATUS") {
                status = mergeStatus(status, parseStatus(frame.payload))
            }
            completeReply(frame)
        }
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
                if (running) close()
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
            )
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
