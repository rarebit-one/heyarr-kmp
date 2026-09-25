package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.discover.DiscoverClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class PostTransport(private val response: HttpResponse) : HttpTransport {
    var lastBody: String? = null
    var lastUrl: String? = null
    override fun get(url: String, headers: Map<String, String>) = HttpResponse(405, "")
    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse {
        lastUrl = url
        lastBody = body
        return response
    }
}

class DiscoverClientTest {

    private val base = "https://h.example"
    private val cred = Credential.Session("tok")

    @Test fun buildsTheRequest() {
        assertEquals("$base/api/v1/discover", DiscoverClient.discoverUrl("$base/"))
        assertEquals("""{"query":"the \"expanse\""}""", DiscoverClient.discoverBody("  the \"expanse\"  "))
    }

    @Test fun parsesCandidates() {
        val body = """{"results":[
            {"title":"The Expanse","year":2015,"type":"series","tvdb_id":"280619","overview":"Belters."},
            {"title":"No Id","type":"series"},
            {"year":1999}]}"""
        val t = PostTransport(HttpResponse(200, body))
        val out = DiscoverClient(t, base, cred).discover("expanse") as DiscoverClient.Outcome.Found
        assertEquals(2, out.results.size)
        assertEquals("280619", out.results[0].tvdbId)
        assertEquals(2015, out.results[0].year)
        assertEquals("tvdb:280619", out.results[0].key)
        assertEquals("tvdb:No Id", out.results[1].key)
        assertEquals("$base/api/v1/discover", t.lastUrl)
    }

    @Test fun anOlderNodeOrNoProviderIsUnavailableNotAnError() {
        assertTrue(
            DiscoverClient(
                PostTransport(HttpResponse(404, "")),
                base,
                cred,
            ).discover("x") is DiscoverClient.Outcome.Unavailable,
        )
        assertTrue(
            DiscoverClient(
                PostTransport(HttpResponse(503, """{"detail":"no metadata provider"}""")),
                base,
                cred,
            ).discover("x") is DiscoverClient.Outcome.Unavailable,
        )
        assertTrue(
            DiscoverClient(
                PostTransport(HttpResponse(500, "")),
                base,
                cred,
            ).discover("x") is DiscoverClient.Outcome.Failed,
        )
    }

    @Test fun blankQueryNeverHitsTheNetwork() {
        val t = PostTransport(HttpResponse(500, ""))
        val out = DiscoverClient(t, base, cred).discover("  ")
        assertTrue(out is DiscoverClient.Outcome.Found && out.results.isEmpty())
        assertEquals(null, t.lastUrl)
    }
}
