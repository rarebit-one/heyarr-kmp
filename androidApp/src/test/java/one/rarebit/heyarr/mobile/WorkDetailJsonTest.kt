package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.mobile.library.WorkDetailJson
import one.rarebit.heyarr.mobile.library.WorksJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The detail screen's readers against the live heyarr-core shapes (internal/api/resources/model.go, desired.go). */
class WorkDetailJsonTest {

    @Test fun parsesTheAssetsPage() {
        val body = """{"items":[
            {"id":"a1","edition_id":"e1","library_id":"l1","source_class":"managed","blob_hash":"blake3:ab","source_path":"/m/Dune (2021)/Dune.mkv","role":"primary","filename":"Dune.mkv","mime":"video/x-matroska","identification_source":"scan","missing_since":null,"created_at":"2026-09-01T10:00:00Z","updated_at":"2026-09-01T10:00:00Z"},
            {"id":"a2","edition_id":"e2","library_id":null,"source_class":"linked","blob_hash":null,"source_path":"/x/y.srt","role":"subtitle","filename":"y.srt","mime":null,"identification_source":"scan","missing_since":"2026-08-30T00:00:00Z","created_at":"2026-08-01T00:00:00Z","updated_at":"2026-08-30T00:00:00Z"}
        ],"next_cursor":"YXNzZXRzOmEy"}"""
        val assets = WorkDetailJson.parseAssets(body)
        assertEquals(2, assets.size)
        assertEquals("blake3:ab", assets[0].blobHash)
        assertEquals("video/x-matroska", assets[0].mime)
        assertTrue(assets[0].isPlayable)
        assertFalse(assets[0].isMissing)
        assertNull("a linked asset has no blob", assets[1].blobHash)
        assertFalse(assets[1].isPlayable)
        assertTrue(assets[1].isMissing)
        assertEquals("subtitle", assets[1].role)
        assertEquals("YXNzZXRzOmEy", WorkDetailJson.nextCursor(body))
        assertNull(WorkDetailJson.nextCursor("""{"items":[]}"""))
    }

    @Test fun parsesEditionAndBlobSize() {
        val e = WorkDetailJson.parseEdition(
            """{"id":"e1","work_id":"w1","label":"1080p BluRay","edition_type":"release","language":null,"attributes":{},"created_at":"2026-09-01T10:00:00Z"}""",
        )!!
        assertEquals("w1", e.workId)
        assertEquals("1080p BluRay", e.label)
        assertEquals(
            4_294_967_296L,
            WorkDetailJson.parseBlobSize(
                """{"hash":"blake3:ab","size":4294967296,"mime":"video/x-matroska","chunked":false,"chunk_manifest":"not_required","first_seen_at":"2026-09-01T10:00:00Z"}""",
            ),
        )
        assertNull(WorkDetailJson.parseBlobSize("""{"status":404}"""))
    }

    @Test fun parsesWantsWithAcquisitionFacts() {
        val body = """{"items":[
            {"id":"d1","scope":"work","work_id":"w1","quality_profile_id":"qp1","monitor":true,"reason":"rewatch","acquisition":{"state":"CONTENT_SATISFIED","phase":"complete","managed":true,"content":"satisfied","placement":"converging","detail":"1 of 2 peers"},"created_at":"2026-09-01T10:00:00Z","updated_at":"2026-09-02T10:00:00Z"},
            {"id":"d2","scope":"edition","work_id":"w1","edition_id":"e9","quality_profile_id":"qp1","monitor":false,"created_at":"2026-09-02T12:00:00Z","updated_at":"2026-09-02T12:00:00Z"}
        ]}"""
        val wants = WorkDetailJson.parseWants(body)
        assertEquals(2, wants.size)
        val d1 = wants[0]
        assertEquals("CONTENT_SATISFIED", d1.state)
        assertEquals("complete", d1.phase)
        assertEquals(true, d1.managed)
        assertEquals("converging", d1.placement)
        assertEquals("1 of 2 peers", d1.detail)
        assertEquals("rewatch", d1.reason)
        assertTrue(d1.monitor)
        assertEquals("CONTENT_SATISFIED · complete · monitored", d1.status)
        val d2 = wants[1]
        assertEquals("e9", d2.editionId)
        assertFalse(d2.monitor)
        assertNull("no acquisition row → no state, not a crash", d2.state)
        // recentFirst: d2 (12:00 on the 2nd) before d1 (10:00 on the 2nd).
        assertEquals(listOf("d2", "d1"), WorkDetailJson.recentFirst(wants).map { it.id })
    }

    @Test fun parsesASingleWantFromAPatchResponse() {
        val w = WorkDetailJson.parseWant(
            """{"id":"d1","scope":"work","work_id":"w1","quality_profile_id":"qp1","monitor":false,"created_at":"2026-09-01T10:00:00Z","updated_at":"2026-09-02T10:00:00Z"}""",
        )!!
        assertFalse(w.monitor)
        assertNull(WorkDetailJson.parseWant("[]"))
    }

    @Test fun worksJsonReadsTheLiveWorkShapeAndPaging() {
        val body = """{"items":[{"id":"w1","content_type":"movie","work_key":"movie:dune:2021","title":"Dune","sort_title":"dune","year":2021,"attributes":{},"created_at":"2026-08-01T00:00:00Z","updated_at":"2026-09-01T00:00:00Z"}],"next_cursor":"d29ya3M6ZHVuZQ"}"""
        val w = WorksJson.parse(body).single()
        assertEquals("movie", w.kind)
        assertEquals(2021, w.year)
        assertEquals("movie:dune:2021", w.workKey)
        assertEquals("2026-09-01T00:00:00Z", w.recency)
        assertFalse("the live view inlines no blob", w.isPlayable)
        assertEquals("d29ya3M6ZHVuZQ", WorksJson.nextCursor(body))
        val one = WorksJson.parseOne("""{"id":"w2","title":"Arrival","year":null}""")!!
        assertNull(one.year)
    }

    @Test fun formatsBytesHumanly() {
        assertEquals("512 B", WorkAsset.formatBytes(512))
        assertEquals("1.0 KB", WorkAsset.formatBytes(1024))
        assertEquals("1.5 MB", WorkAsset.formatBytes(1_572_864))
        assertEquals("4.0 GB", WorkAsset.formatBytes(4_294_967_296))
        assertEquals("812 MB", WorkAsset.formatBytes(851_443_712))
    }

    @Test fun qualityLineSkipsDefaults() {
        val a =
            WorkAsset(
                id = "a",
                editionId = "e",
                role = "primary",
                mime = "video/mp4",
                sourceClass = "managed",
                sizeBytes = 1024,
                editionLabel = "720p",
            )
        assertEquals("720p · video/mp4 · 1.0 KB", a.quality)
        val b = WorkAsset(id = "b", editionId = "e", role = "subtitle", sourceClass = "vault")
        assertEquals("subtitle · vault", b.quality)
    }
}
