package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.desktop.heyarr.HeyarrApi
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * playbackTarget asks POST /api/v1/playback/plan and plays what it is told: the
 * server transcodes 4K/HEVC down when the client declares it cannot decode it,
 * and hands the blob over directly otherwise. The client must send a
 * conservative profile, turn the plan's relative URL into an absolute one, and
 * never let a plan failure stop playback.
 */
class PlaybackPlanTest {
    private val base = "https://h.example"

    private fun api(t: HttpTransport) = HeyarrApi(t, base, Credential.Bearer("heyarr_1_secret"))

    private fun transport(status: Int, body: String, seen: (String?) -> Unit = {}) =
        object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>) = HttpResponse(405, "")
            override fun post(url: String, body2: String?, contentType: String?, headers: Map<String, String>): HttpResponse {
                seen(body2)
                return HttpResponse(status, body)
            }
        }

    @Test
    fun aStreamPlanIsPlayedAsAnAbsoluteStreamUrl() {
        var sentBody: String? = null
        val t = transport(200, """{"mode":"stream","url":"/api/v1/playback/stream/tok123","mime":"video/mp4"}""") { sentBody = it }

        val url = api(t).playbackTarget("asset-1", "blake3:abc").url

        assertEquals("$base/api/v1/playback/stream/tok123", url)
        // A conservative, transcode-forcing profile: H.264 up to 1080p, this asset.
        assertTrue(sentBody!!.contains("\"asset_id\":\"asset-1\""), "sends the asset id")
        assertTrue(sentBody!!.contains("\"max_height\":1080"), "declares max_height 1080")
        assertTrue(sentBody!!.contains("h264"), "declares only h264 video so hevc transcodes")
    }

    @Test
    fun aStreamPlanCarriesTheSourceDurationForTheScrubber() {
        // The source's true runtime (whole seconds) rides the plan so the client can
        // pin the scrubber total; the transcode stream cannot report its own length.
        val t = transport(200, """{"mode":"stream","url":"/api/v1/playback/stream/tok","source":{"duration_seconds":2703.4}}""")
        val target = api(t).playbackTarget("asset-1", "blake3:abc")
        assertEquals("$base/api/v1/playback/stream/tok", target.url)
        assertEquals(2703.0, target.durationSeconds)
    }

    @Test
    fun aStreamPlanIsMarkedRestartSeekableOffAUrlWithNoStart() {
        // A transcode stream cannot be seeked natively (no length, no ranges), so the
        // player needs to know it must RE-CUT instead — and needs the base URL to
        // build `?start=` from, so a second seek never stacks one param on another.
        val t = transport(200, """{"mode":"stream","url":"/api/v1/playback/stream/tok"}""")
        val target = api(t).playbackTarget("asset-1", "blake3:abc")
        assertTrue(target.restartSeekable, "a stream seeks by restarting")
        assertEquals("$base/api/v1/playback/stream/tok", target.streamBaseUrl)
    }

    @Test
    fun aDirectPlanIsNotRestartSeekable() {
        // The blob endpoint answers ranges, so mpv seeks it itself — restarting would
        // throw away a perfectly good native seek.
        val t = transport(200, """{"mode":"direct","url":"/api/v1/blobs/blake3:abc/content"}""")
        val target = api(t).playbackTarget("asset-1", "blake3:abc")
        assertTrue(!target.restartSeekable, "a direct blob seeks natively")
        assertNull(target.streamBaseUrl)
    }

    @Test
    fun aFailedPlanFallsBackToADirectlySeekableBlob() {
        val t = transport(500, "boom")
        assertTrue(!api(t).playbackTarget("asset-1", "blake3:abc").restartSeekable)
    }

    @Test
    fun aDirectPlanIsPlayedAsThePlansUrl() {
        val t = transport(200, """{"mode":"direct","url":"/api/v1/blobs/blake3:abc/content"}""")
        val target = api(t).playbackTarget("asset-1", "blake3:abc")
        assertEquals("$base/api/v1/blobs/blake3:abc/content", target.url)
        assertNull(target.durationSeconds, "a direct blob has no plan-supplied duration")
    }

    @Test
    fun anAbsolutePlanUrlIsUsedVerbatim() {
        val t = transport(200, """{"mode":"stream","url":"https://peer.example/api/v1/playback/stream/tok"}""")
        assertEquals("https://peer.example/api/v1/playback/stream/tok", api(t).playbackTarget("asset-1", "blake3:abc").url)
    }

    @Test
    fun aFailedPlanFallsBackToTheDirectBlob() {
        val t = transport(500, "boom")
        assertEquals(HeyarrApi.blobUrl(base, "blake3:abc"), api(t).playbackTarget("asset-1", "blake3:abc").url)
    }

    @Test
    fun aBlankAssetIdFallsBackWithoutCallingThePlan() {
        var called = false
        val t = transport(200, "{}") { called = true }
        assertEquals(HeyarrApi.blobUrl(base, "blake3:abc"), api(t).playbackTarget("", "blake3:abc").url)
        assertTrue(!called, "no plan call when there is no asset id")
    }
}
