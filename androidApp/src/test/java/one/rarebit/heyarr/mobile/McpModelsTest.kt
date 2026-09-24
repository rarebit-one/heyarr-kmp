package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.heyarr.CandidateJson
import one.rarebit.heyarr.core.heyarr.DesiredItemJson
import one.rarebit.heyarr.mobile.heyarr.HeyarrApi
import one.rarebit.heyarr.mobile.heyarr.McpResult
import one.rarebit.heyarr.core.heyarr.QualityProfileJson
import one.rarebit.heyarr.core.mcp.ExplanationJson
import one.rarebit.heyarr.core.mcp.PlaybackStatusJson
import one.rarebit.heyarr.core.mcp.ReleaseAttributes
import one.rarebit.heyarr.core.mcp.ReleaseToExplain
import one.rarebit.heyarr.core.mcp.RendererJson
import one.rarebit.heyarr.core.mcp.SatisfactionJson
import one.rarebit.heyarr.core.mcp.SearchHitsJson
import one.rarebit.heyarr.core.mcp.WantJson
import one.rarebit.heyarr.mobile.preview.FakeHeyarrTransport
import one.rarebit.heyarr.mobile.preview.Fixtures
import one.rarebit.heyarr.core.theme.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The typed readers over live-observed shapes — above all, that rule codes survive verbatim. */
class McpModelsTest {

    @Test
    fun explainReleaseKeepsEveryRuleCodeVerbatim() {
        val e = ExplanationJson.parse(Fixtures.explain)!!
        assertEquals("living-room", e.qualityProfile)
        assertEquals("r1", e.selected)
        assertEquals(listOf("r1", "r2"), e.ranked.map { it.id })
        val r1 = e.ranked[0]
        assertTrue(r1.accepted)
        assertEquals(listOf("resolution.gte", "source.nin", "video_codec.eq", "hdr.eq", "resolution.gte"), r1.reasons.map { it.rule })
        assertEquals(listOf("accept", "accept", "prefer", "prefer", "terminal"), r1.reasons.map { it.section })
        assertEquals("the provider could not determine hdr", r1.reasons[3].detail)
        assertTrue(r1.reasons[3].isUndetermined)
        val r2 = e.ranked[1]
        assertFalse(r2.accepted)
        assertEquals(listOf("resolution.gte", "source.nin"), r2.rejectedBy.map { it.rule })
        assertEquals("resolution 480, which is not at least 1080", r2.rejectedBy[0].detail)
    }

    @Test
    fun satisfactionReadsContentPlacementAndUpgrade() {
        val s = SatisfactionJson.parse(Fixtures.satisfaction("d-yellowstone"))!!
        assertEquals("FULLY_SATISFIED", s.state)
        assertEquals("satisfied", s.contentSatisfaction)
        assertTrue(s.placementUnproven)
        assertTrue(s.upgradeEligible)
        assertEquals(1, s.assets.size)
        val a = s.assets[0]
        assertTrue(a.accepted)
        assertEquals(7, a.reasons.size)
        assertEquals("pass", a.reasons[0].result)
        assertTrue(a.rejectedBy.isEmpty())

        val missing = SatisfactionJson.parse(Fixtures.satisfaction("x"))!!
        assertEquals("not_satisfied", missing.contentSatisfaction)
        assertTrue(missing.assets.isEmpty())
        assertEquals("nothing acceptable is held, so this is an acquisition rather than an upgrade", missing.upgradeDetail)
    }

    @Test
    fun candidatesCarryAcceptanceAndRejectingRules() {
        val c = CandidateJson.parse(Fixtures.candidates("d1"))!!
        assertEquals("d1", c.desiredItemId)
        assertEquals(2, c.candidates.size)
        assertTrue(c.candidates[0].accepted)
        assertTrue(c.candidates[0].selected)
        assertEquals(10, c.candidates[0].score)
        assertEquals(listOf("resolution.gte", "source.nin"), c.candidates[1].rejectedBy.map { it.rule })
    }

