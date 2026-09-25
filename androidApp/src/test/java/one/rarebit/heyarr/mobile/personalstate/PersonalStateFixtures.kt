package one.rarebit.heyarr.mobile.personalstate

import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.core.net.JsonScan
import java.util.Base64

/**
 * A deterministic in-memory stand-in for heyarr-core's personal-state routes, used
 * to drive [SpaceSession] end-to-end in a pure-JVM test. It re-derives the
 * content-addressed change id on `POST /changes` exactly as the node does and
 * refuses a mismatch — so a test that stores a minted change proves the id is the
 * one the node would accept, not just internally consistent.
 */
internal class FakeServer(val base: String = "https://node.test") : HttpTransport {
    private class Space(val kind: String) {
        val keys = LinkedHashMap<String, ByteArray>() // recipient -> wrapped
        val changes = ArrayList<EncryptedChange>()
    }

    private val spaces = LinkedHashMap<String, Space>()

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val path = url.removePrefix(base)
        return when {
            path == "/api/v1/spaces" -> ok(
                "{\"spaces\":[" + spaces.entries.joinToString(",") { (id, sp) ->
                    "{\"id\":${q(id)},\"kind\":${q(sp.kind)},\"created_at\":\"2026-09-05T00:00:00Z\"}"
                } + "]}",
            )

            path.endsWith("/keys") -> {
                val sp = spaces[spaceId(path)] ?: return notFound()
                ok(
                    "{\"space_id\":${q(spaceId(path))},\"wrapped_keys\":[" +
                        sp.keys.entries.joinToString(",") { (r, w) ->
                            "{\"recipient\":${q(r)},\"wrapped\":${q(b64(w))},\"created_at\":\"2026-09-05T00:00:00Z\"}"
                        } + "]}",
                )
            }

            path.endsWith("/changes") -> {
                val sp = spaces[spaceId(path)] ?: return notFound()
                ok(
                    "{\"space_id\":${q(spaceId(path))},\"changes\":[" +
                        sp.changes.joinToString(",") { it.encode() } + "]}",
                )
            }

            path.endsWith("/snapshot") -> notFound()

            // no snapshots in the fake
            else -> notFound()
        }
    }

    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse {
        val path = url.removePrefix(base)
        val b = body ?: return HttpResponse(400, "")
        return when {
            path == "/api/v1/spaces" -> {
                val id = JsonScan.stringField(b, "id") ?: return HttpResponse(400, "")
                val kind = JsonScan.stringField(b, "kind") ?: ""
                val sp = Space(kind)
                for (w in JsonScan.objectsOf(b, listOf("wrapped_keys"))) {
                    val r = JsonScan.stringField(w, "recipient") ?: continue
                    sp.keys[r] = Base64.getDecoder().decode(JsonScan.stringField(w, "wrapped"))
                }
                spaces[id] = sp
                HttpResponse(201, "{\"id\":${q(id)},\"kind\":${q(kind)},\"created_at\":\"2026-09-05T00:00:00Z\"}")
            }

            path.endsWith("/changes") -> {
                val sp = spaces[spaceId(path)] ?: return notFound()
                val ch = EncryptedChange.parse(b)
                // The node re-derives the id and refuses a mismatch (Invariant 1).
                if (!ch.validate() || ch.spaceId != spaceId(path)) {
                    return HttpResponse(400, "{\"detail\":\"change id does not match its content\"}")
                }
                if (sp.changes.none { it.changeId == ch.changeId }) sp.changes.add(ch)
                HttpResponse(201, "{\"change_id\":${q(ch.changeId)}}")
            }

            else -> notFound()
        }
    }

    /** The recipient ids a space is wrapped for (test assertion helper). */
    fun recipients(spaceId: String): Set<String> = spaces[spaceId]?.keys?.keys?.toSet() ?: emptySet()

    fun changeCount(spaceId: String): Int = spaces[spaceId]?.changes?.size ?: 0

    private fun spaceId(path: String): String = path.removePrefix("/api/v1/spaces/").substringBefore('/')

    private fun ok(body: String) = HttpResponse(200, body)
    private fun notFound() = HttpResponse(404, "{\"detail\":\"not found\"}")
    private fun q(s: String) = PsJson.goJsonString(s)
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
}

/**
 * A confidentiality-preserving-shaped but non-cryptographic [SpaceCrypto] for logic
 * tests: a space key is random bytes; a wrap is `keyTag ‖ spaceKey` recoverable only
 * with the matching seed; a change is `keyPrefix ‖ plaintext` so two keys give
 * different bytes and the plaintext is not the whole ciphertext. It exercises the
 * fold/heads/wire paths without depending on real X25519 (that is voidbind-client's
 * own KAT-proven job).
 */
internal class IdentityCrypto : SpaceCrypto {
    private var counter = 0
    override fun newSpaceKey(): ByteArray = ByteArray(32) { (it + (++counter)).toByte() }
    override fun seal(spaceKey: ByteArray, recipientPub: ByteArray): ByteArray = recipientPub + spaceKey
    override fun unwrap(wrapped: ByteArray, recipientSeed: ByteArray): ByteArray {
        val tag = wrapped.copyOfRange(0, recipientSeed.size)
        require(tag.contentEquals(pubOf(recipientSeed))) { "wrong recipient" }
        return wrapped.copyOfRange(recipientSeed.size, wrapped.size)
    }
    override fun encryptChange(spaceKey: ByteArray, plaintext: ByteArray): ByteArray =
        spaceKey.copyOfRange(0, 4) + plaintext
    override fun decryptChange(spaceKey: ByteArray, blob: ByteArray): ByteArray {
        require(blob.copyOfRange(0, 4).contentEquals(spaceKey.copyOfRange(0, 4))) { "wrong key" }
        return blob.copyOfRange(4, blob.size)
    }

    companion object {
        /** In the fake, a device's "public key" is its seed (identity); the real crypto derives it via X25519. */
        fun pubOf(seed: ByteArray): ByteArray = seed
    }
}

/** A test device key whose "public key" equals its seed (see [IdentityCrypto]). */
internal class FakeDeviceKey(private val id: Int) : DeviceEncKey {
    private val bytes = ByteArray(32) { (id * 31 + it).toByte() }
    override fun publicKey(): ByteArray = bytes
    override fun seed(): ByteArray = bytes
}
