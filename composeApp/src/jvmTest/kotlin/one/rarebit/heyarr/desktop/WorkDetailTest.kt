package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.desktop.library.WorkDetailClient
import one.rarebit.heyarr.desktop.library.WorkDetailJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The work-detail read: parsing the `GET /api/v1/works/{id}` body (heyarr-core
 * `WorkDetail`, ADR-0075) into the work header + its inlined `primary_asset`, and the
 * detail URL. Same stance as [WorksJsonTest]: hand-rolled reader, no network.
 */
class WorkDetailTest {

    private val HASH = "blake3:" + "a".repeat(64)

    @Test
    fun parsesPrimaryAssetHashAndMime() {
        val body = """
            {
              "id": "w1",
              "title": "Blade Runner",
              "year": 1982,
              "content_type": "movie",
              "external_ids": {"imdb": "tt0083658"},
              "artwork": {"asset_id": "art1", "blob_hash": "$HASH", "mime": "image/jpeg",
                          "content_url": "/api/v1/blobs/$HASH/content"},
              "primary_asset": {
                "asset_id": "a1", "edition_id": "e1", "blob_hash": "$HASH",
                "mime": "video/mp4", "size": 4294967296, "duration_seconds": 6900.0,
                "content_url": "/api/v1/blobs/$HASH/content"
              }
            }
        """.trimIndent()

        val detail = WorkDetailJson.parse(body)!!
        assertEquals("Blade Runner", detail.work.title)
        assertEquals(1982, detail.work.year)
        assertTrue(detail.isPlayable)
        val asset = detail.primaryAsset!!
        // The hash comes from primary_asset's OWN slice, not the artwork embed.
        assertEquals(HASH, asset.blobHash)
        assertEquals("video/mp4", asset.mime)
        assertEquals("a1", asset.assetId)
        assertEquals("e1", asset.editionId)
        assertEquals(4294967296L, asset.sizeBytes)
    }

    @Test
    fun nullPrimaryAssetIsNotPlayable() {
        val body = """{"id":"w2","title":"No File","primary_asset":null}"""
        val detail = WorkDetailJson.parse(body)!!
        assertEquals("No File", detail.work.title)
        assertNull(detail.primaryAsset)
        assertTrue(!detail.isPlayable)
    }

    @Test
    fun absentPrimaryAssetIsNotPlayable() {
        val body = """{"id":"w3","title":"Older Node"}"""
        val detail = WorkDetailJson.parse(body)!!
        assertNull(detail.primaryAsset)
        assertTrue(!detail.isPlayable)
    }

    @Test
    fun linkedAssetWithoutBlobHashIsNotPlayable() {
        // A `linked` asset with no bytes (ADR-0020) cannot be played.
        val body = """{"id":"w4","title":"Linked","primary_asset":{"asset_id":"a1","edition_id":"e1"}}"""
        val detail = WorkDetailJson.parse(body)!!
        assertNull(detail.primaryAsset)
    }

    @Test
    fun detailUrlEncodesId() {
        assertEquals(
            "https://h.example/api/v1/works/w%2F1",
            WorkDetailClient.workUrl("https://h.example/", "w/1"),
        )
    }

    @Test
    fun clientSendsBearerAndReturns404AsNull() {
        val seenAuth = ArrayList<String>()
        val transport = object : HttpTransport {
            override fun get(url: String, headers: Map<String, String>): HttpResponse {
                seenAuth += headers["Authorization"].orEmpty()
                return if (url.endsWith("/missing")) {
                    HttpResponse(404, "")
                } else {
                    HttpResponse(200, """{"id":"w1","title":"One","primary_asset":{"blob_hash":"$HASH"}}""")
                }
            }
            override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) =
                HttpResponse(405, "")
        }
        val client = WorkDetailClient(transport, "https://h.example", Credential.Bearer("heyarr_1_secret"))

        val ok = client.getWorkDetail("w1")!!
        assertEquals(HASH, ok.primaryAsset!!.blobHash)
        assertNull(client.getWorkDetail("missing"))
        assertTrue(seenAuth.all { it == "Bearer heyarr_1_secret" })
    }
}
