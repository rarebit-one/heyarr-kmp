package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.library.LibraryClient
import one.rarebit.heyarr.mobile.library.WorkDetailClient
import one.rarebit.heyarr.mobile.library.WorkPatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A scripted transport: each URL (path + query) maps to a canned response; every call
 * is recorded so tests can assert the exact routes, methods, bodies and auth.
 */
internal class RoutedTransport(private val routes: Map<String, HttpResponse>) : HttpTransport {
    val calls = ArrayList<Triple<String, String, String?>>() // method, url, body
    var lastAuth: String? = null
        private set

    private fun answer(method: String, url: String, body: String?, headers: Map<String, String>): HttpResponse {
        calls.add(Triple(method, url, body))
        lastAuth = headers["Authorization"]
        val path = url.substringAfter("/api/v1")
        return routes["$method $path"] ?: routes[path]
            ?: HttpResponse(404, """{"status":404,"detail":"no such route in the fake"}""")
    }

    override fun get(url: String, headers: Map<String, String>) = answer("GET", url, null, headers)
    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) =
        answer("POST", url, body, headers)
    override fun delete(url: String, headers: Map<String, String>) = answer("DELETE", url, null, headers)
    override fun patch(url: String, body: String?, contentType: String?, headers: Map<String, String>) =
        answer("PATCH", url, body, headers)
}

class WorkDetailClientTest {

    private val base = "https://h.example"
    private val cred = Credential.Session("tok")

    @Test fun buildsTheLiveRoutes() {
        assertEquals("$base/api/v1/works/w1/assets?limit=200", WorkDetailClient.workAssetsUrl(base, "w1"))
        assertEquals(
            "$base/api/v1/works/w1/assets?limit=200&cursor=a%2Fb",
            WorkDetailClient.workAssetsUrl(base, "w1", "a/b"),
        )
        assertEquals("$base/api/v1/works/w1", WorkDetailClient.workUrl(base, "w1"))
        assertEquals("$base/api/v1/assets/a1", WorkDetailClient.assetUrl(base, "a1"))
        assertEquals("$base/api/v1/desired?work_id=w1&limit=200", WorkDetailClient.wantsUrl(base, "w1"))
        assertEquals("$base/api/v1/desired/d1", WorkDetailClient.wantUrl(base, "d1"))
        assertEquals("""{"monitor":false}""", WorkDetailClient.monitorBody(false))
        assertEquals("$base/api/v1/works?limit=200&include=artwork%2Cprimary_asset", LibraryClient.worksUrl(base, null))
        assertEquals(
            "$base/api/v1/works?limit=200&include=artwork%2Cprimary_asset&cursor=c1",
            LibraryClient.worksUrl(base, "c1"),
        )
    }

    @Test fun assetsForWorkReadsTheJoinedPerWorkRoute() {
        // #429: GET /works/{id}/assets returns joined WorkAssets — edition label + blob
        // size + blob mime inline, no /editions or /blobs fan-out. Paged by next_cursor.
        val t = RoutedTransport(
            mapOf(
                "GET /works/w1/assets?limit=200" to HttpResponse(
                    200,
                    """{"items":[
                    {"id":"a1","edition_id":"e1","source_class":"managed","blob_hash":"blake3:aa","role":"primary","filename":"one.mkv","edition_label":"1080p","edition_type":"release","blob_size":2048,"blob_mime":"video/x-matroska","identification_source":"scan","created_at":"2026-09-01T00:00:00Z","updated_at":"2026-09-01T00:00:00Z"}
                ],"next_cursor":"p2"}""",
                ),
                "GET /works/w1/assets?limit=200&cursor=p2" to HttpResponse(
                    200,
                    """{"items":[
                    {"id":"a3","edition_id":"e1","source_class":"linked","blob_hash":null,"role":"subtitle","filename":"one.srt","edition_label":"1080p","edition_type":"release","blob_size":null,"blob_mime":null,"identification_source":"scan","created_at":"2026-09-01T00:00:00Z","updated_at":"2026-09-01T00:00:00Z"}
                ]}""",
                ),
            ),
        )
        val assets = WorkDetailClient(t, base, cred).assetsForWork("w1")
        assertEquals(listOf("a1", "a3"), assets.map { it.id })
        assertEquals("1080p", assets[0].editionLabel)
        assertEquals(2048L, assets[0].sizeBytes)
        assertEquals("video/x-matroska", assets[0].mime)
        assertEquals(null, assets[1].sizeBytes)
        // No edition/blob fan-out at all — only the two page reads.
        assertEquals(2, t.calls.size)
        assertEquals(0, t.calls.count { it.second.contains("/editions/") })
        assertEquals(0, t.calls.count { it.second.contains("/blobs/") })
        assertEquals("Bearer tok", t.lastAuth)
    }

    @Test fun wantsForWorkFiltersByWorkIdAndIsRecentFirst() {
        val t = RoutedTransport(
            mapOf(
                "GET /desired?work_id=w1&limit=200" to HttpResponse(
                    200,
                    """{"items":[
                    {"id":"old","scope":"work","work_id":"w1","quality_profile_id":"q","monitor":true,"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"},
                    {"id":"new","scope":"work","work_id":"w1","quality_profile_id":"q","monitor":true,"created_at":"2026-02-01T00:00:00Z","updated_at":"2026-02-01T00:00:00Z"}
                ]}""",
                ),
            ),
        )
        assertEquals(listOf("new", "old"), WorkDetailClient(t, base, cred).wantsForWork("w1").map { it.id })
    }

