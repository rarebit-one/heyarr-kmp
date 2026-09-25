package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.playback.PlaybackClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class RangeTransport(private val status: Int) : HttpTransport {
    var lastRange: String? = null
        private set
    var lastAuth: String? = null
        private set
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        lastRange = headers["Range"]
        lastAuth = headers["Authorization"]
        return HttpResponse(status, "")
    }
    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse =
        HttpResponse(405, "")
}

class PlaybackUrlTest {

    private val hash = "blake3:a617353faeec5b99c042ceb349976d8171e5b7efc6fb1924fbc8b0d605ad8c9e"

    @Test fun blobContentUrlIsRangeCapablePath() {
        assertEquals(
            "https://h.example/api/v1/blobs/$hash/content",
            PlaybackClient.blobContentUrl("https://h.example/", hash),
        )
    }

    /** Regression: a percent-encoded colon (`blake3%3A…`) is a 400 at the node. */
    @Test fun blobContentUrlKeepsTheColonVerbatim() {
        val url = PlaybackClient.blobContentUrl("http://192.168.16.224:7777", hash)
        assertEquals(true, url.contains("/blobs/blake3:a617"))
        assertEquals(false, url.contains("%3A"))
    }

    @Test fun blobContentUrlRefusesAnythingButABlake3Hash() {
        for (bad in listOf(
            "deadbeef",
            "blake3:DEADBEEF",
            "blake3:zz",
            "sha256:" + "a".repeat(64),
            "blake3:" + "a".repeat(63) + "/x",
        )) {
            try {
                PlaybackClient.blobContentUrl("https://h.example", bad)
                throw AssertionError("accepted $bad")
            } catch (
                e: IllegalArgumentException,
            ) { /* expected */ }
        }
    }

    @Test fun playbackUrl() {
        assertEquals("https://h.example/api/v1/playback", PlaybackClient.playbackUrl("https://h.example"))
    }

    @Test fun probeSendsRangeAndAuthAndReadsPartialContent() {
        val t = RangeTransport(status = 206)
        val client = PlaybackClient(t, "https://h.example", Credential.Session("tok"))
        val result = client.probe("blake3:a617353faeec5b99c042ceb349976d8171e5b7efc6fb1924fbc8b0d605ad8c9e")
        assertEquals("bytes=0-0", t.lastRange)
        assertEquals("Bearer tok", t.lastAuth)
        assertTrue(result.acceptsRanges)
    }

    @Test fun probeReportsNoRangeSupportOn200() {
        val t = RangeTransport(status = 200)
        val client = PlaybackClient(t, "https://h.example", Credential.Session("tok"))
        assertTrue(
            !client.probe("blake3:a617353faeec5b99c042ceb349976d8171e5b7efc6fb1924fbc8b0d605ad8c9e").acceptsRanges,
        )
    }
}
