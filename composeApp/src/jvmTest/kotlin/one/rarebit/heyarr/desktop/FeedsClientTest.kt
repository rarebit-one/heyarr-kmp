package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.feeds.FollowedItemsJson
import one.rarebit.heyarr.core.feeds.FollowedSourcesJson
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.desktop.feeds.FeedsClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedsClientTest {

    @Test
    fun sourcesUrlIsBareRoute() {
        assertEquals("https://h.example/api/v1/followed-sources", FeedsClient.sourcesUrl("https://h.example/"))
    }

    @Test
    fun itemsUrlHasSourceIdLimitAndCursor() {
        assertEquals(
            "https://h.example/api/v1/followed-sources/fs1/items?limit=200",
            FeedsClient.itemsUrl("https://h.example", "fs1"),
        )
        assertTrue(FeedsClient.itemsUrl("https://h.example", "fs1", "n/x").endsWith("&cursor=n%2Fx"))
    }

    @Test
    fun parsesFollowedSourcesEnvelope() {
        val body = """
            {"followed_sources":[
              {"id":"fs1","work_id":"wk1","title":"Daring Fireball","type":"rss_feed",
               "items_known":240,"items_archived":12,"health":"healthy","feed_ref":"https://df.net/feed.xml"},
              {"source_id":"fs2","name":"Some Podcast","type":"podcast","items_known":5,"items_archived":5,"status":"unknown"}
            ]}
        """.trimIndent()
        val sources = FollowedSourcesJson.parse(body)
        assertEquals(2, sources.size)
        assertEquals("Daring Fireball", sources[0].title)
        assertEquals("rss_feed", sources[0].type)
        assertEquals(240, sources[0].itemsKnown)
        assertEquals(12, sources[0].itemsArchived)
        assertEquals("healthy", sources[0].health)
        // second row uses the alternate keys source_id/name/status
        assertEquals("fs2", sources[1].id)
        assertEquals("Some Podcast", sources[1].title)
        assertEquals("unknown", sources[1].health)
    }

    @Test
    fun parsesFollowedItemsAndArchivedFlag() {
        val body = """
            {"items":[
              {"id":"fi1","work_id":"wk1","edition_id":"ed1","item_key":"S02E05","title":"The Episode",
               "published_at":"2025-08-30T00:00:00Z","archived":true,
               "want":{"desired_item_id":"di1","phase":"acquired","content":"have","placement":"everywhere"}},
              {"id":"fi2","item_key":"GUID-123","archived":false}
            ],"next_cursor":"c9"}
        """.trimIndent()
        val items = FollowedItemsJson.parse(body)
        assertEquals(2, items.size)
        assertEquals("The Episode", items[0].title)
        assertEquals("wk1", items[0].workId)
        assertTrue(items[0].archived)
        // want.content == "have" must NOT be read as a top-level field — depth-1 only.
        // second item: title falls back to item_key, archived defaults false.
        assertEquals("GUID-123", items[1].title)
        assertFalse(items[1].archived)
        assertEquals("c9", FollowedItemsJson.nextCursor(body))
    }

    @Test
    fun listSourcesSendsBearer() {
        val seenAuth = ArrayList<String>()
        val transport = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>): HttpResponse {
                seenAuth += headers["Authorization"].orEmpty()
                return HttpResponse(200, """{"followed_sources":[{"id":"fs1","title":"X"}]}""")
            }
            override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) =
                HttpResponse(405, "")
        }
        val sources = FeedsClient(transport, "https://h.example", Credential.Bearer("heyarr_1_s")).listSources()
        assertEquals(1, sources.size)
        assertTrue(seenAuth.single() == "Bearer heyarr_1_s")
    }
}
