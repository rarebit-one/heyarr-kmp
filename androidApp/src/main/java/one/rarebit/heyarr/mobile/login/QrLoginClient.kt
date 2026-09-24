package one.rarebit.heyarr.mobile.login

import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.net.VoidbindTransportAdapter
import one.rarebit.voidbind.net.WebLoginClient
import one.rarebit.voidbind.net.HttpTransport as VoidbindHttpTransport

/**
 * The initiator (RP) half of a QR web-login against heyarr's weblogin broker, driven
 * through voidbind-client's [WebLoginClient] — the published, Go-interop-proven port
 * of voidbind-go's `weblogin` handler:
 *
 * ```
 * POST {rp}/login             -> { "id":.., "qr":.. }             (createLogin)
 * GET  {rp}/login/{id}         -> { "status", "token"?, "user"? }  (poll)
 * ```
 *
 * [begin] starts the login and hands back the tuple to render as a QR code;
 * [awaitApproval] polls until the broker reports the login approved and returns a
 * short-lived **Bearer** session token (the bootstrap credential the app carries as
 * `Authorization: Bearer <token>` until it enrols as a device).
 *
 * The poll cadence and the clock are injected so the state machine is unit-tested
 * instantly; the HTTP calls and JSON are the library's, so the wire stays identical
 * to every other Voidbind relying party by construction rather than by copy.
 */
class QrLoginClient(
    private val web: WebLoginClient,
    /** Poll cadence; injected so tests run instantly. */
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val pollIntervalMs: Long = 1_500L,
) : VoidbindLogin {

    /** Build over the app's own [HttpTransport] seam (what tests script and the settings factory holds). */
    constructor(
        http: HttpTransport,
        rpBase: String,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        nowMs: () -> Long = { System.currentTimeMillis() },
        pollIntervalMs: Long = 1_500L,
    ) : this(WebLoginClient(VoidbindTransportAdapter(http, sleep), rpBase), sleep, nowMs, pollIntervalMs)

    /** Build over a voidbind-client transport (the app's OkHttp actual). */
    constructor(transport: VoidbindHttpTransport, rpBase: String) :
        this(WebLoginClient(transport, rpBase))

    override fun begin(): VoidbindLogin.Pending {
        val created = web.createLogin()
        val tuple = LoginTuple.decode(created.qr)
        return VoidbindLogin.Pending(loginId = created.id, tuple = tuple, qrTuple = created.qr)
    }

    override fun awaitApproval(pending: VoidbindLogin.Pending, timeoutMs: Long): VoidbindLogin.Result {
        val deadline = nowMs() + timeoutMs
        while (true) {
            val poll = try {
                web.poll(pending.loginId)
            } catch (e: IllegalArgumentException) {
                // WebLoginClient refuses a non-200 poll with the status in the message.
                return VoidbindLogin.Result.Failed(e.message ?: "poll failed")
            }
            when (poll.status) {
                "approved" -> {
                    val token = poll.token
                        ?: return VoidbindLogin.Result.Failed("approved without a token")
                    return VoidbindLogin.Result.Approved(sessionToken = token, user = poll.user)
                }

                "denied" -> return VoidbindLogin.Result.Denied("declined on the authenticator")

                "expired" -> return VoidbindLogin.Result.Denied("login challenge expired")
                // "pending" → keep polling
            }
            if (nowMs() >= deadline) return VoidbindLogin.Result.Denied("timed out waiting for approval")
            sleep(pollIntervalMs)
        }
    }
}
