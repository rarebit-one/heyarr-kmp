package one.rarebit.heyarr.desktop.playback

import com.sun.net.httpserver.HttpExchange
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.playback.HlsPresentation
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
 *
 * An HLS presentation (ADR-0126) is registered by its playlist but served as its whole
 * capability prefix, because the playlist names its init and segments relative to itself:
 * one local prefix maps to the node's, and only [HlsPresentation]'s entry names pass under
 * it. Those routes take no credential (the capability is the authority), so none is sent.
 */
class AuthenticatedMediaProxy(private val baseUrl: () -> String, private val credential: () -> Credential) :
    AutoCloseable {
    private val targets = ConcurrentHashMap<String, URI>()
    private val paths = ConcurrentHashMap<URI, String>()

    /** Local prefix (no trailing slash) → the node's presentation prefix (with one). */
    private val presentations = ConcurrentHashMap<String, URI>()
    private val presentationPaths = ConcurrentHashMap<URI, String>()

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
        if (HlsPresentation.isPlaylistPath(target.rawPath)) return presentationUrl(target)
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

    /** The local URL of [playlist]'s presentation: its own capability prefix, then the playlist. */
    private fun presentationUrl(playlist: URI): String {
        require(playlist.rawQuery == null && playlist.rawFragment == null) { "Not an HLS playlist URL" }
        val prefix = URI.create(HlsPresentation.prefixOf(playlist.toASCIIString()))
        val local = presentationPaths.computeIfAbsent(prefix) {
            val fresh = "/" + UUID.randomUUID()
            presentations[fresh] = prefix
            fresh
        }
        return "http://127.0.0.1:${forwarder.port}$local/${HlsPresentation.PLAYLIST}"
    }

    /** The node URL an HLS entry request maps to, or null when [path] is none of a presentation's. */
    private fun presentationEntry(path: String, query: String?): URI? {
        val slash = path.lastIndexOf('/')
        val name = path.substring(slash + 1)
        return if (slash > 0 && query == null && HlsPresentation.isEntry(name)) {
            presentations[path.substring(0, slash)]?.resolve(name)
        } else {
            null
        }
    }

    // The forwarder closes the exchange once this returns.
    private fun serve(exchange: HttpExchange) {
        val uri = exchange.requestURI
        val registered = targets[uri.rawPath]
        val entry = if (registered == null) presentationEntry(uri.rawPath, uri.rawQuery) else null
        val target = registered ?: entry
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
            entry ?: withQuery(target, uri.rawQuery),
            // The capability is an HLS entry's authority: the device's proof goes nowhere it isn't asked for.
            upstreamHeaders = if (entry != null) ::emptyMap else ({ credential().asHeader() }),
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
            // A presentation answers 429 when every stream slot is busy; the player backs off by it.
            "Retry-After",
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
