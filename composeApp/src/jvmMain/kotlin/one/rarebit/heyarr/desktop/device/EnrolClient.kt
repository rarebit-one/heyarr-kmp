package one.rarebit.heyarr.desktop.device

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.core.net.JsonScan
import one.rarebit.voidbind.crypto.MiniJson

/**
 * Registers a freshly paired desktop's admission with the heyarr node — the desktop
 * copy of heyarr-mobile's `device/EnrolClient`. A possession proof verifies OFFLINE,
 * but heyarr's `deviceauth.Verify` additionally requires a `device_identities` row, so
 * an admission the node never accepted authenticates nobody: this posts it.
 *
 * Two lanes, tried in order:
 *  - **Self-enrol** `POST {base}/enrol {cert, proof, name, ops?}` (outside `/api/v1`,
 *    heyarr-core ADR-0067): `cert` carries the admitting op, `proof` a fresh possession
 *    proof over it, `ops` the membership ops this device knows. A node that predates
 *    `ops` (strict JSON → 400) is retried once without it.
 *  - **Admin registration** `POST /api/v1/identities/devices {cert, name}` — admin scope,
 *    which a guest / read-only session never has; surfaced for an operator when the
 *    self-enrol route is absent.
 */
class EnrolClient(
    private val http: HttpTransport,
    private val baseUrl: String,
) {
    sealed interface Outcome {
        /** The node accepted the admission (self-enrol, or the admin route). */
        data class Registered(val via: String, val recoveryEncryptionKey: String? = null) : Outcome

        /** No route this caller can use — an operator must register the op. */
        data class NeedsAdmin(val reason: String) : Outcome

        data class Failed(val message: String) : Outcome
    }

    fun register(
        certToken: String,
        proof: String,
        name: String,
        credential: Credential?,
        ops: List<String> = emptyList(),
    ): Outcome {
        var self = runCatching { http.post(selfEnrolUrl(baseUrl), selfBody(certToken, proof, name, ops), "application/json") }
            .getOrElse { return Outcome.Failed("self-enrol: ${it.message}") }
        if (self.status == 400 && ops.isNotEmpty()) {
            self = runCatching { http.post(selfEnrolUrl(baseUrl), selfBody(certToken, proof, name, emptyList()), "application/json") }
                .getOrElse { return Outcome.Failed("self-enrol: ${it.message}") }
        }
        when (self.status) {
            200, 201, 204 -> return Outcome.Registered("POST /enrol", recoveryKey(self.body))

            404, 405 -> Unit

            // not mounted on this node — fall through to the admin lane
            else -> return Outcome.Failed(problem(self.body, self.status, "self-enrol"))
        }

        if (credential == null || credential is Credential.Guest) return Outcome.NeedsAdmin("this node has no /enrol route")
        val adminBody = MiniJson.encodeObject(listOf("cert" to certToken, "name" to name))
        val admin = runCatching {
            http.post(
                adminEnrolUrl(baseUrl),
                adminBody,
                "application/json",
                credential.asHeader(),
            )
        }.getOrElse { return Outcome.Failed("register: ${it.message}") }
        return when (admin.status) {
            200, 201 -> Outcome.Registered("POST /api/v1/identities/devices")
            401, 403 -> Outcome.NeedsAdmin("no /enrol route, and registering needs admin scope (HTTP ${admin.status})")
            else -> Outcome.Failed(problem(admin.body, admin.status, "register"))
        }
    }

    companion object {
        fun selfEnrolUrl(baseUrl: String) = baseUrl.trimEnd('/') + "/enrol"
        fun adminEnrolUrl(baseUrl: String) = baseUrl.trimEnd('/') + "/api/v1/identities/devices"

        /** The recovery encryption PUBLIC key (`x25519:<hex>`) an `/enrol` response may carry, or null. */
        fun recoveryKey(body: String): String? =
            JsonScan.stringField(body, "recovery_encryption_key")?.trim()?.takeIf { it.isNotEmpty() }

        /** The `POST /enrol` body: `cert`, `proof`, `name`, and `ops` only when there are any. */
        fun selfBody(certToken: String, proof: String, name: String, ops: List<String>): String = MiniJson.encodeObject(
            buildList {
                add("cert" to certToken)
                add("proof" to proof)
                add("name" to name)
                if (ops.isNotEmpty()) add("ops" to ops)
            },
        )

        /** A best-effort message from an RFC-7807 problem body, else a plain "step failed (HTTP n)". */
        private fun problem(body: String, status: Int, step: String): String {
            val detail = JsonScan.stringField(body, "detail") ?: JsonScan.stringField(body, "title")
            return detail?.takeIf { it.isNotBlank() }?.let { "$step: $it (HTTP $status)" }
                ?: "$step failed (HTTP $status)"
        }
    }
}
