package one.rarebit.heyarr.desktop.vault

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.crypto.Blake3
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fake-transport tests for the vault sync pipe: correct routes/bodies, content-addressed
 * ids computed with the vault's BLAKE3, base64 ciphertext, and response parsing. No live
 * node (CI has none) — the transport is a programmable fake.
 */
class VaultSpaceClientTest {

    private class Req(val method: String, val url: String, val body: String?)

    private class FakeTransport(
        val responses: MutableMap<String, HttpResponse> = mutableMapOf(),
    ) : HttpTransport {
        val requests = ArrayList<Req>()
        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            requests.add(Req("GET", url, null))
            return responses["GET $url"] ?: HttpResponse(404, "")
        }
        override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse {
            requests.add(Req("POST", url, body))
            return responses["POST $url"] ?: HttpResponse(500, "")
        }
        override fun delete(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse {
            requests.add(Req("DELETE", url, body))
            return responses["DELETE $url"] ?: HttpResponse(500, "")
        }
    }

    private val base = "https://node.example:7777"
    private val cred = Credential.Bearer("heyarr_x_secret")
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    @Test
    fun pushChangeContentAddressesAndPosts() {
        val ct = byteArrayOf(1, 2, 3, 4, 5)
        val id = Blake3.hashHex(ct)
        val fake = FakeTransport()
        val url = VaultSpaceClient.changesUrl(base, "space-1")
        fake.responses["POST $url"] = HttpResponse(201, """{"change_id":"$id"}""")

        val client = VaultSpaceClient(fake, base, cred)
        val returned = client.pushChange("space-1", listOf("blake3:aa", "blake3:bb"), ct)

        assertEquals(id, returned)
        val req = fake.requests.single()
        assertEquals("POST", req.method)
        assertEquals(url, req.url)
        val body = req.body!!
        assertTrue(body.contains(""""space_id":"space-1""""), "body has space_id: $body")
        assertTrue(body.contains(""""change_id":"$id""""), "body has content-addressed id: $body")
        assertTrue(body.contains(""""ciphertext":"${b64(ct)}""""), "body has base64 ciphertext: $body")
    }

    @Test
    fun pullChangesParses() {
        val ct = byteArrayOf(9, 8, 7)
        val fake = FakeTransport()
        val url = VaultSpaceClient.changesUrl(base, "s")
        fake.responses["GET $url"] = HttpResponse(
            200,
            """{"space_id":"s","changes":[{"space_id":"s","change_id":"blake3:cc","parents":["blake3:aa"],"ciphertext":"${b64(ct)}"}]}""",
        )
        val changes = VaultSpaceClient(fake, base, cred).pullChanges("s")
        assertEquals(1, changes.size)
        assertEquals("blake3:cc", changes[0].changeId)
        assertEquals(listOf("blake3:aa"), changes[0].parents)
        assertTrue(ct.contentEquals(changes[0].ciphertext))
    }

    @Test
    fun getSnapshotNullOn404AndParsesOn200() {
        val fake = FakeTransport()
        val url = VaultSpaceClient.snapshotUrl(base, "s")
        assertNull(VaultSpaceClient(fake, base, cred).getSnapshot("s"))

        val ct = byteArrayOf(4, 4, 4)
        fake.responses["GET $url"] =
            HttpResponse(200, """{"space_id":"s","snapshot_id":"blake3:dd","frontier":["blake3:cc"],"ciphertext":"${b64(ct)}"}""")
        val snap = VaultSpaceClient(fake, base, cred).getSnapshot("s")!!
        assertEquals("blake3:dd", snap.snapshotId)
        assertEquals(listOf("blake3:cc"), snap.frontier)
        assertTrue(ct.contentEquals(snap.ciphertext))
    }

    @Test
    fun pinPlacementPostsPair() {
        val fake = FakeTransport()
        val url = VaultSpaceClient.placementsUrl(base)
        fake.responses["POST $url"] = HttpResponse(200, """{"blob_hash":"blake3:ee","peer_id":"cove"}""")
        VaultSpaceClient(fake, base, cred).pinPlacement("blake3:ee", "cove")
        val body = fake.requests.single().body!!
        assertTrue(body.contains(""""blob_hash":"blake3:ee""""), body)
        assertTrue(body.contains(""""peer_id":"cove""""), body)
    }

    @Test
    fun unpinPlacementDeletesPair() {
        val fake = FakeTransport()
        val url = VaultSpaceClient.placementsUrl(base)
        fake.responses["DELETE $url"] = HttpResponse(204, "")
        VaultSpaceClient(fake, base, cred).unpinPlacement("blake3:ee", "cove")
        val req = fake.requests.single()
        assertEquals("DELETE", req.method)
        assertTrue(req.body!!.contains(""""blob_hash":"blake3:ee""""), req.body!!)
        assertTrue(req.body!!.contains(""""peer_id":"cove""""), req.body!!)
    }

    @Test
    fun listKeysParses() {
        val w = byteArrayOf(1, 1, 2, 3)
        val fake = FakeTransport()
        val url = VaultSpaceClient.keysUrl(base, "s")
        fake.responses["GET $url"] =
            HttpResponse(200, """{"space_id":"s","wrapped_keys":[{"recipient":"x25519:ab","wrapped":"${b64(w)}","created_at":"t"}]}""")
        val keys = VaultSpaceClient(fake, base, cred).listKeys("s")
        assertEquals(1, keys.size)
        assertEquals("x25519:ab", keys[0].recipient)
        assertTrue(w.contentEquals(keys[0].wrapped))
    }
}
