package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.desktop.cast.CastMediaRelay
import one.rarebit.heyarr.desktop.heyarr.PlaybackTarget
import one.rarebit.heyarr.desktop.playback.AuthenticatedMediaProxy
import one.rarebit.heyarr.desktop.playback.LocalPresentation
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The node's HLS presentation (ADR-0126) as the desktop carries it, over real sockets: the
 * loopback proxy maps the playlist's whole capability prefix, the Cast relay forwards that
 * prefix to one receiver, and mpv is handed the presentation, or the live stream when the
 * node cannot cut the source.
 */
class HlsPresentationRelayTest {
    private val node = FakeHlsNode()
    private val proxy = AuthenticatedMediaProxy({ node.origin }, { Credential.Device("cert", "proof") })
    private val receiver = InetAddress.getByName("127.0.0.1") as Inet4Address
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @AfterTest fun cleanup() {
        proxy.close()
        node.close()
        client.close()
    }

    private fun get(url: String, method: String = "GET"): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
            .method(method, HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    /** What a player does with a playlist: fetch it, then each entry it names, relative to it. */
    private fun play(playlist: String): List<String> {
        val listed = get(playlist)
        assertEquals(200, listed.statusCode(), "the playlist")
        assertEquals(FakeHlsNode.PLAYLIST, listed.body())
        val names = listed.body().lines().mapNotNull { line ->
            Regex("#EXT-X-MAP:URI=\"(.+)\"").find(line)?.groupValues?.get(1)
                ?: line.takeIf { it.isNotBlank() && !it.startsWith("#") }
        }
        return names.map { name -> get(URI.create(playlist).resolve(name).toString()).body() }
    }

    @Test
    fun theProxyServesThePresentationsWholePrefixWithNoCredential() {
        val local = proxy.urlFor(node.playlistUrl)
        assertTrue(local.startsWith("http://127.0.0.1:") && local.endsWith("/index.m3u8"), local)
        assertEquals(local, proxy.urlFor(node.playlistUrl), "One presentation, one local prefix")
        assertEquals(listOf("init", "seg:0", "seg:1"), play(local))
        assertEquals(200, get(local, "HEAD").statusCode())
        val seen = node.requests.toList()
        assertEquals(
            listOf("index.m3u8", "init.mp4", "0.m4s", "1.m4s", "index.m3u8"),
            seen.map { it.path.substringAfterLast('/') },
        )
        assertTrue(seen.all { it.authorization == null }, "The capability is the authority: no device proof sent")
        assertTrue(seen.all { it.path.startsWith("/render/hls/${FakeHlsNode.CAP}/") })
    }

    @Test
    fun theProxyLetsNothingElseThroughThePrefix() {
        val local = proxy.urlFor(node.playlistUrl)
        val prefix = local.removeSuffix("index.m3u8")
        node.requests.clear()
        for (path in listOf("x.m4s", "01.m4s", "index.m3u8?start=60", "0.m4s?x=1", "sub/0.m4s", "")) {
            assertEquals(404, get(prefix + path).statusCode(), path)
        }
        assertEquals(405, get(local, "DELETE").statusCode())
        assertTrue(node.requests.isEmpty(), "Refused before the node: ${node.requests}")
        assertFailsWith<IllegalArgumentException> { proxy.urlFor("http://elsewhere.example/render/hls/cap/index.m3u8") }
        assertFailsWith<IllegalArgumentException> { proxy.urlFor("${node.origin}/render/hls/${FakeHlsNode.CAP}/0.m4s") }
        assertFailsWith<IllegalArgumentException> { proxy.urlFor(node.playlistUrl + "?x=1") }
    }

    @Test
    fun theRelayForwardsThePresentationToItsReceiverOnly() {
        CastMediaRelay(receiver, URI.create(proxy.urlFor(node.playlistUrl)), presentation = true).use { relay ->
            assertTrue(relay.url.endsWith("/index.m3u8"), relay.url)
            assertEquals(listOf("init", "seg:0", "seg:1"), play(relay.url))
            val listed = get(relay.url)
            assertEquals("*", listed.headers().firstValue("Access-Control-Allow-Origin").orElse(null))
            assertEquals("application/vnd.apple.mpegurl", listed.headers().firstValue("Content-Type").orElse(null))
            val preflight = get(relay.url, "OPTIONS")
            assertEquals(204, preflight.statusCode())
            // The node's back-off reaches the receiver.
            val busy = get(URI.create(relay.url).resolve(FakeHlsNode.BUSY).toString())
            assertEquals(429, busy.statusCode())
            assertEquals("5", busy.headers().firstValue("Retry-After").orElse(null))
            node.requests.clear()
            val prefix = relay.url.removeSuffix("index.m3u8")
            for (path in listOf("x.m4s", "../index.m3u8", "sub/0.m4s")) {
                assertEquals(404, get(prefix + path).statusCode(), path)
            }
            assertEquals(400, get("${relay.url}?start=60").statusCode(), "A presentation takes no query")
            assertEquals(404, get(prefix.removeSuffix("/")).statusCode(), "Its bare capability is not a stream")
            assertTrue(node.requests.isEmpty(), "Refused before the node: ${node.requests}")
            assertTrue(!relay.severed)
        }
    }

    @Test
    fun aLiveStreamRelayStillTakesOnlyItsOwnUrlAndAStart() {
        CastMediaRelay(receiver, URI.create(proxy.urlFor(node.streamUrl))).use { relay ->
            assertEquals("live", get(relay.url).body())
            assertEquals("live", get("${relay.url}?start=90").body())
            assertEquals(404, get("${relay.url}/index.m3u8").statusCode())
        }
    }

    @Test
    fun mpvIsHandedThePresentationAndSeeksItNatively() {
        val plan = PlaybackTarget(
            node.streamUrl,
            durationSeconds = 10.0,
            streamBaseUrl = node.streamUrl,
            mime = "video/mp4",
            hlsUrl = node.playlistUrl,
            hlsMime = "application/vnd.apple.mpegurl",
        )
        val target = LocalPresentation.choose(plan, proxy::urlFor, LocalPresentation::probe)
        assertEquals(proxy.urlFor(node.playlistUrl), target.url)
        assertNull(target.streamBaseUrl, "No restart seeks: mpv seeks the playlist itself")
        assertTrue(!target.restartSeekable)
        assertTrue(target.nodeMixesAudio, "The node still chose the audio track: a switch re-plans")
        assertEquals("application/vnd.apple.mpegurl", target.mime)
        assertEquals(10.0, target.durationSeconds)
        assertEquals(listOf("init", "seg:0", "seg:1"), play(target.url))
    }

    @Test
    fun aSourceTheNodeCannotCutFallsBackToTheLiveStream() {
        val plan = PlaybackTarget(
            node.streamUrl,
            streamBaseUrl = node.streamUrl,
            hlsUrl = node.uncutPlaylistUrl,
            hlsMime = "application/vnd.apple.mpegurl",
        )
        val target = LocalPresentation.choose(plan, proxy::urlFor, LocalPresentation::probe)
        assertEquals(proxy.urlFor(node.streamUrl), target.url)
        assertEquals(proxy.urlFor(node.streamUrl), target.streamBaseUrl)
        assertNull(target.hlsUrl)
        assertTrue(target.restartSeekable && target.nodeMixesAudio)
        assertEquals("live", get(target.url).body())
    }

    @Test
    fun aPlanWithNoPresentationIsNeverProbed() {
        val plan = PlaybackTarget(node.streamUrl, streamBaseUrl = node.streamUrl)
        val target = LocalPresentation.choose(plan, proxy::urlFor) { fail("probed $it") }
        assertEquals(proxy.urlFor(node.streamUrl), target.streamBaseUrl)
        assertTrue(!LocalPresentation.probe(proxy.urlFor(node.streamUrl)), "A live stream is not a playlist")
    }
}
