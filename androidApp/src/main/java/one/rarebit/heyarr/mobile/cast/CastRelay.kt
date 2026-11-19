package one.rarebit.heyarr.mobile.cast

import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import one.rarebit.heyarr.mobile.net.CredentialUnavailableException
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * What the receiver is handed instead of the node's URL: [url] serves one film to one
 * receiver. The manager watches [severed] and [credentialBlocked] and replaces the doorway on
 * every restart seek, so a stale URL serves nothing.
 */
interface MediaDoorway : AutoCloseable {
    val url: String

    /**
     * The node's stream broke under a download the receiver was still reading: the phone slept,
     * the network dropped, or the node went away. The receiver plays on from its buffer and then
     * stops, so the film must be restarted before that. A receiver that hangs up (a seek, a
     * stop) is not this, nor is the stream's normal end.
     */
    val severed: Boolean

    /** A request could not be signed (the phone is locked) and was answered 503. */
    val credentialBlocked: Boolean
}

/**
 * The phone relay (heyarr-kmp#250): a receiver-scoped LAN doorway to the node's stream, the
 * desktop's `CastMediaRelay` (#242) on Android, where there is no `com.sun.net.httpserver`.
 *
 *  - **Receiver-only.** It binds the phone's address on the route to [receiver] (never every
 *    interface), and anything not from [receiver]'s IP, or not at the random capability
 *    [path], is a 404, which says nothing about what is here.
 *  - **No credential in the URL.** The capability opens this doorway only; the upstream request
 *    carries this phone's live credential, stamped by the app's `AuthInterceptor` on [client]
 *    (a fresh Device proof per request), and a refused or locked signature is the node's
 *    business, not the receiver's.
 *  - **`?start=` only.** The one query a receiver may send is the restart seek ADR-0069 defines;
 *    it is forwarded, and anything else is a 400. GET, HEAD and the CORS preflight a Cast
 *    receiver sends; nothing else.
 *
 * A locked phone cannot sign (`AuthHeaderSource`), so a request made while it is locked is
 * answered `503` with `Retry-After` and noted in [credentialBlocked]: the manager waits for the
 * unlock instead of reloading into a refusal. A film already flowing needs no new signature;
 * only a restart does.
 *
 * TODO(heyarr-core#908): an HLS source is a playlist and its segments, relative to it. Serving
 * one is a prefix capability — [path] plus segment names in a fixed alphabet, still receiver-only
 * and credential-free — and [rejection] is the one place that widens. A credential-free HLS URL
 * on the home LAN skips the relay altogether ([CastRouting.decide]).
 */
