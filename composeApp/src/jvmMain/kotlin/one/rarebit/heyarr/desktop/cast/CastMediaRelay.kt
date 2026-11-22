package one.rarebit.heyarr.desktop.cast

import com.sun.net.httpserver.HttpExchange
import one.rarebit.heyarr.core.playback.HlsPresentation
import one.rarebit.heyarr.desktop.playback.LocalStreamForwarder
import java.net.DatagramSocket
import java.net.HttpURLConnection.HTTP_BAD_GATEWAY
import java.net.HttpURLConnection.HTTP_BAD_METHOD
import java.net.HttpURLConnection.HTTP_BAD_REQUEST
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.HttpURLConnection.HTTP_NO_CONTENT
import java.net.HttpURLConnection.HTTP_OK
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.URI
import java.security.SecureRandom

/**
 * A receiver-scoped LAN doorway to the loopback credential proxy, with no credential in the URL.
 *
 * For a live stream it is one URL, [upstream], with only `?start=` let through. For an HLS
 * presentation ([presentation], ADR-0126) [upstream] is the proxy's playlist URL and the
 * doorway forwards the whole prefix the playlist's relative entries resolve under: the
 * playlist, the init and each segment, with no query at all ([HlsPresentation]).
 */
internal class CastMediaRelay(
    private val receiver: Inet4Address,
    private val upstream: URI,
    private val presentation: Boolean = false,
    private val captions: List<CastCaption> = emptyList(),
    private val captionOffset: Double = 0.0,
) : AutoCloseable {
    private val path = "/media/" + ByteArray(CAPABILITY_BYTES).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it) }
    private val local = DatagramSocket().use { route ->
        route.connect(receiver, CAST_PORT)
        route.localAddress as? Inet4Address ?: error("No IPv4 route to Cast receiver")
    }

    /**
     * The node's stream broke under a download the receiver was still reading: the laptop
     * slept, the network dropped, or the node went away. The receiver plays on from its
     * buffer and then stops, so the film must be restarted before that. A receiver that
     * hangs up (a seek, a stop) is not this, nor is the stream's normal end.
     */
    @Volatile var severed = false
        private set

    // Bound to the LAN address that routes to the receiver (not loopback: the receiver is
    // another machine), and answering only it; no credential ever crosses this hop.
    private val forwarder = LocalStreamForwarder(
        InetSocketAddress(local, 0),
        "heyarr-cast-media",
        CONNECT_TIMEOUT_SECONDS,
        REQUEST_HEADERS,
        RESPONSE_HEADERS,
        ::serve,
    )

    val url: String get() = "http://${local.hostAddress}:${forwarder.port}$path" +
        if (presentation) "/${HlsPresentation.PLAYLIST}" else ""

    private fun captionPath(index: Int) = "$path/captions/$index.vtt"

    val tracks: List<CastCaption> get() = captions.mapIndexed { index, track ->
        track.copy(url = "http://${local.hostAddress}:${forwarder.port}" + captionPath(index))
    }

    /** The HLS entry a presentation request names (null: none, or not a presentation). */
    private fun entryOf(rawPath: String): String? = rawPath.takeIf { presentation && it.startsWith("$path/") }
        ?.substring(path.length + 1)?.takeIf(HlsPresentation::isEntry)

    // The forwarder closes the exchange once this returns.
    private fun serve(exchange: HttpExchange) {
        val caption = captions.indices.firstOrNull { captionPath(it) == exchange.requestURI.rawPath }
        val refusal = rejection(exchange, caption != null)
        when {
            refusal != null -> exchange.sendResponseHeaders(refusal, -1)
            exchange.requestMethod == "OPTIONS" -> preflight(exchange)
            caption != null -> serveCaption(exchange, captions[caption])
            else -> forward(exchange)
        }
    }

    private fun serveCaption(exchange: HttpExchange, caption: CastCaption) {
        val bytes = runCatching { caption.webVtt(captionOffset) }.getOrElse {
            exchange.sendResponseHeaders(HTTP_BAD_GATEWAY, -1)
            return
        }
        exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
        exchange.responseHeaders.set("Content-Type", "text/vtt; charset=utf-8")
        exchange.responseHeaders.set("Content-Length", bytes.size.toString())
        val head = exchange.requestMethod == "HEAD"
        exchange.sendResponseHeaders(HTTP_OK, if (head) -1 else bytes.size.toLong())
        if (!head) exchange.responseBody.write(bytes)
    }

    // A failed read of the node mid-download marks the relay severed; the forwarder already
    // leaves out a receiver hanging up, the stream's normal end and our own close. The
    // receiver's answer still ends cleanly, as it did on the hardware run: it plays out its
    // buffer while the manager restarts the film.
    private fun forward(exchange: HttpExchange) = forwarder.forward(
        exchange,
        entryOf(exchange.requestURI.rawPath)?.let(upstream::resolve) ?: withQuery(exchange.requestURI.rawQuery),
        downstreamHeaders = CORS,
        onUpstreamCut = { severed = true },
    )

    private fun rejection(exchange: HttpExchange, caption: Boolean): Int? {
        val query = exchange.requestURI.rawQuery
        val known = if (presentation) {
            entryOf(exchange.requestURI.rawPath) != null
        } else {
            exchange.requestURI.rawPath ==
                path
        }
        return when {
            exchange.remoteAddress.address != receiver || (!known && !caption) -> HTTP_NOT_FOUND

            exchange.requestMethod == "OPTIONS" -> null

            exchange.requestMethod !in listOf("GET", "HEAD") -> HTTP_BAD_METHOD

            // A presentation is seeked by fetching another segment: nothing takes a query.
            query != null && (caption || presentation || !query.matches(SEEK_QUERY)) -> HTTP_BAD_REQUEST

            else -> null
        }
    }

    private fun preflight(exchange: HttpExchange) {
        exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
        exchange.responseHeaders.set("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        exchange.responseHeaders.set("Access-Control-Allow-Headers", REQUEST_HEADERS.joinToString(", "))
        exchange.sendResponseHeaders(HTTP_NO_CONTENT, -1)
    }

    private fun withQuery(query: String?): URI = if (query == null) {
        upstream
    } else {
        val separator = if (upstream.rawQuery == null) "?" else "&"
        URI.create("$upstream$separator$query")
    }

    override fun close() = forwarder.close()

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val CAPABILITY_BYTES = 24
        private const val CAST_PORT = 8009
        private val SEEK_QUERY = Regex("start=[0-9]+(?:\\.[0-9]+)?")
        private val REQUEST_HEADERS = listOf("Range", "If-Range", "If-None-Match")
        private val RESPONSE_HEADERS =
            listOf("Content-Type", "Content-Range", "Accept-Ranges", "Content-Length", "Retry-After")

        // The receiver's media element fetches cross-origin from the Cast app's page.
        private val CORS = mapOf("Access-Control-Allow-Origin" to "*")
    }
}
