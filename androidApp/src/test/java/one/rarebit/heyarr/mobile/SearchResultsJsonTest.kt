package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.search.SearchResultsJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultsJsonTest {

    @Test fun parsesWorksListWithTypeAndYear() {
        // The shape `GET /api/v1/works` returns: id, content_type, title, year.
        val body = """
            [
              {"id":"w1","content_type":"movie","title":"Dune","year":2021},
              {"id":"w2","content_type":"series","title":"Severance","year":2022}
            ]
        """.trimIndent()
        val results = SearchResultsJson.parse(body)
        assertEquals(2, results.size)
        assertEquals("w1", results[0].workId)
        assertEquals("Dune", results[0].title)
        assertEquals("movie", results[0].type)
        assertEquals(2021, results[0].year)
        assertEquals("series", results[1].type)
        assertEquals(2022, results[1].year)
    }

    @Test fun parsesWorksEnvelope() {
        val body = """{"works":[{"id":"a","title":"One","content_type":"podcast"}],"next":null}"""
        val results = SearchResultsJson.parse(body)
        assertEquals(1, results.size)
        assertEquals("a", results[0].workId)
        assertEquals("podcast", results[0].type)
    }

    @Test fun nullYearBecomesNull() {
        val results = SearchResultsJson.parse("""[{"id":"w","title":"T","year":null}]""")
        assertEquals(1, results.size)
        assertNull(results[0].year)
    }

    @Test fun titleFallsBackToNameThenId() {
        assertEquals("Blue Note", SearchResultsJson.parse("""[{"id":"x","name":"Blue Note"}]""")[0].title)
        assertEquals("only-id", SearchResultsJson.parse("""[{"id":"only-id"}]""")[0].title)
    }

    @Test fun skipsElementsWithoutId() {
        val results = SearchResultsJson.parse("""[{"title":"orphan"},{"id":"ok","title":"Kept"}]""")
        assertEquals(1, results.size)
        assertEquals("ok", results[0].workId)
    }

    @Test fun followableHintFollowsContentType() {
        val series = SearchResultsJson.parse("""[{"id":"s","content_type":"series"}]""")[0]
        val movie = SearchResultsJson.parse("""[{"id":"m","content_type":"movie"}]""")[0]
        assertTrue(series.followable)
        assertTrue(!movie.followable)
    }

    @Test fun emptyOnNonArray() {
        assertEquals(0, SearchResultsJson.parse("""{"error":"nope"}""").size)
    }
}

class SearchResultsJsonEpisodesTest {
    @Test fun parsesWorksWithPostersAndEpisodes() {
        val body = """{"works":[{"work_id":"w1","content_type":"movie","title":"Arrival","year":2016,"attributes":{"director":"V"},
                         "artwork":{"asset_id":"a5","blob_hash":"blake3:33","content_url":"/api/v1/blobs/blake3:33/content"}}],
                      "episodes":[
                        {"kind":"edition","id":"e8","work_id":"w8","work_title":"A Series","content_type":"series","title":"Pilot Light","season":1,"episode":1,
                         "primary_asset":{"asset_id":"a9","edition_id":"e8","blob_hash":"blake3:88","mime":"video/mp4"}},
                        {"kind":"item","id":"i8","work_id":"w8","work_title":"A Series","content_type":"series","title":"Pilot Returns","season":1,"episode":2}]}"""
        val hits = one.rarebit.heyarr.mobile.search.SearchResultsJson.parseHits(body)
        assertEquals(1, hits.works.size)
        assertEquals("/api/v1/blobs/blake3:33/content", hits.works[0].artworkPath)
        assertEquals(2, hits.episodes.size)
        val ed = hits.episodes[0]
        assertEquals("edition", ed.kind); assertEquals("S01E01", ed.code); assertEquals("a9", ed.assetId); assertEquals("blake3:88", ed.blobHash)
        assertEquals(true, ed.isPlayable)
        val item = hits.episodes[1]
        assertEquals("item", item.kind); assertEquals(false, item.isPlayable); assertEquals("S01E02", item.code)
    }

    @Test fun anOlderNodeHasNoEpisodes() {
        val hits = one.rarebit.heyarr.mobile.search.SearchResultsJson.parseHits("""{"works":[{"work_id":"w1","title":"X"}]}""")
        assertEquals(1, hits.works.size)
        assertEquals(0, hits.episodes.size)
        assertEquals(null, hits.works[0].artworkPath)
    }
}
