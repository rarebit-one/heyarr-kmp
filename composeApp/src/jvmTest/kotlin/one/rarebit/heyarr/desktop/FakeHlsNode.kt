package one.rarebit.heyarr.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue

/**
 * A node's HLS presentation (ADR-0126) as heyarr-core#909 serves it, for the proxy, relay and
 * Cast tests: `/render/hls/{cap}/index.m3u8` names `init.mp4` and `N.m4s` relative to itself,
 * no credential is asked for, and every answer carries `Access-Control-Allow-Origin: *`.
 * Capability [UNCUT] is a source the node cannot segment (its playlist is a 404), and segment
 * [BUSY] answers 429 with `Retry-After`, as when every stream slot is taken. It also serves a
 * live stream at `/api/v1/playback/stream/tok`, the plan's fallback.
 */
internal class FakeHlsNode : AutoCloseable {
    data class Seen(val method: String, val path: String, val query: String?, val authorization: String?)

    val requests = LinkedBlockingQueue<Seen>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.use {
                val path = it.requestURI.rawPath
                requests.add(
                    Seen(it.requestMethod, path, it.requestURI.rawQuery, it.requestHeaders.getFirst("Authorization")),
                )
                it.responseHeaders.set("Access-Control-Allow-Origin", "*")
                val (status, type, body) = answer(path)
                if (status == 429) it.responseHeaders.set("Retry-After", "5")
                type?.let { mime -> it.responseHeaders.set("Content-Type", mime) }
                val bytes = body.toByteArray()
                val size = if (it.requestMethod == "HEAD" || bytes.isEmpty()) -1L else bytes.size.toLong()
                if (it.requestMethod == "HEAD") it.responseHeaders.set("Content-Length", bytes.size.toString())
                it.sendResponseHeaders(status, size)
                if (size > 0) it.responseBody.write(bytes)
            }
        }
        start()
    }

    val origin = "http://127.0.0.1:${server.address.port}"
    val playlistUrl = "$origin/render/hls/$CAP/index.m3u8"
    val uncutPlaylistUrl = "$origin/render/hls/$UNCUT/index.m3u8"
    val streamUrl = "$origin/api/v1/playback/stream/tok"

    private fun answer(path: String): Triple<Int, String?, String> {
        val prefix = "/render/hls/$CAP/"
        return when {
            path == "/api/v1/playback/stream/tok" -> Triple(200, "video/mp4", "live")

            path == "$prefix$PLAYLIST_NAME" -> Triple(200, "application/vnd.apple.mpegurl", PLAYLIST)

            path == "${prefix}init.mp4" -> Triple(200, "video/mp4", "init")

            path == "$prefix$BUSY" -> Triple(429, "application/problem+json", "{}")

            path.startsWith(prefix) && path.removePrefix(prefix).matches(Regex("(0|[1-9][0-9]*)\\.m4s")) ->
                Triple(200, "video/mp4", "seg:" + path.removePrefix(prefix).removeSuffix(".m4s"))

            else -> Triple(404, "application/problem+json", "{}")
        }
    }

    override fun close() = server.stop(0)

    companion object {
        const val CAP = "h1.capability"
        const val UNCUT = "h1.uncuttable"
        const val BUSY = "99.m4s"
        private const val PLAYLIST_NAME = "index.m3u8"

        /** A two-segment VOD playlist of the whole film, its entries relative. */
        val PLAYLIST = listOf(
            "#EXTM3U",
            "#EXT-X-VERSION:7",
            "#EXT-X-PLAYLIST-TYPE:VOD",
            "#EXT-X-TARGETDURATION:7",
            "#EXT-X-INDEPENDENT-SEGMENTS",
            "#EXT-X-MAP:URI=\"init.mp4\"",
            "#EXTINF:6.000,",
            "0.m4s",
            "#EXTINF:4.000,",
            "1.m4s",
            "#EXT-X-ENDLIST",
        ).joinToString("\n", postfix = "\n")
    }
}
