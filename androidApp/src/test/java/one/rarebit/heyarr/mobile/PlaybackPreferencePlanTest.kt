package one.rarebit.heyarr.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.core.playback.PlaybackPreference
import one.rarebit.heyarr.core.playback.QualityPreference
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.mobile.library.WorkDetailJson
import one.rarebit.heyarr.mobile.playback.ClientCapabilities
import one.rarebit.heyarr.mobile.playback.CopyPreferences
import one.rarebit.heyarr.mobile.playback.PlaybackClient
import one.rarebit.heyarr.mobile.playback.PlaybackCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The person's quality on the plan request (heyarr-core#887): resolved on the phone, sent as
 * `preference`, echoed by a node that applied it, and dropped for a node that refuses it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackPreferencePlanTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(dispatcher)
    private val base = "https://h.example"
    private val hash = "blake3:" + "a".repeat(64)
    private val caps = ClientCapabilities(
        containers = listOf("mp4"),
        video = listOf("h264"),
        audio = listOf("aac"),
        maxHeight = 1080,
        supportsHdr = true,
    )
    private val best = PlaybackPreference(QualityPreference.BEST)

    /** Answers each plan from [answer]; a node before #887 is one that 400s any `preference`. */
    private class Node(val answer: (String) -> HttpResponse) : HttpTransport {
        val bodies = ArrayList<String>()
        override fun get(url: String, headers: Map<String, String>) = HttpResponse(404, "{}")
        override fun post(
            url: String,
            body: String?,
            contentType: String?,
            headers: Map<String, String>,
        ): HttpResponse {
            bodies.add(body.orEmpty())
            return answer(body.orEmpty())
        }
    }

    private fun filmAssets(): List<WorkAsset> {
        val uhd = """{"id":"uhd","edition_id":"e1","role":"primary","blob_hash":"$hash",""" +
            """"filename":"Film.2160p.HDR.mkv","blob_mime":"video/x-matroska","blob_size":50000000000}"""
        val fhd = """{"id":"fhd","edition_id":"e2","role":"primary","blob_hash":"$hash",""" +
            """"filename":"Film.1080p.mkv","blob_mime":"video/x-matroska","blob_size":6000000000}"""
        return WorkDetailJson.parseAssets("""{"items":[$uhd,$fhd]}""")
    }

    private val film = Work(id = "w1", title = "Film", kind = "movie", blobHash = hash, primaryAssetId = "uhd")

    private fun coordinator(node: Node, preference: PlaybackPreference): PlaybackCoordinator {
        val c = PlaybackCoordinator(node, { base }, { Credential.Session("tok") }, scope, dispatcher)
        c.copies = CopyPreferences(preference = { preference })
        c.capabilities = caps
        return c
    }

    @Test fun theBodiesCarryThePreferenceButNotTheDefault() {
        val sent = caps.withPreference(PlaybackPreference(QualityPreference.DATA_SAVER, maxHeight = 1080))
        assertTrue(
            sent.planRequestBody("a1"),
            sent.planRequestBody(
                "a1",
            ).endsWith(""""max_height":1080},"preference":{"quality":"data_saver","max_height":1080}}"""),
        )
        assertTrue(sent.planByWorkBody("w1").contains(""""supports_hdr":true},"preference":{"quality":"data_saver""""))
        val default = caps.withPreference(PlaybackPreference())
        assertFalse("fit with no cap is what no field means", default.planRequestBody("a1").contains("preference"))
        assertFalse(caps.planByWorkBody("w1").contains("preference"))
    }

    @Test fun aNodeBeforeTheFieldIsAskedAgainWithoutIt() {
        val node = Node { body ->
            if (body.contains("\"preference\"")) {
                HttpResponse(400, """{"detail":"unknown field \"preference\""}""")
            } else {
                HttpResponse(200, """{"asset_id":"a1","mode":"direct"}""")
            }
        }
        val plan = PlaybackClient(node, base, Credential.Session("tok"))
            .planWork("w1", caps.withPreference(PlaybackPreference(QualityPreference.DATA_SAVER)))
        assertEquals(2, node.bodies.size)
        assertFalse("the retry sends no preference", node.bodies[1].contains("preference"))
        assertEquals("a1", plan.assetId)
        assertFalse("the node did not apply it", plan.preferenceApplied)
    }

    @Test fun bestQualityIsPlannedByFilmWhenTheNodeAppliesIt() {
        val chose = """{"asset_id":"uhd","mode":"direct","preference":{"quality":"best"},""" +
            """"selection":{"chosen":{"asset_id":"uhd"},"alternatives":[]}}"""
        val node = Node { HttpResponse(200, chose) }
        val c = coordinator(node, best)
        c.playCopy(film, c.copiesFor("w1", filmAssets(), "uhd")!!)
        val np = c.nowPlaying.value!!
        assertEquals("one plan, by film", 1, node.bodies.size)
        assertTrue(node.bodies[0].contains("\"work_id\":\"w1\""))
        assertTrue(node.bodies[0].contains(""""preference":{"quality":"best"}"""))
        assertEquals("uhd", np.assetId)
        assertEquals("a re-plan sends it again", best, np.preference)
    }

    @Test fun bestQualityFallsBackToThePhonesCopyWhenTheNodeDidNotApplyIt() {
        // A node after #885 but before #887 refuses the field; asked without it, it chooses by the
        // screen alone and says nothing of a preference.
        val node = Node { body ->
            when {
                body.contains("\"preference\"") -> HttpResponse(400, "{}")
                body.contains("\"work_id\"") -> HttpResponse(200, """{"asset_id":"fhd","mode":"direct"}""")
                else -> HttpResponse(200, """{"asset_id":"uhd","mode":"direct"}""")
            }
        }
        val c = coordinator(node, best)
        c.playCopy(film, c.copiesFor("w1", filmAssets(), "uhd")!!)
        assertEquals("the phone's own pick under best quality plays", "uhd", c.nowPlaying.value!!.assetId)
        assertTrue("…planned by asset", node.bodies.last().contains("\"asset_id\":\"uhd\""))
    }

    @Test fun aCopyPickedByHandSendsNoPreference() {
        val node = Node { HttpResponse(200, """{"mode":"direct"}""") }
        val c = coordinator(node, PlaybackPreference(QualityPreference.DATA_SAVER, maxHeight = 720))
        val choice = c.copiesFor("w1", filmAssets(), "uhd")!!
        val uhd = choice.copies.first { it.asset.id == "uhd" }
        c.playCopy(film, choice.copy(picked = uhd, chosen = true), chosen = true)
        assertEquals(1, node.bodies.size)
        assertTrue(node.bodies[0].contains("\"asset_id\":\"uhd\""))
        assertFalse("the picture the person asked for is not narrowed", node.bodies[0].contains("preference"))
        assertTrue(node.bodies[0].contains("\"max_height\":2160"))
    }

    @Test fun anEpisodeSendsThePreferenceAndARefreshSendsItAgain() {
        val node = Node { HttpResponse(200, """{"mode":"direct","preference":{"quality":"data_saver"}}""") }
        val saver = PlaybackPreference(QualityPreference.DATA_SAVER)
        val c = coordinator(node, saver)
        c.playFile("Ep 1", "a1", hash, "video/mp4", "series")
        c.refresh(atSeconds = 30.0, play = true)
        assertEquals(2, node.bodies.size)
        for (body in node.bodies) {
            assertTrue(body, body.contains(""""max_height":720},"preference":{"quality":"data_saver"}"""))
        }
    }
}
