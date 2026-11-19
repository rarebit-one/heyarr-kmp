package one.rarebit.heyarr.desktop.cast

import com.sun.net.httpserver.HttpExchange
import one.rarebit.heyarr.desktop.playback.LocalStreamForwarder
import java.net.DatagramSocket
import java.net.HttpURLConnection.HTTP_BAD_METHOD
import java.net.HttpURLConnection.HTTP_BAD_REQUEST
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.HttpURLConnection.HTTP_NO_CONTENT
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.URI
import java.security.SecureRandom

/** A receiver-scoped LAN doorway to the loopback credential proxy, with no credential in the URL. */
internal class CastMediaRelay(private val receiver: Inet4Address, private val upstream: URI) : AutoCloseable {
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

    val url: String get() = "http://${local.hostAddress}:${forwarder.port}$path"

    // The forwarder closes the exchange once this returns.
    private fun serve(exchange: HttpExchange) {
        val refusal = rejection(exchange)
        when {
            refusal != null -> exchange.sendResponseHeaders(refusal, -1)
            exchange.requestMethod == "OPTIONS" -> preflight(exchange)
            else -> forward(exchange)
        }
    }

    // A failed read of the node mid-download marks the relay severed; the forwarder already
    // leaves out a receiver hanging up, the stream's normal end and our own close. The
    // receiver's answer still ends cleanly, as it did on the hardware run: it plays out its
    // buffer while the manager restarts the film.
    private fun forward(exchange: HttpExchange) = forwarder.forward(
        exchange,
        withQuery(exchange.requestURI.rawQuery),
        downstreamHeaders = CORS,
        onUpstreamCut = { severed = true },
    )

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
        private val RESPONSE_HEADERS = listOf("Content-Type", "Content-Range", "Accept-Ranges", "Content-Length")

        // The receiver's media element fetches cross-origin from the Cast app's page.
        private val CORS = mapOf("Access-Control-Allow-Origin" to "*")
    }
}
