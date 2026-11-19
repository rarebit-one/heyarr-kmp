package one.rarebit.heyarr.desktop.playback

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection.HTTP_BAD_GATEWAY
import java.net.HttpURLConnection.HTTP_NOT_MODIFIED
import java.net.HttpURLConnection.HTTP_NO_CONTENT
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors

/**
 * The HTTP plumbing [AuthenticatedMediaProxy] (mpv's loopback door to the node) and the Cast
 * relay (a receiver's LAN door to that proxy) share: a small local server, an upstream client,
 * and a streaming forward that copies an allowlist of headers each way and never sends a second
 * response once a stream's headers are out.
 *
 * Policy stays with each owner, deliberately: where it binds, which requests it serves (the
 * owner's [handle] routes and refuses before calling [forward]), its header allowlists, what it
 * adds upstream (the proxy's credential) or downstream (the relay's CORS header), and what a
 * broken upstream means to it ([forward]'s `onUpstreamCut`).
 */
internal class LocalStreamForwarder(
    bind: InetSocketAddress,
    threadName: String,
    connectTimeoutSeconds: Long,
    private val requestHeaders: List<String>,
    private val responseHeaders: List<String>,
    handle: (HttpExchange) -> Unit,
) : AutoCloseable {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeoutSeconds)).build()
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, threadName).apply { isDaemon = true }
    }
    private val server = HttpServer.create(bind, 0).apply {
        executor = this@LocalStreamForwarder.executor
        createContext("/") { exchange -> handle(exchange) }
        start()
    }

    val port: Int get() = server.address.port

    @Volatile private var closed = false

    /**
     * Send [exchange]'s GET or HEAD to [upstream] and stream the answer back. [upstreamHeaders]
     * is read per request, after the allowlisted headers are copied, so a credential is always
     * the current one. [onUpstreamCut] runs when a read of the upstream body fails mid-download
     * while this forwarder is open: never for a failed write downstream (the client hung up, a
     * seek), the body's normal end, or the failed read our own [close] causes.
     */
    fun forward(
        exchange: HttpExchange,
        upstream: URI,
        upstreamHeaders: () -> Map<String, String> = ::emptyMap,
        downstreamHeaders: Map<String, String> = emptyMap(),
        onUpstreamCut: () -> Unit = {},
    ) {
        try {
            val request = HttpRequest.newBuilder(upstream)
                .method(exchange.requestMethod, HttpRequest.BodyPublishers.noBody())
            for (name in requestHeaders) {
                exchange.requestHeaders.getFirst(name)?.let { request.header(name, it) }
            }
            upstreamHeaders().forEach { (name, value) -> request.header(name, value) }
            val response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { body ->
                for (name in responseHeaders) {
                    response.headers().firstValue(name).ifPresent { exchange.responseHeaders.set(name, it) }
                }
                downstreamHeaders.forEach { (name, value) -> exchange.responseHeaders.set(name, value) }
                val size = responseSize(exchange, response)
                exchange.sendResponseHeaders(response.statusCode(), size)
                if (size != -1L) copy(body, exchange.responseBody, onUpstreamCut)
            }
        } catch (_: IOException) {
            // Seeking or stopping closes the client's old download. Do not send
            // a second response after the stream's headers have already gone out.
            failBeforeHeaders(exchange)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            failBeforeHeaders(exchange)
        } catch (_: IllegalStateException) {
            // Closing can race with submission to the HTTP client.
            failBeforeHeaders(exchange)
        }
    }

    /** [InputStream.transferTo], but telling a failed read of the upstream from a failed write downstream. */
    private fun copy(body: InputStream, client: OutputStream, onUpstreamCut: () -> Unit) {
        val buffer = ByteArray(COPY_BYTES)
        while (true) {
            val read = try {
                body.read(buffer)
            } catch (failure: IOException) {
                // Closing the forwarder (a restart, a stop) also fails this read; that is ours.
                if (!closed) onUpstreamCut()
                throw failure
            }
            if (read < 0) return
            client.write(buffer, 0, read)
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

    private companion object {
        const val COPY_BYTES = 64 * 1024
    }
}
