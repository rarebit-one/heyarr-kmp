package one.rarebit.heyarr.desktop

import com.sun.net.httpserver.HttpServer
import one.rarebit.heyarr.desktop.cast.CastMediaRelay
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real HTTP requests exercise the LAN relay without a receiver or a media server. */
class CastMediaRelayTest {
    private data class Seen(
        val method: String,
        val query: String?,
        val range: String?,
        val validator: String?,
        val authorization: String?,
    )

    private val requests = LinkedBlockingQueue<Seen>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/media") { exchange ->
            exchange.use {
                val headers = it.requestHeaders
                requests.add(
                    Seen(
                        it.requestMethod,
                        it.requestURI.rawQuery,
                        headers.getFirst("Range"),
                        headers.getFirst("If-Range") ?: headers.getFirst("If-None-Match"),
                        headers.getFirst("Authorization"),
                    ),
                )
                if (headers.getFirst("If-None-Match") == "unchanged") {
                    it.sendResponseHeaders(304, -1)
                } else {
                    val ranged = headers.getFirst("Range") != null
                    val body = if (ranged) "2345" else "0123456789"
                    it.responseHeaders.set("Content-Type", "video/mp4")
                    it.responseHeaders.set("Accept-Ranges", "bytes")
                    it.responseHeaders.set("Content-Length", body.length.toString())
                    if (ranged) it.responseHeaders.set("Content-Range", "bytes 2-5/10")
                    val size = if (it.requestMethod == "HEAD") -1L else body.length.toLong()
                    it.sendResponseHeaders(if (ranged) 206 else 200, size)
                    if (size != -1L) it.responseBody.write(body.toByteArray())
                }
            }
        }
        start()
    }
    private val upstream = URI.create("http://127.0.0.1:${server.address.port}/media?profile=1")
    private val receiver = InetAddress.getByName("127.0.0.1") as Inet4Address
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @AfterTest fun cleanup() {
        server.stop(0)
        client.close()
    }

    @Test fun ranges_and_restart_queries_reach_only_the_registered_upstream() {
        CastMediaRelay(receiver, upstream).use { relay ->
            val request = request("${relay.url}?start=90.5")
                .header("Range", "bytes=2-5")
                .header("If-Range", "source-validator")
                .header("Authorization", "Bearer downstream-must-not-be-forwarded")
                .GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assertEquals(206, response.statusCode())
            assertEquals("2345", response.body())
            assertEquals("bytes 2-5/10", response.headers().firstValue("Content-Range").orElse(null))
            assertEquals("bytes", response.headers().firstValue("Accept-Ranges").orElse(null))
            assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
            assertEquals(
                Seen("GET", "profile=1&start=90.5", "bytes=2-5", "source-validator", null),
                requests.poll(5, TimeUnit.SECONDS),
            )
        }
    }

    @Test fun head_preserves_the_source_length_without_a_response_body() {
        CastMediaRelay(receiver, upstream).use { relay ->
            val response = send(request(relay.url).method("HEAD", HttpRequest.BodyPublishers.noBody()))
            assertEquals(200, response.statusCode())
            assertEquals("10", response.headers().firstValue("Content-Length").orElse(null))
            assertEquals("video/mp4", response.headers().firstValue("Content-Type").orElse(null))
            assertEquals("", response.body())
            assertEquals(Seen("HEAD", "profile=1", null, null, null), requests.poll(5, TimeUnit.SECONDS))
        }
    }

    @Test fun preflight_is_answered_locally_and_does_not_open_an_upstream_stream() {
        CastMediaRelay(receiver, upstream).use { relay ->
            val response = send(
                request(relay.url).method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Origin", "https://receiver.example")
                    .header("Access-Control-Request-Method", "GET")
                    .header("Access-Control-Request-Headers", "Range, If-Range"),
            )
            assertEquals(204, response.statusCode())
            assertEquals("", response.body())
            assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
            assertTrue(response.headers().firstValue("Access-Control-Allow-Methods").orElse("").contains("GET"))
            assertTrue(response.headers().firstValue("Access-Control-Allow-Headers").orElse("").contains("Range"))
            assertNull(requests.poll())
        }
    }

    @Test fun unknown_paths_methods_and_unapproved_queries_never_reach_upstream() {
        CastMediaRelay(receiver, upstream).use { relay ->
            val unknownPath = URI.create(relay.url).resolve("/media/not-the-capability")
            assertEquals(404, send(request(unknownPath.toString())).statusCode())
            assertEquals(405, send(request(relay.url).POST(HttpRequest.BodyPublishers.noBody())).statusCode())
            val invalid = listOf(
                "start=-1",
                "start=NaN",
                "start=1&start=2",
                "url=https://other.example",
                "start=1&token=x",
            )
            for (query in invalid) {
                assertEquals(400, send(request("${relay.url}?$query")).statusCode(), query)
            }
            assertNull(requests.poll())
        }
    }

    @Test fun a_different_peer_is_rejected_even_with_the_capability_and_forwarded_header() {
        val otherReceiver = InetAddress.getByName("127.0.0.2") as Inet4Address
        CastMediaRelay(otherReceiver, upstream).use { relay ->
            val uri = URI.create(relay.url)
            // Bind the caller explicitly: the test must not depend on the OS choosing
            // a particular source address for another address in the loopback network.
            Socket().use { socket ->
                socket.bind(InetSocketAddress(receiver, 0))
                socket.connect(InetSocketAddress(uri.host, uri.port), 5_000)
                socket.soTimeout = 5_000
                val wire = "GET ${uri.rawPath} HTTP/1.1\r\nHost: ${uri.host}\r\n" +
                    "X-Forwarded-For: ${otherReceiver.hostAddress}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(wire.toByteArray())
                assertTrue(socket.getInputStream().bufferedReader().readLine().startsWith("HTTP/1.1 404"))
            }
            assertNull(requests.poll())
        }
    }

    @Test fun conditional_not_modified_has_no_body() {
        CastMediaRelay(receiver, upstream).use { relay ->
            val response = send(request(relay.url).header("If-None-Match", "unchanged"))
            assertEquals(304, response.statusCode())
            assertEquals("", response.body())
            assertEquals(Seen("GET", "profile=1", null, "unchanged", null), requests.poll(5, TimeUnit.SECONDS))
        }
    }

    @Test fun closing_the_relay_revokes_its_url_and_a_new_relay_has_a_new_capability() {
        val oldUrl = CastMediaRelay(receiver, upstream).use { relay ->
            assertEquals(200, send(request(relay.url)).statusCode())
            requests.poll(5, TimeUnit.SECONDS)
            relay.url
        }
        assertFailsWith<IOException> { send(request(oldUrl)) }
        CastMediaRelay(receiver, upstream).use { replacement ->
            assertNotEquals(URI.create(oldUrl).path, URI.create(replacement.url).path)
            val revoked = URI.create(replacement.url).resolve(URI.create(oldUrl).path)
            assertEquals(404, send(request(revoked.toString())).statusCode())
            assertNull(requests.poll())
        }
    }

    // ── heyarr-kmp#248: a stream cut under the relay ────────────────────────────────────

    /** A node whose live stream sends one chunk, then [ending]: the last chunk, or nothing. */
    private fun liveNode(ending: String?, body: String = "moov"): Pair<ServerSocket, URI> {
        val node = ServerSocket(0, 0, receiver)
        Thread {
            runCatching {
                node.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (input.readLine().orEmpty().isNotEmpty()) Unit
                    val out = socket.getOutputStream()
                    val head = "HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nTransfer-Encoding: chunked\r\n\r\n"
                    out.write((head + "%x\r\n%s\r\n".format(body.length, body)).toByteArray())
                    out.flush()
                    Thread.sleep(300)
                    ending?.let { out.write(it.toByteArray()) }
                    out.flush()
                }
            }
        }.apply { isDaemon = true }.start()
        return node to URI.create("http://127.0.0.1:${node.localPort}/stream")
    }

    @Test fun a_node_stream_that_breaks_mid_download_marks_the_relay_severed() {
        val (node, live) = liveNode(ending = null)
        node.use {
            CastMediaRelay(receiver, live).use { relay ->
                runCatching { send(request(relay.url)) }
                assertTrue(relay.severed, "The receiver will play out its buffer and stop")
            }
        }
    }

    @Test fun the_streams_normal_end_and_a_receiver_hanging_up_are_not_a_cut() {
        val (node, live) = liveNode(ending = "0\r\n\r\n")
        node.use {
            CastMediaRelay(receiver, live).use { relay ->
                assertEquals("moov", send(request(relay.url)).body())
                assertFalse(relay.severed)
            }
        }
        // The receiver drops its download (a seek, a stop) while the node still has data.
        val (busy, endless) = liveNode(ending = null, body = "x".repeat(4 * 1024 * 1024))
        busy.use {
            CastMediaRelay(receiver, endless).use { relay ->
                val uri = URI.create(relay.url)
                Socket(uri.host, uri.port).use { socket ->
                    socket.getOutputStream()
                        .write("GET ${uri.rawPath} HTTP/1.1\r\nHost: ${uri.host}\r\n\r\n".toByteArray())
                    socket.getInputStream().readNBytes(1024)
                }
                Thread.sleep(500)
                assertFalse(relay.severed, "A receiver hanging up is not the node's stream breaking")
            }
        }
    }

    private fun request(url: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))

    private fun send(request: HttpRequest.Builder): HttpResponse<String> =
        client.send(request.build(), HttpResponse.BodyHandlers.ofString())
}
