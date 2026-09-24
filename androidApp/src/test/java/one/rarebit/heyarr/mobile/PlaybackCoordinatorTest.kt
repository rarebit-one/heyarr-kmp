package one.rarebit.heyarr.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.mobile.playback.ClientCapabilities
import one.rarebit.heyarr.mobile.playback.PlaybackCoordinator
import one.rarebit.heyarr.mobile.playback.PlaybackDiagnostics
import one.rarebit.heyarr.mobile.playback.PlaybackTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The play/plan/re-plan logic that used to live untested inside the app ViewModel,
 * now over a scripted transport. Everything Unconfined so it settles inline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCoordinatorTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(dispatcher)
    private val base = "https://h.example"
    private val hash = "blake3:" + "a".repeat(64)
    private val caps = ClientCapabilities(containers = listOf("mp4"), video = listOf("h264"), audio = listOf("aac"), maxHeight = 1080)

    private fun coordinator(routes: Map<String, HttpResponse>, cred: Credential? = Credential.Session("tok")): Pair<PlaybackCoordinator, RoutedTransport> {
        val t = RoutedTransport(routes)
        return PlaybackCoordinator(t, { base }, { cred }, scope, dispatcher) to t
    }

    @Test fun aRowWithoutAHashIsANoticeNotAPlayer() {
        val (c, _) = coordinator(emptyMap())
        c.play(Work(id = "w1", title = "Dune"))
        assertNull(c.nowPlaying.value)
        assertTrue(c.notice.value!!.contains("Dune"))
        c.clearNotice()
        assertNull(c.notice.value)
    }

    @Test fun noCredentialMeansNothingHappens() {
        val (c, t) = coordinator(emptyMap(), cred = null)
        c.play(Work(id = "w1", title = "Dune", blobHash = hash))
        assertNull(c.nowPlaying.value)
        assertTrue(t.calls.isEmpty())
    }

    @Test fun aRowWithAHashAndNoCapabilitiesPlaysTheBlobDirectly() {
        val (c, t) = coordinator(emptyMap())
        c.play(Work(id = "w1", title = "Dune", blobHash = hash, mime = "video/mp4", primaryAssetId = "a1"))
        val np = c.nowPlaying.value!!
        assertEquals("$base/api/v1/blobs/$hash/content", np.target.contentUrl)
        assertEquals(PlaybackTarget.Origin.DIRECT_UNPLANNED, np.target.origin)
        assertTrue(np.target.isVideo)
        assertEquals("a1", np.assetId)
        assertTrue("no plan call without capabilities", t.calls.isEmpty())
    }

    @Test fun aRowWithAPrimaryAssetPlansAgainstCapabilities() {
        val (c, t) = coordinator(
            mapOf(
                "POST /playback/plan" to HttpResponse(200, """{"mode":"stream","url":"/api/v1/playback/stream/tok","mime":"video/mp4","reason":"audio"}"""),
            ),
        )
        c.capabilities = caps
        c.play(Work(id = "w1", title = "Dune", blobHash = hash, mime = "video/x-matroska", primaryAssetId = "a1"))
        val np = c.nowPlaying.value!!
        assertEquals(PlaybackTarget.Origin.STREAM, np.target.origin)
        assertEquals("$base/api/v1/playback/stream/tok", np.target.contentUrl)
        assertFalse(np.target.seekable)
        assertEquals(1, t.calls.count { it.first == "POST" })
    }

    @Test fun anOlderNodeFallsBackToTheBlob() {
        val (c, _) = coordinator(mapOf("POST /playback/plan" to HttpResponse(400, "{}")))
        c.capabilities = caps
        c.playAsset(Work(id = "w1", title = "Dune", kind = "movie"), WorkAsset(id = "a1", editionId = "e1", blobHash = hash, filename = "dune.mkv"))
        val np = c.nowPlaying.value!!
        assertEquals("$base/api/v1/blobs/$hash/content", np.target.contentUrl)
        assertEquals(PlaybackTarget.Origin.DIRECT_UNPLANNED, np.target.origin)
        assertEquals("Dune — dune.mkv", np.title)
    }

    @Test fun aLinkedAssetHasNothingToStream() {
        val (c, _) = coordinator(emptyMap())
        c.playAsset(Work(id = "w1", title = "Dune"), WorkAsset(id = "a1", editionId = "e1", blobHash = null, filename = "dune.epub"))
        assertNull(c.nowPlaying.value)
        assertTrue(c.notice.value!!.contains("dune.epub"))
    }

    @Test fun anIssueOnAPlannedDirectTargetReplansOnceAndOnlyOnce() {
        val (c, t) = coordinator(
            mapOf(
                "POST /playback/plan" to HttpResponse(200, """{"mode":"direct","reason":"fine"}"""),
            ),
        )
        c.capabilities = caps
        c.playAsset(Work(id = "w1", title = "Dune"), WorkAsset(id = "a1", editionId = "e1", blobHash = hash, mime = "video/mp4"))
        assertEquals(PlaybackTarget.Origin.DIRECT_PLANNED, c.nowPlaying.value!!.target.origin)
        val before = t.calls.size

        c.onIssue(PlaybackDiagnostics.Issue(message = "no decoder", type = 1, codec = "ac3"))
        val after = c.nowPlaying.value!!
        assertTrue(after.replanned)
        assertNotNull("the node still said direct: an honest banner, not a stream", after.banner)
        assertEquals(before + 1, t.calls.size)

        c.onIssue(PlaybackDiagnostics.Issue(message = "no decoder", type = 1, codec = "ac3"))
        assertEquals("a second issue does not re-plan again", before + 1, t.calls.size)
    }

    @Test fun anIssueOnAnUnplannedTargetIsIgnored() {
        val (c, t) = coordinator(emptyMap())
        c.capabilities = caps
        c.play(Work(id = "w1", title = "Dune", blobHash = hash)) // no primaryAssetId → unplanned
        c.onIssue(PlaybackDiagnostics.Issue(message = "no decoder", type = 1, codec = "ac3"))
        assertTrue(t.calls.isEmpty())
        assertFalse(c.nowPlaying.value!!.replanned)
    }

    @Test fun stopClearsThePlayer() {
        val (c, _) = coordinator(emptyMap())
        c.play(Work(id = "w1", title = "Dune", blobHash = hash))
        assertNotNull(c.nowPlaying.value)
        c.stop()
        assertNull(c.nowPlaying.value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCoordinatorResumeTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val hash = "blake3:" + "b".repeat(64)

    private class Spy : one.rarebit.heyarr.mobile.consumption.ProgressReporter {
        val events = ArrayList<String>()
        override fun begin(assetId: String, verb: String) {
            events.add("begin:$assetId:$verb")
        }
        override fun progressAt(pos: one.rarebit.heyarr.mobile.consumption.Position) {
            events.add("progress:${pos.locator}")
        }
        override fun pauseAt(pos: one.rarebit.heyarr.mobile.consumption.Position) {
            events.add("pause")
        }
        override fun resumeAt(pos: one.rarebit.heyarr.mobile.consumption.Position) {
            events.add("resume")
        }
        override fun endAt(pos: one.rarebit.heyarr.mobile.consumption.Position, completed: Boolean) {
            events.add("end:$completed")
        }
    }

    @Test fun aPlayLooksUpTheResumePositionAndOpensASession() {
        val spy = Spy()
        val c = PlaybackCoordinator(
            RoutedTransport(emptyMap()),
            { "https://h" },
            { Credential.Session("t") },
            CoroutineScope(dispatcher),
            dispatcher,
            reporter = spy,
            resumeAt = { id -> if (id == "a1") 1284.5 else null },
        )
        c.play(Work(id = "w1", title = "Arrival", blobHash = hash, mime = "video/mp4", primaryAssetId = "a1"))
        val np = c.nowPlaying.value!!
        assertEquals(1284.5, np.startSeconds, 0.0)
        assertEquals("watch", np.verb)
        assertEquals(listOf("begin:a1:watch"), spy.events)
        c.reportProgress(1300.0)
        c.reportEnded(1400.0, completed = false)
        assertEquals(listOf("begin:a1:watch", "progress:1300", "end:false"), spy.events)
    }

    @Test fun aKnownStartSkipsTheLookupAndAudioIsAListen() {
        val spy = Spy()
        var looked = false
        val c = PlaybackCoordinator(
            RoutedTransport(emptyMap()),
            { "https://h" },
            { Credential.Session("t") },
            CoroutineScope(dispatcher),
            dispatcher,
            reporter = spy,
            resumeAt = {
                looked = true
                5.0
            },
        )
        c.playFile("Track", "a9", hash, "audio/flac", "music", startSeconds = 42.0)
        assertEquals(42.0, c.nowPlaying.value!!.startSeconds, 0.0)
        assertEquals("listen", c.nowPlaying.value!!.verb)
        assertFalse(looked)
    }
}
