package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.playback.ClientCapabilities
import one.rarebit.heyarr.mobile.playback.PlaybackClient
import one.rarebit.heyarr.mobile.playback.PlaybackTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A transport that records the last POST and returns a canned response (or throws). */
private class RecordingTransport(private val response: HttpResponse?, private val failure: Exception? = null) :
    HttpTransport {
    var lastPostUrl: String? = null
        private set
    var lastPostBody: String? = null
        private set
    var lastPostAuth: String? = null
        private set
    var lastPostContentType: String? = null
        private set
    var posts = 0
        private set
    override fun get(url: String, headers: Map<String, String>): HttpResponse = HttpResponse(405, "")
    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse {
        posts++
        lastPostUrl = url
        lastPostBody = body
        lastPostAuth = headers["Authorization"]
        lastPostContentType = contentType
        failure?.let { throw it }
        return response!!
    }
}

private const val HASH = "blake3:a617353faeec5b99c042ceb349976d8171e5b7efc6fb1924fbc8b0d605ad8c9e"
private val CAPS = ClientCapabilities(video = listOf("h264", "hevc"), audio = listOf("aac", "mp3"), maxHeight = 2412)

class PlaybackClientTest {

    @Test fun blobTargetPointsAtRangeCapableUrlUnderCredential() {
        val client =
            PlaybackClient(RecordingTransport(HttpResponse(200, "")), "https://h.example/", Credential.Session("tok"))
        val target = client.blobTarget(HASH, isVideo = true, mimeType = "video/mp4")
        assertEquals("https://h.example/api/v1/blobs/$HASH/content", target.contentUrl)
        assertEquals("Bearer tok", target.authHeaders()["Authorization"])
        assertTrue(target.isVideo)
        assertTrue(target.seekable)
        assertEquals(PlaybackTarget.Origin.DIRECT_UNPLANNED, target.origin)
        assertEquals("video/mp4", target.mimeType)
    }

    @Test fun planPostsAssetAndClientCapabilitiesUnderAuth() {
        val t = RecordingTransport(HttpResponse(200, """{"mode":"direct","url":"/api/v1/blobs/$HASH/content"}"""))
        val client = PlaybackClient(t, "https://h.example", Credential.Session("tok"))
        val r = client.plan("asset-1", CAPS)
        assertEquals("https://h.example/api/v1/playback/plan", t.lastPostUrl)
        assertEquals(
            """{"asset_id":"asset-1","client":{"containers":["mp4","mkv","webm"],"video":["h264","hevc"],"audio":["aac","mp3"],"max_height":2412}}""",
            t.lastPostBody,
        )
        assertEquals("application/json", t.lastPostContentType)
        assertEquals("Bearer tok", t.lastPostAuth)
        assertTrue(r is PlaybackClient.PlanResult.Direct)
        assertEquals("https://h.example/api/v1/blobs/$HASH/content", (r as PlaybackClient.PlanResult.Direct).url)
    }

    @Test fun planCarriesTheDeviceCredentialHeader() {
        val t = RecordingTransport(HttpResponse(200, """{"mode":"direct"}"""))
        val client = PlaybackClient(t, "https://h.example", Credential.Device("cert", "proof"))
        client.plan("asset-1", CAPS)
        assertEquals("Device cert~proof", t.lastPostAuth)
    }

    @Test fun planReadsAStreamAnswer() {
        val t = RecordingTransport(
            HttpResponse(
                200,
                """{"mode":"stream","url":"/api/v1/playback/stream/abc","mime":"video/mp4","reason":"audio ac3 not decodable",
                   "source":{"container":"mp4","video":"h264","audio":"ac3","width":1920,"height":1080}}""",
            ),
        )
        val client = PlaybackClient(t, "https://h.example", Credential.Session("tok"))
        val r = client.plan("asset-1", CAPS) as PlaybackClient.PlanResult.Stream
        assertEquals("https://h.example/api/v1/playback/stream/abc", r.url)
        assertEquals("video/mp4", r.mime)
        assertEquals("audio ac3 not decodable", r.reason)
        assertEquals("ac3", r.source?.audio)
        assertEquals(1080, r.source?.height)
    }

    @Test fun olderNodeRejectingClientFieldIsUnavailableNotAnError() {
        val t = RecordingTransport(HttpResponse(400, """{"title":"unknown field \"client\""}"""))
        val client = PlaybackClient(t, "https://h.example", Credential.Session("tok"))
        assertTrue(client.plan("asset-1", CAPS) is PlaybackClient.PlanResult.Unavailable)
    }

