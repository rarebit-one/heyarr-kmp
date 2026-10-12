package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.WorksJson
import org.junit.Assert.assertEquals
import org.junit.Test

class WorksJsonTest {

    @Test fun parsesBareArray() {
        val body = """
            [
              {"id":"w1","title":"Dune","media_type":"movie"},
              {"id":"w2","name":"Blue Note","kind":"album"}
            ]
        """.trimIndent()
        val works = WorksJson.parse(body)
        assertEquals(2, works.size)
        assertEquals("w1", works[0].id)
        assertEquals("Dune", works[0].title)
        assertEquals("movie", works[0].kind)
        // Falls back to `name` for the title, and reads `kind`.
        assertEquals("Blue Note", works[1].title)
        assertEquals("album", works[1].kind)
    }

    @Test fun parsesItemsEnvelope() {
        val body = """{"items":[{"id":"a","title":"One"}],"next":null}"""
        val works = WorksJson.parse(body)
        assertEquals(1, works.size)
        assertEquals("a", works[0].id)
        assertEquals("One", works[0].title)
    }

    @Test fun skipsElementsWithoutId() {
        val body = """[{"title":"orphan"},{"id":"ok","title":"Kept"}]"""
        val works = WorksJson.parse(body)
        assertEquals(1, works.size)
        assertEquals("ok", works[0].id)
    }

    @Test fun titleFallsBackToIdWhenAbsent() {
        val works = WorksJson.parse("""[{"id":"only-id"}]""")
        assertEquals(1, works.size)
        assertEquals("only-id", works[0].title)
    }

    @Test fun toleratesNestedBracesInStrings() {
        // A nested object + a bracket inside a string must not fool the splitter.
        val body = """[{"id":"w","title":"A [weird] title","meta":{"x":"y"}}]"""
        val works = WorksJson.parse(body)
        assertEquals(1, works.size)
        assertEquals("A [weird] title", works[0].title)
    }

    @Test fun emptyOnNonArray() {
        assertEquals(0, WorksJson.parse("""{"error":"nope"}""").size)
    }

    @Test fun parsesOptionalPlaybackHandles() {
        val body = """
            [
              {"id":"w1","title":"Dune","media_type":"movie","blob_hash":"deadbeef","mime":"video/mp4"},
              {"id":"w2","title":"No Asset"}
            ]
        """.trimIndent()
        val works = WorksJson.parse(body)
        assertEquals("deadbeef", works[0].blobHash)
        assertEquals("video/mp4", works[0].mime)
        assertEquals(true, works[0].isPlayable)
        // A row without a hash is not directly playable.
        assertEquals(null, works[1].blobHash)
        assertEquals(false, works[1].isPlayable)
    }

    @Test fun parsesExternalIdsOnTheSingleWorkRead() {
        // GET /works/{id} inlines external_ids (#431); the list read carries none.
        val one = WorksJson.parseOne(
            """{"id":"w1","content_type":"movie","work_key":"k","title":"Dune","sort_title":"dune","year":2021,"external_ids":{"tmdb":"438631","imdb":"tt1160419"},"attributes":{},"created_at":"x","updated_at":"y"}""",
        )!!
        assertEquals(mapOf("tmdb" to "438631", "imdb" to "tt1160419"), one.externalIds)
        // No external_ids on a plain list row → empty, not an error.
        assertEquals(emptyMap<String, String>(), WorksJson.parse("""[{"id":"w2","title":"No ids"}]""")[0].externalIds)
    }
}

class WorksJsonEmbedsTest {
    @Test fun readsTheBrowseEmbedsFromTheirOwnSlices() {
        val body = """[{"id":"w1","title":"T","content_type":"music",
            "attributes":{"artist":"Artist A","author":"nope"},
            "artwork":{"asset_id":"a5","blob_hash":"blake3:33","content_url":"/api/v1/blobs/blake3:33/content"},
            "primary_asset":{"asset_id":"a1","blob_hash":"blake3:11","mime":"audio/flac"}}]"""
        val w = one.rarebit.heyarr.mobile.library.WorksJson.parse(body).single()
        assertEquals("a1", w.primaryAssetId)
        assertEquals("blake3:11", w.blobHash)
        assertEquals("audio/flac", w.mime)
        assertEquals("/api/v1/blobs/blake3:33/content", w.artworkPath)
        assertEquals("Artist A", w.artist)
        assertEquals("nope", w.author)
    }

    @Test fun aTopLevelHashStillWinsOverTheEmbed() {
        val body = """[{"id":"w1","title":"T","blob_hash":"blake3:top","primary_asset":{"asset_id":"a1","blob_hash":"blake3:11"}}]"""
        val w = one.rarebit.heyarr.mobile.library.WorksJson.parse(body).single()
        assertEquals("blake3:top", w.blobHash)
        assertEquals("a1", w.primaryAssetId)
    }

    @Test fun nullEmbedsReadAsAbsent() {
        val body = """[{"id":"w1","title":"T","artwork":null,"primary_asset":null,"attributes":{}}]"""
        val w = one.rarebit.heyarr.mobile.library.WorksJson.parse(body).single()
        assertEquals(null, w.primaryAssetId)
        assertEquals(null, w.artworkPath)
        assertEquals(emptyMap<String, String>(), w.artworkSizes)
        assertEquals(null, w.artist)
    }
}

class WorksJsonSizesTest {
    private val sized = Work(
        id = "w1",
        title = "T",
        artworkPath = "/fb",
        artworkSizes = mapOf("256" to "u256", "512" to "u512", "1024" to "u1024"),
    )

    @Test fun parsesSizesFromArtworkEmbed() {
        // `sizes` maps bucket widths to redirect paths; `content_url` still fills artworkPath.
        // JsonScan reads one line, so the fixture is joined, not wrapped.
        val body = "[{\"id\":\"w1\",\"title\":\"T\",\"artwork\":{\"content_url\":\"/blobs/b1/content\"," +
            "\"sizes\":{\"256\":\"/a?size=256\",\"512\":\"/a?size=512\",\"1024\":\"/a?size=1024\"}}}]"
        val w = WorksJson.parse(body).single()
        assertEquals("/blobs/b1/content", w.artworkPath)
        assertEquals(mapOf("256" to "/a?size=256", "512" to "/a?size=512", "1024" to "/a?size=1024"), w.artworkSizes)
    }

    @Test fun artworkForSelectsCorrectBucket() {
        assertEquals("exact bucket", "u256", sized.artworkFor(256))
        assertEquals("rounds up to the next bucket", "u512", sized.artworkFor(300))
        assertEquals("exact bucket", "u512", sized.artworkFor(512))
        assertEquals("larger than every bucket takes the largest", "u1024", sized.artworkFor(2048))
    }

    @Test fun artworkForFallsBackToArtworkPathWhenSizesAbsent() {
        val plain = Work(id = "w1", title = "T", artworkPath = "/fb")
        assertEquals("/fb", plain.artworkFor(256))
        assertEquals("/fb", plain.artworkFor(1024))
        assertEquals(null, Work(id = "w1", title = "T").artworkFor(256))
    }

    @Test fun artworkSizesIsEmptyWhenSizesKeyAbsentFromEmbed() {
        val body = "[{\"id\":\"w1\",\"title\":\"T\",\"artwork\":{\"content_url\":\"/blobs/b1/content\"}}]"
        val w = WorksJson.parse(body).single()
        assertEquals(emptyMap<String, String>(), w.artworkSizes)
        assertEquals("/blobs/b1/content", w.artworkFor(256))
    }
}