    @Test fun writesHitTheRealRoutesWithTheRightMethods() {
        val t = RoutedTransport(
            mapOf(
                "DELETE /desired/d1" to HttpResponse(204, ""),
                "PATCH /desired/d1" to
                    HttpResponse(
                        200,
                        """{"id":"d1","scope":"work","work_id":"w1","quality_profile_id":"q","monitor":false,"created_at":"x","updated_at":"y"}""",
                    ),
                "POST /desired/d1/reconcile" to
                    HttpResponse(202, """{"desired_item_id":"d1","job_id":"j1","status":"queued"}"""),
                "POST /desired/d1/search" to HttpResponse(202, """{"job_id":"j2"}"""),
                "DELETE /assets/a1" to HttpResponse(204, ""),
            ),
        )
        val c = WorkDetailClient(t, base, cred)
        assertTrue(c.cancelWant("d1") is WorkDetailClient.Outcome.Done)
        assertTrue(c.setMonitor("d1", false) is WorkDetailClient.Outcome.Done)
        assertTrue(c.reconcile("d1") is WorkDetailClient.Outcome.Done)
        assertTrue(c.searchAgain("d1") is WorkDetailClient.Outcome.Done)
        assertTrue(c.removeAsset("a1") is WorkDetailClient.Outcome.Done)
        assertEquals(
            listOf(
                "DELETE /desired/d1",
                "PATCH /desired/d1",
                "POST /desired/d1/reconcile",
                "POST /desired/d1/search",
                "DELETE /assets/a1",
            ),
            t.calls.map { it.first + " " + it.second.substringAfter("/api/v1") },
        )
        assertEquals("""{"monitor":false}""", t.calls[1].third)
    }

    @Test fun editWorkPatchesTheWorkAndCarriesTheBody() {
        val t = RoutedTransport(
            mapOf(
                "PATCH /works/w1" to
                    HttpResponse(
                        200,
                        """{"id":"w1","content_type":"movie","work_key":"k","title":"The Conversation","sort_title":"conversation, the","year":1974,"attributes":{},"created_at":"x","updated_at":"y"}""",
                    ),
            ),
        )
        val out = WorkDetailClient(t, base, cred).editWork("w1", WorkPatch(title = "The Conversation", year = 1974))
        assertTrue(out is WorkDetailClient.Outcome.Done)
        assertEquals("PATCH", t.calls.single().first)
        assertTrue(t.calls.single().second.endsWith("/works/w1"))
        assertEquals("""{"title":"The Conversation","year":1974}""", t.calls.single().third)
    }

    @Test fun deleteWorkHitsTheRouteAndSurfacesTheFollowedSource409Verbatim() {
        val detail = "this work is still followed — stop following it first (DELETE /followed-sources/s1)"
        val t = RoutedTransport(
            mapOf(
                "DELETE /works/w1" to HttpResponse(204, ""),
                "DELETE /works/w2" to HttpResponse(409, """{"status":409,"detail":"$detail"}"""),
            ),
        )
        val c = WorkDetailClient(t, base, cred)
        assertTrue(c.deleteWork("w1") is WorkDetailClient.Outcome.Done)
        val refused = c.deleteWork("w2")
        assertTrue(refused is WorkDetailClient.Outcome.Refused)
        assertEquals(409, (refused as WorkDetailClient.Outcome.Refused).status)
        assertEquals(detail, refused.message)
    }

    @Test fun a403IsTheHonestReadOnlyHintAndA400CarriesTheDetail() {
        val t = RoutedTransport(
            mapOf(
                "DELETE /desired/d1" to
                    HttpResponse(403, """{"status":403,"detail":"this token does not carry the write scope"}"""),
                "DELETE /assets/a1" to HttpResponse(404, """{"status":404,"detail":"asset not found"}"""),
                "POST /desired/d1/reconcile" to HttpResponse(500, """{"status":500}"""),
            ),
        )
        val c = WorkDetailClient(t, base, cred)
        val ro = c.cancelWant("d1")
        assertTrue(ro is WorkDetailClient.Outcome.ReadOnly)
        assertEquals(WorkDetailClient.READ_ONLY_HINT, (ro as WorkDetailClient.Outcome.ReadOnly).message)
        val gone = c.removeAsset("a1")
        assertTrue(gone is WorkDetailClient.Outcome.Refused)
        assertEquals("asset not found", (gone as WorkDetailClient.Outcome.Refused).message)
        val boom = c.reconcile("d1")
        assertTrue(boom is WorkDetailClient.Outcome.Failed)
        assertEquals("retry failed: HTTP 500", (boom as WorkDetailClient.Outcome.Failed).message)
    }

    @Test fun libraryListFollowsNextCursorAndOrdersRecentFirst() {
        val t = RoutedTransport(
            mapOf(
                "GET /works?limit=200&include=artwork%2Cprimary_asset" to
                    HttpResponse(
                        200,
                        """{"items":[{"id":"a","title":"A","updated_at":"2026-01-01T00:00:00Z"}],"next_cursor":"n"}""",
                    ),
                "GET /works?limit=200&include=artwork%2Cprimary_asset&cursor=n" to
                    HttpResponse(200, """{"items":[{"id":"b","title":"B","updated_at":"2026-05-01T00:00:00Z"}]}"""),
            ),
        )
        assertEquals(listOf("b", "a"), LibraryClient(t, base, cred).listWorks().map { it.id })
        assertEquals(2, t.calls.size)
    }
}
