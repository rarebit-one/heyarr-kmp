package one.rarebit.heyarr.mobile.cast

import okhttp3.OkHttpClient
import one.rarebit.heyarr.mobile.net.CredentialUnavailableException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The phone relay (heyarr-kmp#250): one film to one receiver, no credential in its URL, the
 * credential added upstream by the app's interceptor, `?start=` the only query.
 */
class CastRelayTest {
    private val loopback = InetAddress.getByName("127.0.0.1") as Inet4Address
    private val closers = mutableListOf<AutoCloseable>()

    @After fun closeAll() = closers.reversed().forEach { runCatching { it.close() } }

    /** A node that answers every request with [respond], recording each request head. */
    private inner class Node(private val respond: (head: String, socket: Socket) -> Unit) : AutoCloseable {
        val heads = CopyOnWriteArrayList<String>()
        private val server = ServerSocket(0, 8, loopback)
        val url = "http://127.0.0.1:${server.localPort}/api/v1/playback/stream/tok"

        init {
            thread(isDaemon = true) {
                while (true) {
                    val s = try {
                        server.accept()
                    } catch (_: IOException) {
                        return@thread
                    }
                    thread(isDaemon = true) {
                        s.use {
                            val head = readHead(it)
                            heads += head
                            respond(head, it)
                        }
                    }
                }
            }
            closers += this
        }

        override fun close() = server.close()
    }

    private fun readHead(s: Socket): String {
        val bytes = StringBuilder()
        val input = s.getInputStream()
        while (!bytes.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) break
            bytes.append(b.toChar())
        }
        return bytes.toString()
    }

    /** The app's interceptor, in miniature: the live credential on every upstream request. */
    private fun client(header: () -> String = { "Device proof-1" }) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", header()).build())
        }
        .build()

    private fun relay(node: Node, client: OkHttpClient = client()) =
        CastRelay(loopback, node.url, client, bindTo = loopback).also { closers += it }

    /** One raw request to [url]'s doorway; the whole response as text. */
    private fun request(url: String, method: String = "GET", query: String? = null, extra: String = ""): String {
        val u = java.net.URI(url)
        Socket(u.host, u.port).use { s ->
            val target = u.rawPath + (query?.let { "?$it" } ?: "")
            s.getOutputStream().write("$method $target HTTP/1.1\r\nHost: tv\r\n$extra\r\n".toByteArray())
            s.soTimeout = 10_000
            return s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }
    }

    private val ok: (String, Socket) -> Unit = { _, s ->
        s.getOutputStream().write(
            (
                "HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: 5\r\n" +
                    "X-Internal: secret\r\n\r\nfmp4!"
                ).toByteArray(),
        )
    }

    @Test fun itForwardsTheRestartSeekAndTheRangeWithTheCredentialUpstreamOnly() {
        val node = Node(ok)
        val door = relay(node)
        assertFalse("no credential in the receiver's URL", door.url.contains("proof") || door.url.contains("tok"))
        val response = request(door.url, query = "start=600.5", extra = "Range: bytes=0-\r\nCookie: x=1\r\n")
        assertTrue(response, response.startsWith("HTTP/1.1 200 OK"))
        assertTrue(response.endsWith("fmp4!"))
        assertTrue("CORS for the receiver's page", response.contains("Access-Control-Allow-Origin: *"))
        assertTrue(response.contains("Content-Type: video/mp4"))
        assertFalse("only allow-listed response headers", response.contains("X-Internal"))
        val upstream = node.heads.single()
        assertTrue(upstream, upstream.startsWith("GET /api/v1/playback/stream/tok?start=600.5 HTTP/1.1"))
        assertTrue(upstream.contains("Range: bytes=0-"))
        assertTrue(upstream.contains("Authorization: Device proof-1"))
        assertFalse("only allow-listed request headers", upstream.contains("Cookie"))
    }

    @Test fun aLiveRepackageWithNoLengthIsSentChunked() {
        val node = Node { _, s ->
            s.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nConnection: close\r\n\r\n".toByteArray())
                write("moof".toByteArray())
                flush()
                write("mdat".toByteArray())
            }
        }
        val response = request(relay(node).url)
        assertTrue(response, response.contains("Transfer-Encoding: chunked"))
        assertTrue(response, response.endsWith("0\r\n\r\n"))
        assertTrue(response.contains("moof") && response.contains("mdat"))
    }

    @Test fun aHeadIsAnsweredWithoutABody() {
        val node = Node { _, s ->
            s.getOutputStream().write(
                "HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: 99\r\n\r\n".toByteArray(),
            )
        }
        val response = request(relay(node).url, method = "HEAD")
        assertTrue(response, response.startsWith("HTTP/1.1 200 OK"))
        assertTrue(response.endsWith("\r\n\r\n"))
        assertTrue(node.heads.single().startsWith("HEAD "))
    }

    @Test fun theCorsPreflightIsAnsweredHere() {
        val node = Node(ok)
        val response = request(relay(node).url, method = "OPTIONS")
        assertTrue(response, response.startsWith("HTTP/1.1 204"))
        assertTrue(response.contains("Access-Control-Allow-Methods: GET, HEAD, OPTIONS"))
        assertTrue("never reaches the node", node.heads.isEmpty())
    }

    @Test fun anythingElseIsRefusedWithoutReachingTheNode() {
        val node = Node(ok)
        val door = relay(node)
        assertTrue(request(door.url.substringBeforeLast('/') + "/guess").startsWith("HTTP/1.1 404"))
        assertTrue(request(door.url, method = "POST").startsWith("HTTP/1.1 405"))
        assertTrue(request(door.url, query = "start=1&token=x").startsWith("HTTP/1.1 400"))
        assertTrue(request(door.url, query = "url=http://evil").startsWith("HTTP/1.1 400"))
        assertTrue(node.heads.isEmpty())
    }

    @Test fun aLockedPhoneIsA503TheManagerWaitsOut() {
        val node = Node(ok)
        val door = relay(node, client { throw CredentialUnavailableException(IllegalStateException("DEVICE_LOCKED")) })
        val response = request(door.url)
        assertTrue(response, response.startsWith("HTTP/1.1 503"))
        assertTrue(response.contains("Retry-After: 3"))
        assertTrue(door.credentialBlocked)
        assertTrue(node.heads.isEmpty())
    }

    @Test fun aNodeThatCannotBeReachedIsABadGateway() {
        val gone = Node(ok).also { it.close() }
        val response = request(relay(gone).url)
        assertTrue(response, response.startsWith("HTTP/1.1 502"))
        assertFalse(relay(gone).severed)
    }

    @Test fun aStreamTheNodeCutsIsSeveredButAReceiverHangingUpIsNot() {
        val started = CountDownLatch(1)
        val node = Node { _, s ->
            s.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\n\r\n".toByteArray())
                write(ByteArray(1024))
                flush()
            }
            started.countDown()
            s.setSoLinger(true, 0) // a reset, not a clean end: the node went away mid-film
        }
        val door = relay(node)
        request(door.url)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        // The relay notices on its next read; give it a moment.
        repeat(50) { if (!door.severed) Thread.sleep(20) }
        assertTrue("the film must be restarted before the TV's buffer runs out", door.severed)

        val closing = relay(Node(ok))
        closing.close()
        assertFalse("closing the doorway (a seek, a stop) is ours", closing.severed)
    }

    private fun head(method: String, path: String, query: String?) = CastRelay.Head(method, path, query, emptyMap())

    @Test fun thePolicyIsTheReceiverAndTheCapabilityFirst() {
        val tv = InetAddress.getByName("192.168.1.50")
        val other = InetAddress.getByName("192.168.1.51")
        val path = "/media/abc"
        assertEquals(404, CastRelay.rejection(tv, path, other, head("GET", path, null)))
        assertEquals(
            "another host learns nothing, not even the method rule",
            404,
            CastRelay.rejection(tv, path, other, head("POST", path, null)),
        )
        assertEquals(404, CastRelay.rejection(tv, path, tv, head("GET", "/media/abd", null)))
        assertEquals(405, CastRelay.rejection(tv, path, tv, head("PUT", path, null)))
        assertEquals(400, CastRelay.rejection(tv, path, tv, head("GET", path, "start=-1")))
        assertEquals(400, CastRelay.rejection(tv, path, tv, head("GET", path, "start=1e3")))
        assertNull(CastRelay.rejection(tv, path, tv, head("GET", path, "start=12.345")))
        assertNull(CastRelay.rejection(tv, path, tv, head("HEAD", path, null)))
        assertNull(CastRelay.rejection(tv, path, tv, head("OPTIONS", path, "anything")))
    }

    @Test fun aRequestHeadIsReadBoundedAndParsed() {
        val head = CastRelay.readHead(
            ByteArrayInputStream(
                "GET /media/x?start=5 HTTP/1.1\r\nRange: bytes=0-\r\nHost: tv\r\n\r\nbody".toByteArray(),
            ),
        )!!
        assertEquals("GET", head.method)
        assertEquals("/media/x", head.rawPath)
        assertEquals("start=5", head.rawQuery)
        assertEquals("bytes=0-", head.headers["range"])
        assertNull("no query", CastRelay.parseHead("GET /media/x HTTP/1.1\r\n\r\n")!!.rawQuery)
        assertNull("not HTTP", CastRelay.parseHead("GET /media/x\r\n\r\n"))
        assertNull("not a path", CastRelay.parseHead("GET http://elsewhere/ HTTP/1.1\r\n\r\n"))
        assertNull("truncated", CastRelay.readHead(ByteArrayInputStream("GET / HTTP/1.1\r\n".toByteArray())))
        assertNull(
            "unbounded",
            CastRelay.readHead(
                ByteArrayInputStream(
                    (
                        "GET /" + "a".repeat(9000) +
                            " HTTP/1.1\r\n\r\n"
                        ).toByteArray(),
                ),
            ),
        )
    }

    // ── heyarr-core#908: an HLS presentation, relayed as its prefix ──────────────────────

    @Test fun aPresentationIsRelayedAsItsWholePrefixWithNoCredentialAndNoQuery() {
        val node = Node { head, s ->
            val name = head.substringAfter("/render/hls/cap/").substringBefore(' ')
            val (status, body) = when (name) {
                "index.m3u8" -> "200 OK" to "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n0.m4s\n"
                "init.mp4" -> "200 OK" to "init"
                "0.m4s" -> "200 OK" to "seg0"
                else -> "429 Too Many Requests" to ""
            }
            val retry = if (status.startsWith("429")) "Retry-After: 5\r\n" else ""
            s.getOutputStream().write(
                "HTTP/1.1 $status\r\nContent-Type: video/mp4\r\n${retry}Content-Length: ${body.length}\r\n\r\n$body"
                    .toByteArray(),
            )
        }
        val playlist = node.url.substringBefore("/api/") + "/render/hls/cap/index.m3u8"
        // A presentation's client signs nothing: the capability in the path is the authority.
        val door = CastRelay(loopback, playlist, OkHttpClient(), bindTo = loopback, presentation = true)
            .also { closers += it }
        assertTrue(door.url, door.url.endsWith("/index.m3u8"))
        assertTrue(request(door.url).endsWith("0.m4s\n"))
        val prefix = door.url.removeSuffix("index.m3u8")
        assertTrue(request(prefix + "init.mp4").endsWith("init"))
        assertTrue(request(prefix + "0.m4s").endsWith("seg0"))
        val busy = request(prefix + "99.m4s")
        assertTrue(busy, busy.startsWith("HTTP/1.1 429") && busy.contains("Retry-After: 5"))
        assertEquals(
            listOf("index.m3u8", "init.mp4", "0.m4s", "99.m4s"),
            node.heads.map { it.substringAfter("/render/hls/cap/").substringBefore(' ') },
        )
        assertFalse("no credential upstream", node.heads.any { it.contains("Authorization") })
        node.heads.clear()
        assertTrue(request(door.url, query = "start=60").startsWith("HTTP/1.1 400"))
        for (name in listOf("x.m4s", "01.m4s", "sub/0.m4s")) {
            assertTrue(name, request(prefix + name).startsWith("HTTP/1.1 404"))
        }
        assertTrue("its bare capability is no stream", request(prefix.removeSuffix("/")).startsWith("HTTP/1.1 404"))
        assertTrue("refused before the node", node.heads.isEmpty())
    }

    @Test fun aPresentationsPolicyIsItsPrefixAndEntryNamesOnly() {
        val head = { target: String -> CastRelay.parseHead("GET $target HTTP/1.1\r\nHost: tv\r\n")!! }
        val path = "/media/abc"
        assertNull(CastRelay.rejection(loopback, path, loopback, head("$path/index.m3u8"), presentation = true))
        assertNull(CastRelay.rejection(loopback, path, loopback, head("$path/12.m4s"), presentation = true))
        assertEquals(404, CastRelay.rejection(loopback, path, loopback, head(path), presentation = true))
        assertEquals(
            400,
            CastRelay.rejection(loopback, path, loopback, head("$path/0.m4s?start=1"), presentation = true),
        )
        assertEquals(404, CastRelay.rejection(loopback, path, loopback, head("$path/index.m3u8"), presentation = false))
        assertEquals("0.m4s", CastRelay.entryOf(path, "$path/0.m4s", presentation = true))
        assertNull(CastRelay.entryOf(path, "$path/0.m4s", presentation = false))
    }
}
