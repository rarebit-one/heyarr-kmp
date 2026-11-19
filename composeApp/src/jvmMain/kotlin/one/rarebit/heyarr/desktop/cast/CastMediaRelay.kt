package one.rarebit.heyarr.desktop.cast

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.HttpURLConnection.HTTP_BAD_GATEWAY
import java.net.HttpURLConnection.HTTP_BAD_METHOD
import java.net.HttpURLConnection.HTTP_BAD_REQUEST
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.HttpURLConnection.HTTP_NOT_MODIFIED
import java.net.HttpURLConnection.HTTP_NO_CONTENT
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.Executors

/** A receiver-scoped LAN doorway to the loopback credential proxy, with no credential in the URL. */
internal class CastMediaRelay(private val receiver: Inet4Address, private val upstream: URI) : AutoCloseable {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build()
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "heyarr-cast-media").apply { isDaemon = true }
    }
    private val path = "/media/" + ByteArray(CAPABILITY_BYTES).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it) }
    private val local = DatagramSocket().use { route ->
        route.connect(receiver, CAST_PORT)
        route.localAddress as? Inet4Address ?: error("No IPv4 route to Cast receiver")
    }
    private val server = HttpServer.create(InetSocketAddress(local, 0), 0).apply {
        executor = this@CastMediaRelay.executor
        createContext("/", ::serve)
        start()
    }

    val url: String get() = "http://${local.hostAddress}:${server.address.port}$path"

    @Volatile private var closed = false

    /**
     * The node's stream broke under a download the receiver was still reading: the laptop
     * slept, the network dropped, or the node went away. The receiver plays on from its
     * buffer and then stops, so the film must be restarted before that. A receiver that
     * hangs up (a seek, a stop) is not this, nor is the stream's normal end.
     */
    @Volatile var severed = false
        private set

    private fun serve(exchange: HttpExchange) {
        exchange.use {
            val refusal = rejection(it)
            when {
                refusal != null -> it.sendResponseHeaders(refusal, -1)
                it.requestMethod == "OPTIONS" -> preflight(it)
                else -> forward(it)
            }
        }
    }

    private fun rejection(exchange: HttpExchange): Int? {
        val query = exchange.requestURI.rawQuery
        return when {
            exchange.remoteAddress.address != receiver || exchange.requestURI.rawPath != path -> HTTP_NOT_FOUND
            exchange.requestMethod == "OPTIONS" -> null
            exchange.requestMethod !in listOf("GET", "HEAD") -> HTTP_BAD_METHOD
            query != null && !query.matches(SEEK_QUERY) -> HTTP_BAD_REQUEST
            else -> null
        }
    }

    private fun preflight(exchange: HttpExchange) {
        exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
        exchange.responseHeaders.set("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        exchange.responseHeaders.set("Access-Control-Allow-Headers", REQUEST_HEADERS.joinToString(", "))
        exchange.sendResponseHeaders(HTTP_NO_CONTENT, -1)
    }

    private fun forward(exchange: HttpExchange) {
        try {
            val request = HttpRequest.newBuilder(withQuery(exchange.requestURI.rawQuery))
                .method(exchange.requestMethod, HttpRequest.BodyPublishers.noBody())
            for (name in REQUEST_HEADERS) {
                exchange.requestHeaders.getFirst(name)?.let { request.header(name, it) }
            }
            val response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { body -> sendResponse(exchange, response, body) }
        } catch (_: IOException) {
            // Seeking or stopping closes the receiver's old download. Do not send
            // a second response after the stream's headers have already gone out.
            failBeforeHeaders(exchange)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            failBeforeHeaders(exchange)
        } catch (_: IllegalStateException) {
            // Closing the relay can race with submission to its HTTP client.
            failBeforeHeaders(exchange)
        }
    }

    private fun withQuery(query: String?): URI = if (query == null) {
        upstream
    } else {
        val separator = if (upstream.rawQuery == null) "?" else "&"
        URI.create("$upstream$separator$query")
    }

    private fun sendResponse(exchange: HttpExchange, response: HttpResponse<InputStream>, body: InputStream) {
        for (name in RESPONSE_HEADERS) {
            response.headers().firstValue(name).ifPresent { exchange.responseHeaders.set(name, it) }
        }
        exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
        val size = responseSize(exchange, response)
        exchange.sendResponseHeaders(response.statusCode(), size)
        if (size != -1L) relay(body, exchange.responseBody)
    }

    /** [InputStream.transferTo], but telling a failed read of the node from a failed write to the receiver. */
    private fun relay(body: InputStream, receiver: OutputStream) {
        val buffer = ByteArray(COPY_BYTES)
        while (true) {
            val read = try {
                body.read(buffer)
            } catch (failure: IOException) {
                // Closing the relay (a restart, a stop) also fails this read; that is ours.
                if (!closed) severed = true
                throw failure
            }
            if (read < 0) return
            receiver.write(buffer, 0, read)
        }
    }

    private fun responseSize(exchange: HttpExchange, response: HttpResponse<*>): Long {
        val emptyResponse = exchange.requestMethod == "HEAD" ||
            response.statusCode() in listOf(HTTP_NOT_MODIFIED, HTTP_NO_CONTENT)
        val size = response.headers().firstValueAsLong("Content-Length")
        return if (emptyResponse || (size.isPresent && size.asLong == 0L)) -1 else size.orElse(0)
    }

    private fun failBeforeHeaders(exchange: HttpExchange) {
        if (exchange.responseCode == -1) exchange.sendResponseHeaders(HTTP_BAD_GATEWAY, -1)
    }

    override fun close() {
        closed = true
        server.stop(0)
        executor.shutdownNow()
        client.close()
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val CAPABILITY_BYTES = 24
        private const val COPY_BYTES = 64 * 1024
        private const val CAST_PORT = 8009
        private val SEEK_QUERY = Regex("start=[0-9]+(?:\\.[0-9]+)?")
        private val REQUEST_HEADERS = listOf("Range", "If-Range", "If-None-Match")
        private val RESPONSE_HEADERS = listOf("Content-Type", "Content-Range", "Accept-Ranges", "Content-Length")
    }
}