class CastRelay(
    private val receiver: Inet4Address,
    upstream: String,
    private val client: OkHttpClient,
    bindTo: Inet4Address = routeTo(receiver),
) : MediaDoorway {
    private val upstream = upstream.toHttpUrl()
    private val path = "/media/" + ByteArray(CAPABILITY_BYTES).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it) }
    private val server = ServerSocket(0, BACKLOG, bindTo)
    private val executor: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "heyarr-cast-relay").apply { isDaemon = true }
    }
    private val calls: MutableSet<Call> = ConcurrentHashMap.newKeySet()

    @Volatile private var closed = false

    @Volatile override var severed = false
        private set

    @Volatile override var credentialBlocked = false
        private set

    override val url: String = "http://${bindTo.hostAddress}:${server.localPort}$path"

    init {
        executor.execute(::accept)
    }

    private fun accept() {
        while (!closed) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                return // closed
            }
            try {
                executor.execute { serve(socket) }
            } catch (_: RejectedExecutionException) {
                socket.close() // closing raced the accept
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            s.soTimeout = HEAD_TIMEOUT_MS
            val out = BufferedOutputStream(s.getOutputStream())
            val head = readHead(s.getInputStream())
            if (head == null) {
                respond(out, HTTP_BAD_REQUEST)
                return
            }
            val refusal = rejection(receiver, path, s.inetAddress, head)
            when {
                refusal != null -> respond(out, refusal)

                head.method == "OPTIONS" -> preflight(out)

                else -> {
                    // A live repackage streams for the whole film: no read timeout on the receiver side.
                    s.soTimeout = 0
                    forward(head, out)
                }
            }
        }
    }

    private fun forward(head: Head, out: OutputStream) {
        val target = head.rawQuery?.let { query ->
            val separator = if (upstream.encodedQuery == null) "?" else "&"
            "$upstream$separator$query".toHttpUrl()
        } ?: upstream
        val request = Request.Builder().url(target).apply {
            if (head.method == "HEAD") head() else get()
            for (name in REQUEST_HEADERS) head.headers[name.lowercase()]?.let { header(name, it) }
        }.build()
        val call = client.newCall(request)
        calls += call
        var sent = false
        try {
            call.execute().use { response ->
                sent = true
                send(out, head.method, response)
            }
        } catch (failure: IOException) {
            if (!sent) failBeforeHeaders(out, failure)
        } finally {
            calls -= call
        }
    }

    private fun failBeforeHeaders(out: OutputStream, failure: IOException) {
        if (CredentialUnavailableException.isCause(failure)) {
            credentialBlocked = true
            respond(out, HTTP_UNAVAILABLE, extra = listOf("Retry-After" to RETRY_AFTER_SECONDS.toString()))
        } else {
            respond(out, HTTP_BAD_GATEWAY)
        }
    }

    private fun send(out: OutputStream, method: String, response: Response) {
        val headers = RESPONSE_HEADERS.mapNotNull { name -> response.header(name)?.let { name to it } }
        val length = response.header("Content-Length")
        val bodiless = method == "HEAD" || response.code == HTTP_NO_CONTENT || response.code == HTTP_NOT_MODIFIED
        // A live repackage has no length: it is sent chunked, as the desktop relay's
        // HttpServer does and as the receivers it was proven on read it.
        val chunked = !bodiless && length == null
        writeHead(
            out,
            response.code,
            headers + CORS + listOfNotNull(("Transfer-Encoding" to "chunked").takeIf { chunked }),
        )
        if (!bodiless) copy(response.body.byteStream(), out, chunked)
        out.flush()
    }

    /** [InputStream.transferTo], but telling a failed read of the node from a failed write to the receiver. */
    private fun copy(body: InputStream, out: OutputStream, chunked: Boolean) {
        val buffer = ByteArray(COPY_BYTES)
        while (true) {
            val read = try {
                body.read(buffer)
            } catch (failure: IOException) {
                // Closing the relay (a restart, a stop) also fails this read; that is ours.
                if (!closed) severed = true
                throw failure
            }
            if (read < 0) break
            try {
                if (chunked) out.write("${Integer.toHexString(read)}\r\n".toByteArray(Charsets.US_ASCII))
                out.write(buffer, 0, read)
                if (chunked) out.write(CRLF)
            } catch (_: IOException) {
                return // the receiver hung up: a seek or a stop
            }
        }
        if (chunked) out.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
    }

    private fun preflight(out: OutputStream) {
        writeHead(
            out,
            HTTP_NO_CONTENT,
            CORS + listOf(
                "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                "Access-Control-Allow-Headers" to REQUEST_HEADERS.joinToString(", "),
            ),
        )
        out.flush()
    }

    private fun respond(out: OutputStream, status: Int, extra: List<Pair<String, String>> = emptyList()) {
        runCatching {
            writeHead(out, status, extra + ("Content-Length" to "0"))
            out.flush()
        }
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        calls.forEach(Call::cancel)
        executor.shutdownNow()
    }

    /** A request line and its headers, names lower-cased. */
    data class Head(val method: String, val rawPath: String, val rawQuery: String?, val headers: Map<String, String>)

    companion object {
        private const val CAPABILITY_BYTES = 24
        private const val REQUEST_LINE_PARTS = 3 // METHOD target HTTP/1.1
        private const val CAST_PORT = 8009
        private const val BACKLOG = 8
        private const val HEAD_TIMEOUT_MS = 10_000
        private const val MAX_HEAD_BYTES = 8 * 1024
        private const val COPY_BYTES = 64 * 1024
        private const val RETRY_AFTER_SECONDS = 3
        private val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)
        private val SEEK_QUERY = Regex("start=[0-9]+(?:\\.[0-9]+)?")
        private val REQUEST_HEADERS = listOf("Range", "If-Range", "If-None-Match")
        private val RESPONSE_HEADERS = listOf("Content-Type", "Content-Range", "Accept-Ranges", "Content-Length")
        private val CORS = listOf("Access-Control-Allow-Origin" to "*")

        const val HTTP_OK = 200
        const val HTTP_PARTIAL = 206
        const val HTTP_NO_CONTENT = 204
        const val HTTP_NOT_MODIFIED = 304
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_NOT_FOUND = 404
        const val HTTP_BAD_METHOD = 405
        const val HTTP_BAD_GATEWAY = 502
        const val HTTP_UNAVAILABLE = 503

        /** The phone's own IPv4 address on the route to [receiver]. A UDP connect sends nothing. */
        fun routeTo(receiver: Inet4Address): Inet4Address = DatagramSocket().use { route ->
            route.connect(receiver, CAST_PORT)
            route.localAddress as? Inet4Address ?: error("No IPv4 route to the Cast receiver")
        }

        /**
         * Why a request is refused, or null to serve it. Pure, so the doorway's whole policy is
         * unit-tested: not the receiver or not the capability is a 404 (it reveals nothing),
         * then anything but GET/HEAD/OPTIONS is a 405 and any query but `start=` a 400.
         */
        fun rejection(receiver: InetAddress, path: String, remote: InetAddress?, head: Head): Int? = when {
            remote != receiver || head.rawPath != path -> HTTP_NOT_FOUND
            head.method == "OPTIONS" -> null
            head.method !in listOf("GET", "HEAD") -> HTTP_BAD_METHOD
            head.rawQuery != null && !head.rawQuery.matches(SEEK_QUERY) -> HTTP_BAD_REQUEST
            else -> null
        }

        /** Reads one request head, at most [MAX_HEAD_BYTES]; null when it is not one. */
        @Suppress("ReturnCount") // Each way a head is not one is its own early refusal.
        fun readHead(input: InputStream): Head? {
            val bytes = ByteArrayOutputStream()
            var matched = 0
            while (matched < HEAD_END.size) {
                val b = try {
                    input.read()
                } catch (_: IOException) {
                    return null
                }
                if (b < 0 || bytes.size() >= MAX_HEAD_BYTES) return null
                bytes.write(b)
                matched = if (b.toByte() == HEAD_END[matched]) {
                    matched + 1
                } else if (b == '\r'.code) {
                    1
                } else {
                    0
                }
            }
            return parseHead(bytes.toString(Charsets.ISO_8859_1.name()))
        }

        private val HEAD_END = "\r\n\r\n".toByteArray(Charsets.US_ASCII)

        /** Parses `METHOD target HTTP/1.x` and its header lines. Pure. */
        @Suppress("ReturnCount") // Each way a head is not one is its own early refusal.
        fun parseHead(text: String): Head? {
            val lines = text.split("\r\n")
            val parts = lines.first().split(' ')
            if (parts.size != REQUEST_LINE_PARTS || !parts[2].startsWith("HTTP/1.")) return null
            val target = parts[1]
            if (!target.startsWith("/")) return null
            val headers = lines.drop(1).filter { it.isNotEmpty() }.mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <=
                    0
                ) {
                    null
                } else {
                    line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
                }
            }.toMap()
            val query = target.substringAfter('?', "").takeIf { '?' in target }
            return Head(parts[0], target.substringBefore('?'), query, headers)
        }

        private fun writeHead(out: OutputStream, status: Int, headers: List<Pair<String, String>>) {
            val head = StringBuilder("HTTP/1.1 $status ${reason(status)}\r\n")
            for ((name, value) in headers) head.append(name).append(": ").append(value).append("\r\n")
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        }

        private fun reason(status: Int): String = when (status) {
            HTTP_OK -> "OK"
            HTTP_PARTIAL -> "Partial Content"
            HTTP_NO_CONTENT -> "No Content"
            HTTP_NOT_MODIFIED -> "Not Modified"
            HTTP_BAD_REQUEST -> "Bad Request"
            HTTP_NOT_FOUND -> "Not Found"
            HTTP_BAD_METHOD -> "Method Not Allowed"
            HTTP_BAD_GATEWAY -> "Bad Gateway"
            HTTP_UNAVAILABLE -> "Service Unavailable"
            else -> "Status"
        }
    }
}
