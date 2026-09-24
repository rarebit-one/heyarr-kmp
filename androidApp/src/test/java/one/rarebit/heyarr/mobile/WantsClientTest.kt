package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.acquisition.CandidatesJson
import one.rarebit.heyarr.mobile.acquisition.WantsClient
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WantsClientTest {

    private val base = "https://h.example"
    private val cred = Credential.Session("tok")

    private val candidatesBody = """{"desired_item_id":"i1","search_id":"search-1","selected":"good","candidates":[
      {"candidate_id":"good","provider":"fake-indexer","title":"Arrival 2160p remux","accepted":true,"score":30,"terminal":true,"selected":true,
       "reasons":[{"rule":"resolution.gte","section":"accept","result":"pass","detail":"resolution 2160, which is at least 1080"}]},
      {"candidate_id":"tiny","provider":"fake-indexer","title":"Arrival 480p cam","accepted":false,"score":0,"terminal":false,"selected":false,
       "reasons":[{"rule":"resolution.gte","section":"accept","result":"fail","detail":"resolution 480, which is not at least 1080"}],
       "rejected_by":[{"rule":"resolution.gte","section":"accept","result":"fail","detail":"resolution 480, which is not at least 1080"}]},
      {"provider":"x"}]}"""

    @Test fun urlsAndBody() {
        assertEquals("$base/api/v1/desired/i%3A1/candidates", WantsClient.candidatesUrl(base, "i:1"))
        assertEquals("$base/api/v1/desired/i1/select", WantsClient.selectUrl(base, "i1"))
        assertEquals("""{"candidate_id":"good"}""", WantsClient.selectBody("good"))
    }

    @Test fun parsesCandidatesWithVerdicts() {
        val set = CandidatesJson.parse(candidatesBody)
        assertEquals("search-1", set.searchId)
        assertEquals("good", set.selectedId)
        assertEquals(2, set.candidates.size)
        val good = set.candidates[0]
        assertTrue(good.accepted && good.selected && good.terminal)
        assertEquals(30, good.score)
        assertEquals("resolution.gte — resolution 2160, which is at least 1080", good.reasons.single().line)
        val tiny = set.candidates[1]
        assertFalse(tiny.accepted)
        assertEquals(1, tiny.rejectedBy.size)
        assertEquals("fail", tiny.rejectedBy[0].result)
    }

    @Test fun candidatesAndSelectOverTheWire() {
        val t = RoutedTransport(mapOf(
            "GET /desired/i1/candidates" to HttpResponse(200, candidatesBody),
            "POST /desired/i1/select" to HttpResponse(200, "{}"),
            "GET /desired/i9/candidates" to HttpResponse(404, ""),
        ))
        val c = WantsClient(t, base, cred)
        assertEquals(2, c.candidates("i1").candidates.size)
        assertEquals(0, c.candidates("i9").candidates.size)
        assertEquals(WantsClient.SelectOutcome.Done, c.select("i1", "good"))
        assertTrue(t.calls.last().third!!.contains("\"good\""))
    }

    @Test fun aRejectedPickIsRefusedByName() {
        val t = RoutedTransport(mapOf("POST /desired/i1/select" to HttpResponse(400, """{"detail":"candidate tiny is rejected by resolution.gte"}""")))
        val out = WantsClient(t, base, cred).select("i1", "tiny")
        assertTrue(out is WantsClient.SelectOutcome.Refused && out.message.contains("resolution.gte"))
        val ro = WantsClient(RoutedTransport(mapOf("POST /desired/i1/select" to HttpResponse(403, ""))), base, cred).select("i1", "good")
        assertTrue(ro is WantsClient.SelectOutcome.Refused && ro.status == 403)
    }
}
