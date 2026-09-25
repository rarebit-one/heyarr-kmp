package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.mobile.search.FollowedItemsJson
import one.rarebit.heyarr.mobile.search.FollowedSourceClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The #430 reads behind a followed source's detail: `GET /followed-sources/{id}` + `/items`. */
class FollowedSourceArchiveTest {

    private val base = "https://h.example"
    private val cred = Credential.Session("tok")

    @Test fun buildsTheRoutes() {
        assertEquals("$base/api/v1/followed-sources/s1", FollowedSourceClient.sourceUrl(base, "s1"))
        assertEquals("$base/api/v1/followed-sources/s1/items?limit=200", FollowedSourceClient.itemsUrl(base, "s1"))
        assertEquals(
            "$base/api/v1/followed-sources/s1/items?limit=200&cursor=c%2F2",
            FollowedSourceClient.itemsUrl(base, "s1", "c/2"),
        )
    }

    @Test fun parsesItemsWithAndWithoutAProjectedWant() {
        val body = """{"items":[
            {"id":"i1","work_id":"w1","edition_id":"e1","item_key":"S01E01","title":"Pilot","published_at":"2026-08-20T00:00:00Z","archived":true,"want":{"desired_item_id":"d1","phase":"complete","content":"satisfied","placement":"satisfied"},"created_at":"x"},
            {"id":"i2","work_id":"w1","item_key":"S00E00","title":"Old special","archived":false,"want":null,"created_at":"x"}
        ],"next_cursor":""}"""
        val items = FollowedItemsJson.parse(body)
        assertEquals(listOf("i1", "i2"), items.map { it.id })
        assertTrue(items[0].archived)
        assertEquals("d1", items[0].want?.desiredItemId)
        assertEquals("complete · satisfied · satisfied", items[0].want?.summary)
        assertFalse(items[1].archived)
        assertNull(items[1].want)
        assertNull(FollowedItemsJson.nextCursor(body)) // blank next_cursor → no more pages
    }

    @Test fun sourceReadReturnsGoneOn404AndFoundOn200() {
        val t = RoutedTransport(
            mapOf(
                "GET /followed-sources/s1" to
                    HttpResponse(
                        200,
                        """{"id":"s1","work_id":"w1","title":"Show","type":"tv_series","feed_ref":"tvdb:1","quality_profile_id":"qp","monitor":true,"backfill":"from_now","items_known":3,"items_archived":2,"health":"healthy","created_at":"x"}""",
                    ),
            ),
        )
        val c = FollowedSourceClient(t, base, cred)
        val found = c.source("s1")
        assertTrue(found is FollowedSourceClient.SourceResult.Found)
        assertEquals("Show", (found as FollowedSourceClient.SourceResult.Found).source.title)
        assertEquals(FollowedSourceClient.SourceResult.Gone, c.source("nope"))
    }

    @Test fun itemsPagesToTheEnd() {
        val t = RoutedTransport(
            mapOf(
                "GET /followed-sources/s1/items?limit=200" to
                    HttpResponse(200, """{"items":[{"id":"i1","title":"a","item_key":"S01E01"}],"next_cursor":"p2"}"""),
                "GET /followed-sources/s1/items?limit=200&cursor=p2" to
                    HttpResponse(200, """{"items":[{"id":"i2","title":"b","item_key":"S01E02"}]}"""),
            ),
        )
        assertEquals(listOf("i1", "i2"), FollowedSourceClient(t, base, cred).items("s1").map { it.id })
        assertEquals("Bearer tok", t.lastAuth)
    }
}
