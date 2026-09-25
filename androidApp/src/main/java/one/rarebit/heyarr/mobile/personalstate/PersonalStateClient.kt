package one.rarebit.heyarr.mobile.personalstate

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.core.net.JsonScan
import one.rarebit.heyarr.mobile.net.ProblemDetail
import java.net.URLEncoder
import java.util.Base64

/**
 * The device-facing personal-state sync surface (heyarr-core
 * `internal/api/personalstate`):
 *
 * ```
 * GET  /api/v1/spaces                 -> the spaces this device can see
 * GET  /api/v1/spaces/{id}/keys       -> wrapped space keys (opaque)
 * GET  /api/v1/spaces/{id}/changes    -> opaque CRDT changes (incremental)
 * GET  /api/v1/spaces/{id}/snapshot   -> a snapshot (404 when none)
 * POST /api/v1/spaces                 -> mint a space (client id + wrapped keys)
 * POST /api/v1/spaces/{id}/changes    -> push an opaque change
 * ```
 *
 * THE INVARIANT: everything the server hands back is **opaque ciphertext** (a
 * wrapped key it cannot unwrap, a change it cannot read). The peer never decrypts
 * (Invariant 6, ADR-0049); [SpaceSession] does, on the device, under a space key
 * unwrapped with this phone's X25519 key. This client only fetches/pushes bytes.
 */
class PersonalStateClient(
    private val http: HttpTransport,
    private val baseUrl: String,
    private val credential: Credential,
) {
    internal fun listSpaces(): List<SpaceInfo> {
        val resp = http.get(spacesUrl(baseUrl), credential.asHeader())
        require(resp.status == 200) { fail("GET /spaces", resp.status, resp.body) }
        return JsonScan.objectsOf(resp.body, listOf("spaces")).map {
            SpaceInfo(
                JsonScan.stringField(it, "id") ?: "",
                JsonScan.stringField(it, "kind") ?: "",
                JsonScan.stringField(it, "created_at") ?: "",
            )
        }
    }

    internal fun wrappedKeys(spaceId: String): List<WrappedKeyEntry> {
        val resp = http.get(keysUrl(baseUrl, spaceId), credential.asHeader())
        if (resp.status == 404) return emptyList()
        require(resp.status == 200) { fail("GET /keys", resp.status, resp.body) }
        return JsonScan.objectsOf(resp.body, listOf("wrapped_keys")).map {
            WrappedKeyEntry(
                JsonScan.stringField(it, "recipient") ?: "",
                b64(JsonScan.stringField(it, "wrapped")),
            )
        }
    }

    internal fun changes(spaceId: String): List<EncryptedChange> {
        val resp = http.get(changesUrl(baseUrl, spaceId), credential.asHeader())
        if (resp.status == 404) return emptyList()
        require(resp.status == 200) { fail("GET /changes", resp.status, resp.body) }
        return JsonScan.objectsOf(resp.body, listOf("changes")).map { EncryptedChange.parse(it) }
    }

    internal fun snapshot(spaceId: String): EncryptedSnapshot? {
        val resp = http.get(snapshotUrl(baseUrl, spaceId), credential.asHeader())
        if (resp.status == 404) return null
        require(resp.status == 200) { fail("GET /snapshot", resp.status, resp.body) }
        return EncryptedSnapshot.parse(resp.body)
    }

    internal fun createSpace(id: String, kind: String, wrapped: List<WrappedKeyEntry>): SpaceInfo {
        val body = buildString {
            append("{\"id\":").append(PsJson.goJsonString(id))
            append(",\"kind\":").append(PsJson.goJsonString(kind))
            append(",\"wrapped_keys\":[")
            wrapped.forEachIndexed { i, w ->
                if (i > 0) append(',')
                append("{\"recipient\":").append(PsJson.goJsonString(w.recipient))
                append(",\"wrapped\":").append(PsJson.goJsonString(Base64.getEncoder().encodeToString(w.wrapped)))
                append('}')
            }
            append("]}")
        }
        val resp = http.post(spacesUrl(baseUrl), body, "application/json", credential.asHeader())
        require(resp.status == 201 || resp.status == 200) { fail("POST /spaces", resp.status, resp.body) }
        return SpaceInfo(
            JsonScan.stringField(resp.body, "id") ?: id,
            JsonScan.stringField(resp.body, "kind") ?: kind,
            JsonScan.stringField(resp.body, "created_at") ?: "",
        )
    }

    internal fun putChange(spaceId: String, change: EncryptedChange) {
        val resp = http.post(changesUrl(baseUrl, spaceId), change.encode(), "application/json", credential.asHeader())
        require(resp.status == 201 || resp.status == 200) { fail("POST /changes", resp.status, resp.body) }
    }

    private fun fail(op: String, status: Int, body: String): String =
        ProblemDetail.message(body, status, "personal-state: $op")

    companion object {
        fun spacesUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/api/v1/spaces"
        fun keysUrl(baseUrl: String, spaceId: String): String = space(baseUrl, spaceId) + "/keys"
        fun changesUrl(baseUrl: String, spaceId: String): String = space(baseUrl, spaceId) + "/changes"
        fun snapshotUrl(baseUrl: String, spaceId: String): String = space(baseUrl, spaceId) + "/snapshot"

        private fun space(baseUrl: String, spaceId: String): String =
            baseUrl.trimEnd('/') + "/api/v1/spaces/" + URLEncoder.encode(spaceId, "UTF-8")

        private fun b64(s: String?): ByteArray = if (s.isNullOrEmpty()) ByteArray(0) else Base64.getDecoder().decode(s)
    }
}