    @Test
    fun searchHitsReadWorksEpisodesAndArtwork() {
        val hits = SearchHitsJson.parse(Fixtures.searchContent("yellow", null))
        // The fixture also carries the download-folder variant the scanner mints (heyarr-core#470).
        assertEquals(listOf("Yellowstone", "Yellowstone Season 4 Mp4"), hits.works.map { it.title })
        assertEquals("/api/v1/blobs/${Fixtures.HASH}/content", hits.works[0].artworkPath)
        assertEquals(2018, hits.works[0].year)
        assertEquals(1, hits.episodes.size)
        assertEquals(Fixtures.YELLOWSTONE, hits.episodes[0].workId)
        assertEquals(Fixtures.HASH, hits.episodes[0].blobHash)
        val typed = SearchHitsJson.parse(Fixtures.searchContent("dune", "book"))
        assertEquals(listOf("Dune"), typed.works.map { it.title })
        assertEquals("Frank Herbert", typed.works[0].creator)
    }

    @Test
    fun wantsProfilesDesiredRenderersAndPlayback() {
        val wants = WantJson.list(Fixtures.missing)
        assertEquals(3, wants.size)
        assertEquals("living-room", wants[0].qualityProfile)
        assertEquals("MISSING", wants[0].state)
        assertNull(wants[2].reason)

        val profiles = QualityProfileJson.list(Fixtures.profiles)
        assertEquals(listOf("archival", "everyday", "living-room"), profiles.map { it.name })

        val desired = DesiredItemJson.list(Fixtures.desired)
        assertEquals(5, desired.size)
        assertEquals("fetching", desired[1].phase)
        assertEquals(true, desired[1].managed)
        assertEquals("1 candidate(s), none acceptable", desired[2].detail)

        val r = RendererJson.list(Fixtures.renderers)
        assertEquals("Devialet · Phantom II 95 dB", r[0].subtitle)

        val p = PlaybackStatusJson.parse(Fixtures.playback)!!
        assertTrue(p.playing)
        assertEquals(1425L, p.elapsedSeconds)
        assertEquals(5520L, p.durationSeconds)

        val bare = PlaybackStatusJson.parse("""{"elapsed_seconds":0,"playing":false,"renderer":"Phantom II 95 dB-a98d","state":"NO_MEDIA_PRESENT"}""")!!
        assertEquals("NO_MEDIA_PRESENT", bare.state)
        assertNull(bare.durationSeconds)
    }

    @Test
    fun releaseAttributesLeaveUnknownsOut() {
        val args = ReleaseToExplain("r", "t", ReleaseAttributes(resolution = 1080, source = "", hdr = null)).toArguments()
        @Suppress("UNCHECKED_CAST") val attrs = args["attributes"] as Map<String, Any?>
        assertEquals(1080, attrs["resolution"])
        assertNull(attrs["source"])
        assertNull(attrs["hdr"])
    }

    @Test
    fun theTypedDoorMapsToolsAndRestReadsOntoTheFixtureNode() {
        val http = FakeHeyarrTransport()
        val api = HeyarrApi(http, "https://node.example:7777", Credential.Session("tok"))
        assertEquals(listOf("Dune: Part Two"), api.searchContent("dune", MediaType.MOVIE).works.map { it.title })
        assertEquals(11, api.works().size)
        assertEquals("Yellowstone", api.work(Fixtures.YELLOWSTONE)?.title)
        assertEquals(Fixtures.HASH, api.work(Fixtures.YELLOWSTONE)?.blobHash)
        assertEquals(34, api.assets(Fixtures.YELLOWSTONE).size)
        assertEquals(5, api.desired().size)
        assertEquals(3, api.qualityProfiles().size)
        assertEquals(1, api.continueRail().size)
        assertEquals("23:45 / 55:12", api.continueRail()[0].progressLabel)
        assertEquals(3, api.followed().size)
        assertEquals(2, api.followedItems("fs-1").size)
        assertEquals(4, api.jobs().size)
        assertTrue(api.sessionInfo()!!.canWrite)
        assertEquals(3, api.providers().size)
        assertEquals(listOf("download", "ffmpeg", "ffmpeg.encoder.h264", "ffprobe", "indexer"), api.capabilities().available)
        // A refusal comes back as a value with the server's wording, naming the tool.
        val refused = api.discover("severance") as McpResult.Refused
        assertEquals("discover_content", refused.tool)
        assertTrue(refused.message.startsWith("no metadata provider is configured"))
        val acquire = api.acquire("d1", "infohash:cam") as McpResult.Refused
        assertTrue(acquire.message.contains("source.nin"))
        assertTrue(http.requests.any { it.first.endsWith("/api/v1/mcp") })
    }
}
