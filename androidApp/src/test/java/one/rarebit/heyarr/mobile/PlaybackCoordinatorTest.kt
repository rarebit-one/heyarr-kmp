package one.rarebit.heyarr.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.core.playback.InMemoryResumeStore
import one.rarebit.heyarr.core.playback.QualityPreference
import one.rarebit.heyarr.core.playback.ResumeItem
import one.rarebit.heyarr.core.playback.ResumePoint
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.mobile.library.WorkDetailJson
import one.rarebit.heyarr.mobile.playback.ClientCapabilities
import one.rarebit.heyarr.mobile.playback.CopyPreferences
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
    private val caps =
        ClientCapabilities(containers = listOf("mp4"), video = listOf("h264"), audio = listOf("aac"), maxHeight = 1080)

    private fun coordinator(
        routes: Map<String, HttpResponse>,
        cred: Credential? = Credential.Session("tok"),
    ): Pair<PlaybackCoordinator, RoutedTransport> {
        val t = RoutedTransport(routes)
        return PlaybackCoordinator(t, { base }, { cred }, scope, dispatcher) to t
    }

    @Test fun aPhoneScreenPicksTheTenEightyCopyOverTheOldestFourKCopy() {
        // heyarr-kmp#225's acceptance case, off the wire shape `GET /works/{id}/assets` sends.
        // Concatenated, so the JSON is still one line (JsonScan is a field scanner, not a parser).
        val uhd = """{"id":"uhd","edition_id":"e1","role":"primary","blob_hash":"$hash",""" +
            """"filename":"Film.2160p.HDR.mkv","blob_mime":"video/x-matroska","blob_size":50000000000,""" +
            """"edition_label":"Remux-2160p"}"""
        val fhd = """{"id":"fhd","edition_id":"e2","role":"primary","blob_hash":"$hash",""" +
            """"filename":"Film.1080p.mkv","blob_mime":"video/x-matroska","blob_size":6000000000,""" +
            """"edition_label":"WEBDL-1080p"}"""
        val sub = """{"id":"sub","edition_id":"e2","role":"subtitle","blob_hash":"$hash",""" +
            """"filename":"Film.en.srt","blob_mime":"text/plain","edition_label":"WEBDL-1080p"}"""
        val assets = WorkDetailJson.parseAssets("""{"items":[$uhd,$fhd,$sub]}""")
        val (c, _) = coordinator(emptyMap())
        fun picked() = c.copiesFor("w1", assets, defaultId = "uhd")?.picked?.asset?.id
        // Before the probe: the cautious 1080-line guess.
        assertEquals("unprobed phone takes 1080p", "fhd", picked())
        c.capabilities = caps.copy(maxHeight = 1080, supportsHdr = true)
        assertEquals("1080-line HDR phone takes 1080p", "fhd", picked())
        assertEquals("two copies, the subtitle is not one", 2, c.copiesFor("w1", assets, "uhd")!!.copies.size)
        c.capabilities = caps.copy(maxHeight = 2160, supportsHdr = true)
        assertEquals("2160-line HDR screen takes 2160p", "uhd", picked())
        assertNull("a work without video copies plays its primary", c.copiesFor("w1", emptyList(), defaultId = "uhd"))
    }

    private fun filmAssets(): List<WorkAsset> {
        val uhd = """{"id":"uhd","edition_id":"e1","role":"primary","blob_hash":"$hash",""" +
            """"filename":"Film.2160p.HDR.mkv","blob_mime":"video/x-matroska","blob_size":50000000000}"""
        val fhd = """{"id":"fhd","edition_id":"e2","role":"primary","blob_hash":"$hash",""" +
            """"filename":"Film.1080p.mkv","blob_mime":"video/x-matroska","blob_size":6000000000}"""
        return WorkDetailJson.parseAssets("""{"items":[$uhd,$fhd]}""")
    }

    /** A node that answers a plan by what the body names: a film (`work_id`) or a file (`asset_id`). */
    private class PlanNode(val byWork: HttpResponse, val byAsset: HttpResponse) : HttpTransport {
        val bodies = ArrayList<String>()
        override fun get(url: String, headers: Map<String, String>) = HttpResponse(404, "{}")
        override fun post(
            url: String,
            body: String?,
            contentType: String?,
            headers: Map<String, String>,
        ): HttpResponse {
            bodies.add(body.orEmpty())
            return if (body.orEmpty().contains("\"work_id\"")) byWork else byAsset
        }
    }

    private val film = Work(id = "w1", title = "Film", kind = "movie", blobHash = hash, primaryAssetId = "uhd")

    private fun playFilm(node: PlanNode): PlaybackCoordinator {
        val c = PlaybackCoordinator(node, { base }, { Credential.Session("tok") }, scope, dispatcher)
        c.capabilities = caps.copy(maxHeight = 1080, supportsHdr = true)
        c.playCopy(film, c.copiesFor("w1", filmAssets(), "uhd")!!)
        return c
    }

    @Test fun aNodeThatDoesNotPlanByFilmGetsTheCopyThePhoneChose() {
        // heyarr-core before #885 refuses `work_id` (and the unknown `supports_hdr`) with a 400.
        val node = PlanNode(HttpResponse(400, "{}"), HttpResponse(200, """{"mode":"direct"}"""))
        val np = playFilm(node).nowPlaying.value!!
        assertEquals("the node was asked to choose first, then about the phone's copy", 2, node.bodies.size)
        assertTrue("the film, with the screen's HDR", node.bodies[0].contains("\"supports_hdr\":true"))
        assertTrue("…and the screen's height", node.bodies[0].contains("\"max_height\":1080"))
        assertTrue("then the 1080p copy by asset", node.bodies[1].contains("\"asset_id\":\"fhd\""))
        assertFalse("an asset plan never sends supports_hdr", node.bodies[1].contains("supports_hdr"))
        assertEquals("the 1080p copy plays", "fhd", np.assetId)
        assertEquals("the player can offer both copies", 2, np.copies.size)
        assertEquals("fit this screen declares the screen", 1080, np.maxHeight)
    }

    @Test fun aNodeThatPlansByFilmChoosesTheCopy() {
        // The node knows what the phone cannot (each copy's codec) and chose the other copy.
        // Concatenated, so the JSON is still one line (JsonScan is a field scanner, not a parser).
        val chose = """{"asset_id":"uhd","mode":"stream","url":"/api/v1/playback/stream/tok1","mime":"video/mp4",""" +
            """"selection":{"chosen":{"asset_id":"uhd"},"alternatives":[]}}"""
        val node = PlanNode(HttpResponse(200, chose), HttpResponse(500, "{}"))
        val np = playFilm(node).nowPlaying.value!!
        assertEquals("one plan, by film", 1, node.bodies.size)
        assertEquals("the copy the node chose plays", "uhd", np.assetId)
        assertEquals("…and the Version menu says so", "uhd", np.copy?.asset?.id)
        assertEquals("on the node's stream", "$base/api/v1/playback/stream/tok1", np.target.contentUrl)
    }

    @Test fun aCopyThePhoneDoesNotHoldFallsBackToTheCopyItChose() {
        val node = PlanNode(
            HttpResponse(200, """{"asset_id":"elsewhere","mode":"direct"}"""),
            HttpResponse(200, """{"mode":"direct"}"""),
        )
        val np = playFilm(node).nowPlaying.value!!
        assertEquals("fhd", np.assetId)
        assertTrue(node.bodies.last().contains("\"asset_id\":\"fhd\""))
    }

    @Test fun theVersionMenuSwitchesAtThePositionAndIsRemembered() {
        val (c, t) = coordinator(mapOf("POST /playback/plan" to HttpResponse(200, """{"mode":"direct"}""")))
        c.capabilities = caps.copy(maxHeight = 1080, supportsHdr = true)
        val film = Work(id = "w1", title = "Film", kind = "movie", blobHash = hash, primaryAssetId = "uhd")
        c.playCopy(film, c.copiesFor("w1", filmAssets(), "uhd")!!)
        val uhd = c.nowPlaying.value!!.copies.first { it.asset.id == "uhd" }
        c.switchCopy(uhd, atSeconds = 754.0)
        val np = c.nowPlaying.value!!
        assertEquals("the 2160p copy plays", "uhd", np.assetId)
        assertEquals("from where the 1080p copy was", 754.0, np.startSeconds, 0.001)
        assertEquals("the copies survive the switch", 2, np.copies.size)
        assertTrue("a chosen copy is not downscaled", t.calls.last().third!!.contains("\"max_height\":2160"))
        assertEquals(
            "the choice is remembered for the work",
            "uhd",
            c.copiesFor("w1", filmAssets(), "uhd")?.picked?.asset?.id,
        )
        assertTrue("…and marked as chosen", c.copiesFor("w1", filmAssets(), "uhd")!!.chosen)
    }

    @Test fun theDataSaverPreferenceCapsEveryPlan() {
        val t = RoutedTransport(mapOf("POST /playback/plan" to HttpResponse(200, """{"mode":"direct"}""")))
        val c = PlaybackCoordinator(t, { base }, { Credential.Session("tok") }, scope, dispatcher)
        c.copies = CopyPreferences(preference = { QualityPreference.DATA_SAVER })
        c.capabilities = caps.copy(maxHeight = 1080)
        c.playFile("Ep 1", "a1", hash, "video/mp4", "series")
        assertTrue("data saver declares 720 lines", t.calls.single().third!!.contains("\"max_height\":720"))
    }

    @Test fun aRowWithoutAHashIsANoticeNotAPlayer() {
        val (c, _) = coordinator(emptyMap())
        c.play(Work(id = "w1", title = "Dune"))
        assertNull(c.nowPlaying.value)
        assertTrue(c.notice.value!!.contains("Dune"))
        c.clearNotice()
        assertNull(c.notice.value)
    }

    @Test fun nullCredentialPlaysAsGuest() {
        // After sign-out (credential == null) heyarr's trusted-network guest path applies
        // (ADR-0074): the blob endpoint returns 200 for guests, so pressing Play must produce
        // a NowPlaying — it used to silently no-op.
        val (c, t) = coordinator(emptyMap(), cred = null)
        c.play(Work(id = "w1", title = "Dune", blobHash = hash))
        val np = c.nowPlaying.value
        assertNotNull("null credential falls back to Credential.Guest, producing a NowPlaying", np)
        assertEquals("null credential gives Credential.Guest target", Credential.Guest, np!!.target.credential)
        assertTrue("Credential.Guest sends no Authorization header", np.target.authHeaders().isEmpty())
        assertTrue("direct blob needs no plan call", t.calls.isEmpty())
    }

    @Test fun enrolledCredentialIsPreservedInTarget() {
        // A signed-in session credential must survive the play path unchanged so the blob
        // and any re-plan calls carry the correct Authorization header.
        val cred = Credential.Session("tok")
        val (c, _) = coordinator(emptyMap(), cred = cred)
        c.play(Work(id = "w1", title = "Dune", blobHash = hash))
        val np = c.nowPlaying.value!!
        assertEquals(
            "enrolled Session credential is carried through to the target unchanged",
            cred,
            np.target.credential,
        )
        assertFalse("enrolled credential has an Authorization header", np.target.authHeaders().isEmpty())
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
                "POST /playback/plan" to
                    HttpResponse(
                        200,
                        """{"mode":"stream","url":"/api/v1/playback/stream/tok","mime":"video/mp4","reason":"audio"}""",
                    ),
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
        c.playAsset(
            Work(id = "w1", title = "Dune", kind = "movie"),
            WorkAsset(id = "a1", editionId = "e1", blobHash = hash, filename = "dune.mkv"),
        )
        val np = c.nowPlaying.value!!
        assertEquals("$base/api/v1/blobs/$hash/content", np.target.contentUrl)
        assertEquals(PlaybackTarget.Origin.DIRECT_UNPLANNED, np.target.origin)
        assertEquals("Dune — dune.mkv", np.title)
    }

    @Test fun aLinkedAssetHasNothingToStream() {
        val (c, _) = coordinator(emptyMap())
        c.playAsset(
            Work(id = "w1", title = "Dune"),
            WorkAsset(id = "a1", editionId = "e1", blobHash = null, filename = "dune.epub"),
        )
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
        c.playAsset(
            Work(id = "w1", title = "Dune"),
            WorkAsset(id = "a1", editionId = "e1", blobHash = hash, mime = "video/mp4"),
        )
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

    // ── Resume (heyarr-kmp#232) ──────────────────────────────────────────────────────

    private fun leftAt(assetId: String, seconds: Double, at: Long, workId: String? = "w1") =
        ResumePoint(ResumeItem(assetId, workId, "Film"), seconds, 6000.0, at)

    @Test fun aPlayWithNoPositionResumesWhereThisPhoneLeftIt() {
        val (c, _) = coordinator(emptyMap())
        c.resume = InMemoryResumeStore().apply { put(leftAt("a1", 1439.0, 1)) }
        c.play(Work(id = "w1", title = "Film", blobHash = hash, mime = "video/mp4", primaryAssetId = "a1"))
        assertEquals("resumed by default", 1439.0, c.nowPlaying.value!!.startSeconds, 0.0)
        assertEquals("filed under its work", "w1", c.nowPlaying.value!!.workId)
    }

    @Test fun startOverAndAnExplicitPositionWinOverTheResumePoint() {
        val (c, _) = coordinator(emptyMap())
        c.resume = InMemoryResumeStore().apply { put(leftAt("ep2", 900.0, 1)) }
        c.playFile("Show — S01E02", "ep2", hash, "video/mp4", "series", startSeconds = 0.0, workId = "show")
        assertEquals("Start over", 0.0, c.nowPlaying.value!!.startSeconds, 0.0)
        c.playFile("Show — S01E02", "ep2", hash, "video/mp4", "series", startSeconds = 120.0, workId = "show")
        assertEquals(120.0, c.nowPlaying.value!!.startSeconds, 0.0)
        c.playFile("Show — S01E03", "ep3", hash, "video/mp4", "series", workId = "show")
        assertEquals("another episode starts at the beginning", 0.0, c.nowPlaying.value!!.startSeconds, 0.0)
    }

    @Test fun aFilmResumesOnTheCopyItPlaysWhereverItWasLeft() {
        val node = PlanNode(HttpResponse(400, "{}"), HttpResponse(200, """{"mode":"direct"}"""))
        val c = PlaybackCoordinator(node, { base }, { Credential.Session("tok") }, scope, dispatcher)
        c.capabilities = caps.copy(maxHeight = 1080, supportsHdr = true)
        // Left on the 2160p copy on another evening; this phone now plays the 1080p one.
        c.resume = InMemoryResumeStore().apply { put(leftAt("uhd", 2000.0, 5)) }
        c.playCopy(film, c.copiesFor("w1", filmAssets(), "uhd")!!)
        val np = c.nowPlaying.value!!
        assertEquals("fhd", np.assetId)
        assertEquals("the film's point, from its other copy", 2000.0, np.startSeconds, 0.0)
        c.playCopy(film, c.copiesFor("w1", filmAssets(), "uhd")!!, startSeconds = 0.0)
        assertEquals("Start over", 0.0, c.nowPlaying.value!!.startSeconds, 0.0)
    }

    @Test fun aRestoreAfterProcessDeathPutsTheSameFileBackPausedWhereItWas() {
        val (c, _) = coordinator(emptyMap())
        val item = ResumeItem("ep2", "show", "Show — S01E02", "series", hash, "video/mp4", "https://h.example/art")
        c.restore(ResumePoint(item, 754.0, 2700.0, 1))
        val np = c.nowPlaying.value!!
        assertEquals("ep2", np.assetId)
        assertEquals("where it was", 754.0, np.startSeconds, 0.0)
        assertTrue("paused, in the bar", np.startPaused)
        assertEquals("show", np.workId)
        assertEquals("Show — S01E02", np.title)
        assertEquals("https://h.example/art", np.artworkUrl)
        assertTrue(
            "planned afresh: the blob of the same file",
            np.target.contentUrl.contains(hash.removePrefix("blake3:")),
        )
        c.playFile("Show — S01E02", "ep2", hash, "video/mp4", "series", workId = "show")
        assertFalse("an ordinary play plays", c.nowPlaying.value!!.startPaused)
    }

    @Test fun everyPlayIsANewRequestSoStartOverTwiceIsTwoPlays() {
        val (c, _) = coordinator(emptyMap())
        c.playFile("Film", "a1", hash, "video/mp4", "movie", startSeconds = 0.0)
        val first = c.nowPlaying.value!!
        c.playFile("Film", "a1", hash, "video/mp4", "movie", startSeconds = 0.0)
        val second = c.nowPlaying.value!!
        assertTrue("not swallowed by the state flow as an equal value", first != second)
        assertEquals(first.copy(request = second.request), second)
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

    @Test fun aPlayStartsAtTheBeginningAndOpensASession() {
        val spy = Spy()
        val c = PlaybackCoordinator(
            RoutedTransport(emptyMap()),
            { "https://h" },
            { Credential.Session("t") },
            CoroutineScope(dispatcher),
            dispatcher,
            reporter = spy,
        )
        c.play(Work(id = "w1", title = "Arrival", blobHash = hash, mime = "video/mp4", primaryAssetId = "a1"))
        val np = c.nowPlaying.value!!
        assertEquals(0.0, np.startSeconds, 0.0)
        assertEquals("watch", np.verb)
        assertEquals(listOf("begin:a1:watch"), spy.events)
        c.reportProgress(1300.0)
        c.reportEnded(1400.0, completed = false)
        assertEquals(listOf("begin:a1:watch", "progress:1300", "end:false"), spy.events)
    }

    @Test fun anExplicitPlaybackOffsetIsKeptAndAudioIsAListen() {
        val spy = Spy()
        val c = PlaybackCoordinator(
            RoutedTransport(emptyMap()),
            { "https://h" },
            { Credential.Session("t") },
            CoroutineScope(dispatcher),
            dispatcher,
            reporter = spy,
        )
        c.playFile("Track", "a9", hash, "audio/flac", "music", startSeconds = 42.0)
        assertEquals(42.0, c.nowPlaying.value!!.startSeconds, 0.0)
        assertEquals("listen", c.nowPlaying.value!!.verb)
    }
}
