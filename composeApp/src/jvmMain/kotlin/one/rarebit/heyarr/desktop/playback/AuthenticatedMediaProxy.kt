package one.rarebit.heyarr.desktop.playback

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import one.rarebit.heyarr.core.auth.Credential
import java.net.HttpURLConnection.HTTP_BAD_GATEWAY
import java.net.HttpURLConnection.HTTP_BAD_METHOD
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.HttpURLConnection.HTTP_NOT_MODIFIED
import java.net.HttpURLConnection.HTTP_NO_CONTENT
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * mpv cannot renew Voidbind possession proofs. Give it loopback media URLs and obtain the
 * current credential on every upstream request, including ranges, reconnects and restart
 * seeks. Only explicitly registered media URLs at the configured node can be read; neither
 * the device credential nor its keys are handed to mpv or sent to another origin.
 */
class AuthenticatedMediaProxy(private val baseUrl: () -> String, private val credential: () -> Credential) :
    AutoCloseable {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)).build()
    private val targets = ConcurrentHashMap<String, URI>()
    private val paths = ConcurrentHashMap<URI, String>()
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "heyarr-media").apply { isDaemon = true }
    }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = this@AuthenticatedMediaProxy.executor
        createContext("/", ::serve)
        start()
    }

    fun urlFor(url: String): String {
        val target = URI.create(url)
        require(sameOrigin(target, URI.create(baseUrl()))) { "Media URL belongs to another node" }
        require(target.rawPath.startsWith("/api/v1/blobs/") || target.rawPath.startsWith("/api/v1/playback/stream/")) {
            "Not a media URL"
        }
        val path = paths.computeIfAbsent(target) {
            val fresh = "/" + UUID.randomUUID()
            targets[fresh] = target
            fresh
        }
        return "http://127.0.0.1:${server.address.port}$path"
    }

    private fun serve(exchange: HttpExchange) {
        exchange.use {
            val target = targets[it.requestURI.rawPath]
            if (target == null || !sameOrigin(target, URI.create(baseUrl()))) {
                it.sendResponseHeaders(HTTP_NOT_FOUND, -1)
                return
            }
            if (it.requestMethod != "GET" && it.requestMethod != "HEAD") {
                it.sendResponseHeaders(HTTP_BAD_METHOD, -1)
                return
            }
            forward(it, target)
        }
    }

    private fun forward(exchange: HttpExchange, target: URI) {
        var headersSent = false
        try {
            val upstream = withQuery(target, exchange.requestURI.rawQuery)
            val request = HttpRequest.newBuilder(upstream)
                .method(exchange.requestMethod, HttpRequest.BodyPublishers.noBody())
            for (name in REQUEST_HEADERS) {
                exchange.requestHeaders.getFirst(name)?.let { request.header(name, it) }
            }
            credential().asHeader().forEach { (name, value) -> request.header(name, value) }
            val response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { body ->
                for (name in RESPONSE_HEADERS) {
                    response.headers().firstValue(name).ifPresent { exchange.responseHeaders.set(name, it) }
                }
                val size = responseSize(exchange, response)
                exchange.sendResponseHeaders(response.statusCode(), size)
                headersSent = true
                if (size != -1L) body.transferTo(exchange.responseBody)
            }
        } catch (_: java.io.IOException) {
            // A seek closes the previous download. Once streaming, simply close that exchange.
            if (!headersSent) exchange.sendResponseHeaders(HTTP_BAD_GATEWAY, -1)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!headersSent) exchange.sendResponseHeaders(HTTP_BAD_GATEWAY, -1)
        } catch (_: IllegalStateException) {
            if (!headersSent) exchange.sendResponseHeaders(HTTP_BAD_GATEWAY, -1)
        }
    }

    private fun responseSize(exchange: HttpExchange, response: HttpResponse<*>): Long {
        if (exchange.requestMethod == "HEAD" || response.statusCode() in listOf(HTTP_NOT_MODIFIED, HTTP_NO_CONTENT)) {
            return -1
        }
        val size = response.headers().firstValueAsLong("Content-Length")
        return if (size.isPresent && size.asLong == 0L) -1 else size.orElse(0)
    }

    /** Restart seeks add ?start= to the local URL. Preserve the original plan's query. */
    private fun withQuery(target: URI, query: String?): URI = if (query == null) {
        target
    } else {
        URI.create(target.toASCIIString() + (if (target.rawQuery == null) "?" else "&") + query)
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        client.close()
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val HTTP_PORT = 80
        private const val HTTPS_PORT = 443
        private val REQUEST_HEADERS = listOf("Range", "If-Range", "If-None-Match", "If-Modified-Since")
        private val RESPONSE_HEADERS = listOf(
            "Content-Type",
            "Content-Length",
            "Content-Range",
            "Accept-Ranges",
            "ETag",
            "Last-Modified",
            "Cache-Control",
        )

        private fun sameOrigin(a: URI, b: URI): Boolean = a.userInfo == null && a.scheme in listOf("http", "https") &&
            a.scheme == b.scheme && a.host == b.host && port(a) == port(b)

        private fun port(uri: URI): Int = if (uri.port >= 0) {
            uri.port
        } else if (uri.scheme == "https") {
            HTTPS_PORT
        } else {
            HTTP_PORT
        }
    }
}