    @Test fun transportFailureIsUnavailableNotAnError() {
        val t = RecordingTransport(null, failure = java.io.IOException("connection refused"))
        val client = PlaybackClient(t, "https://h.example", Credential.Session("tok"))
        val r = client.plan("asset-1", CAPS)
        assertTrue(r is PlaybackClient.PlanResult.Unavailable)
        assertTrue((r as PlaybackClient.PlanResult.Unavailable).why.contains("connection refused"))
    }

    @Test fun planThrowsOnOtherNon200() {
        val t = RecordingTransport(HttpResponse(403, "forbidden"))
        val client = PlaybackClient(t, "https://h.example", Credential.Session("tok"))
        try {
            client.plan("a", CAPS)
            throw AssertionError("expected an exception on HTTP 403")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("403"))
        }
    }

    // ── resolve: the fallback contract ──────────────────────────────────────────

    @Test fun resolveFallsBackToDirectBlobExactlyAsBeforeOnAnOlderNode() {
        val t = RecordingTransport(HttpResponse(400, "unknown field"))
        val client = PlaybackClient(t, "https://h.example", Credential.Session("tok"))
        val target = client.resolve("asset-1", HASH, isVideo = true, mimeType = "video/mp4", caps = CAPS)
        val direct = client.blobTarget(HASH, isVideo = true, mimeType = "video/mp4")
        assertEquals(direct.contentUrl, target.contentUrl)
        assertEquals(direct.authHeaders(), target.authHeaders())
        assertTrue(target.seekable)
        assertEquals(PlaybackTarget.Origin.DIRECT_UNPLANNED, target.origin)
        assertFalse(target.planned)
        assertEquals(1, t.posts)
    }

    @Test fun resolveKeepsTheBlobForAPlannedDirectAnswerWithoutUrl() {
        val t = RecordingTransport(HttpResponse(200, """{"mode":"direct","reason":"all tracks decodable"}"""))
        val client = PlaybackClient(t, "https://h.example", Credential.Session("tok"))
        val target = client.resolve("asset-1", HASH, isVideo = true, mimeType = "video/mp4", caps = CAPS)
        assertEquals("https://h.example/api/v1/blobs/$HASH/content", target.contentUrl)
        assertEquals(PlaybackTarget.Origin.DIRECT_PLANNED, target.origin)
        assertTrue(target.planned)
        assertTrue(target.seekable)
        assertEquals("all tracks decodable", target.reason)
    }

    @Test fun resolveBuildsAnUnseekableStreamTargetUnderTheSameCredential() {
        val t =
            RecordingTransport(
                HttpResponse(
                    200,
                    """{"mode":"stream","url":"/api/v1/playback/stream/abc","mime":"video/mp4","reason":"remux"}""",
                ),
            )
        val client = PlaybackClient(t, "https://h.example", Credential.Device("cert", "proof"))
        val target = client.resolve("asset-1", HASH, isVideo = true, mimeType = "video/x-msvideo", caps = CAPS)
        assertEquals("https://h.example/api/v1/playback/stream/abc", target.contentUrl)
        assertEquals("Device cert~proof", target.authHeaders()["Authorization"])
        assertEquals("video/mp4", target.mimeType)
        assertFalse(target.seekable)
        assertEquals(PlaybackTarget.Origin.STREAM, target.origin)
        assertEquals("remux", target.reason)
        // #433: the stream target is restart-seekable off the (start-less) token URL.
        assertTrue(target.restartSeekable)
        assertEquals("https://h.example/api/v1/playback/stream/abc", target.streamBaseUrl)
        assertEquals(0.0, target.streamStartSeconds, 0.0)
        assertEquals("https://h.example/api/v1/playback/stream/abc?start=45", target.atStreamStart(45.0).contentUrl)
    }

    @Test fun resolveTreatsAnUnknownModeAsDirect() {
        val t = RecordingTransport(HttpResponse(200, """{"mode":"transcode"}"""))
        val client = PlaybackClient(t, "https://h.example", Credential.Session("tok"))
        val target = client.resolve("asset-1", HASH, isVideo = true, mimeType = null, caps = CAPS)
        assertEquals(PlaybackTarget.Origin.DIRECT_UNPLANNED, target.origin)
        assertNull(target.mimeType)
    }

    @Test fun planUrlBuilder() {
        assertEquals("https://h.example/api/v1/playback/plan", PlaybackClient.planUrl("https://h.example/"))
    }
}
