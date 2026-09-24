package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.search.FollowedSourcesJson
import org.junit.Assert.assertEquals
import org.junit.Test

class FollowedSourcesJsonTest {

    @Test fun parsesFollowedSourcesFromEnvelopeWithCounters() {
        // Live shape: { "followed_sources": [ FollowedSourceView ] }; view carries work_id, no title.
        val body = """
            {"followed_sources":[
              {"id":"s1","work_id":"w1","type":"tv_series","items_known":10,"items_archived":8,"health":"healthy"},
              {"id":"s2","work_id":"w2","type":"tv_series","health":"unknown"}
            ]}
        """.trimIndent()
        val sources = FollowedSourcesJson.parse(body)
        assertEquals(2, sources.size)
        assertEquals("w1", sources[0].workId)
        assertEquals("w1", sources[0].title) // no title in the view → falls back to work_id
        assertEquals("tv_series", sources[0].type)
        assertEquals(10, sources[0].itemsKnown)
        assertEquals(8, sources[0].itemsArchived)
        assertEquals("healthy", sources[0].health)
        assertEquals(null, sources[1].itemsKnown)
    }

    @Test fun parserToleratesBareArrayAndEmpty() {
        assertEquals(1, FollowedSourcesJson.parse("""[{"id":"a","work_id":"w"}]""").size)
        assertEquals(0, FollowedSourcesJson.parse("""{"error":"x"}""").size)
    }
}
