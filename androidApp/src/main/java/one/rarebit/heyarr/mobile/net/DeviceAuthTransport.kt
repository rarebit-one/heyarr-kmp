package one.rarebit.heyarr.mobile.net
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.voidbind.auth.DeviceAuthPolicy
import one.rarebit.voidbind.auth.DeviceCredential

/**
 * Wraps the app's [HttpTransport] so an enrolled device's requests always carry a
 * live `Device` credential, and a refused one is re-minted and retried ONCE
 * (mobile-client §1a constraint 2 — "re-mint and retry, never fail hard").
 *
 * The API clients hold an immutable [Credential] snapshot and set the header
 * themselves; this decorator is the single place that keeps it fresh, and it does so
 * by applying voidbind-client's [DeviceAuthPolicy] to the library's [DeviceCredential]:
 *
 *  1. A request whose `Authorization` is a `Device …` value is re-stamped with
 *     [DeviceCredential.headerValue] — the proof in force (reused for the credential's
 *     `reuseForSeconds`, re-minted once the window lapses), so a client's stale
 *     snapshot never hits the wire.
 *  2. On a `401` ([DeviceAuthPolicy.next] says `REFRESH_AND_RETRY`) [onUnauthorized]
 *     runs first — the app's chance to learn WHY before spending a biometric prompt:
 *     it refreshes the membership replica from the node (`GET /membership/{usr}`) and
 *     re-evaluates; a device that finds itself removed returns `false` and the 401 is
 *     surfaced as-is, with no retry, so the app can show an honest "this device was
 *     removed" instead of looping. Otherwise the credential is
 *     [DeviceCredential.refresh]ed (a forced fresh proof — on a phone this may show
 *     the biometric prompt), the membership header is re-read (so ops just learned
 *     ride the retry), and the request is sent again exactly once. heyarr answers
 *     every Device refusal with the same undifferentiated 401, so one retry is the
 *     whole strategy: a second 401 is surfaced as-is.
 *
 * Requests with a `Bearer` (session) credential, or none, pass straight through.
 *
 * **Membership.** Device requests additionally carry [MEMBERSHIP_HEADER]
 * (`Voidbind-Membership`, voidbind-kmp ADR-0005 / heyarr-core ADR-0068) from
 * [membership]: the comma-joined membership ops this device knows (at most
 * [DeviceCredential.MAX_PRESENTED_OPS] — `device/MembershipOps` picks which), so a
 * node that has never met the member that admitted this device can still evaluate
 * its admission. A null/blank value adds no header.
 */
class DeviceAuthTransport(
    private val inner: HttpTransport,
    private val credential: () -> DeviceCredential?,
    private val membership: () -> String? = { null },
    /**
     * Runs after a `401` on a Device request, BEFORE the re-mint and the single
     * retry. Return `false` to skip the retry and surface the 401 — the device learned
     * it is no longer a member. Default: always retry.
     */
    private val onUnauthorized: () -> Boolean = { true },
) : HttpTransport {

    override fun get(url: String, headers: Map<String, String>): HttpResponse =
        withDeviceAuth(headers) { inner.get(url, it) }

    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse =
        withDeviceAuth(headers) { inner.post(url, body, contentType, it) }

    override fun delete(url: String, headers: Map<String, String>): HttpResponse =
        withDeviceAuth(headers) { inner.delete(url, it) }

    override fun patch(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse =
        withDeviceAuth(headers) { inner.patch(url, body, contentType, it) }

    private inline fun withDeviceAuth(headers: Map<String, String>, send: (Map<String, String>) -> HttpResponse): HttpResponse {
        val cred = credential()
        if (cred == null || !DeviceCredential.isDeviceHeader(headers[Credential.HEADER])) return send(headers)

        var attempt = 1
        var header = cred.headerValue()
        while (true) {
            val response = send(stamped(headers) + (Credential.HEADER to header))
            if (DeviceAuthPolicy.next(response.status, attempt) == DeviceAuthPolicy.Next.DONE) return response
            if (!onUnauthorized()) return response
            attempt++
            header = cred.refresh().headerValue
        }
    }

    /** [headers] plus the membership header when there is something to present — re-read per attempt. */
    private fun stamped(headers: Map<String, String>): Map<String, String> =
        membership()?.takeIf { it.isNotBlank() }?.let { headers + (MEMBERSHIP_HEADER to it) } ?: headers

    companion object {
        /** The membership-ops request header a Device request carries (voidbind-go `rp.MembershipHeader`). */
        const val MEMBERSHIP_HEADER = DeviceCredential.MEMBERSHIP_HEADER
    }
}
