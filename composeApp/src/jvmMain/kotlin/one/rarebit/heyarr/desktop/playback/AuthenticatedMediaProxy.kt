package one.rarebit.heyarr.desktop.playback

import com.sun.net.httpserver.HttpExchange
import one.rarebit.heyarr.core.auth.Credential
import java.net.HttpURLConnection.HTTP_BAD_METHOD
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * mpv cannot renew Voidbind possession proofs. Give it loopback media URLs and obtain the
 * current credential on every upstream request, including ranges, reconnects and restart
 * seeks. Only explicitly registered media URLs at the configured node can be read; neither
 * the device credential nor its keys are handed to mpv or sent to another origin.
 */
class AuthenticatedMediaProxy(private val baseUrl: () -> String, private val credential: () -> Credential) :
    AutoCloseable {
    private val targets = ConcurrentHashMap<String, URI>()
    private val paths = ConcurrentHashMap<URI, String>()

    // Loopback only: mpv is on this machine, and the credential it never sees is added here.
    private val forwarder = LocalStreamForwarder(
        InetSocketAddress("127.0.0.1", 0),
        "heyarr-media",
        CONNECT_TIMEOUT_SECONDS,
        REQUEST_HEADERS,
        RESPONSE_HEADERS,
        ::serve,
    )

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
        return "http://127.0.0.1:${forwarder.port}$path"
    }

    // The forwarder closes the exchange once this returns.
    private fun serve(exchange: HttpExchange) {
        val target = targets[exchange.requestURI.rawPath]
        if (target == null || !sameOrigin(target, URI.create(baseUrl()))) {
            exchange.sendResponseHeaders(HTTP_NOT_FOUND, -1)
            return
        }
        if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
            exchange.sendResponseHeaders(HTTP_BAD_METHOD, -1)
            return
        }
        // A node stream that breaks is passed on as a break, not a clean end: the Cast relay
        // reads through this proxy and restarts the film only when it sees the cut, and mpv
        // must not take a broken transcode for the film's end either.
        forwarder.forward(
            exchange,
            withQuery(target, exchange.requestURI.rawQuery),
            upstreamHeaders = { credential().asHeader() },
            passCutOn = true,
        )
    }

    /** Restart seeks add ?start= to the local URL. Preserve the original plan's query. */
    private fun withQuery(target: URI, query: String?): URI = if (query == null) {
        target
    } else {
        URI.create(target.toASCIIString() + (if (target.rawQuery == null) "?" else "&") + query)
    }

    override fun close() = forwarder.close()

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
