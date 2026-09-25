package one.rarebit.heyarr.mobile.device

import one.rarebit.voidbind.net.HttpResponse
import one.rarebit.voidbind.net.HttpTransport
import one.rarebit.voidbind.net.RelayTimeout

/**
 * Stretches voidbind-client's relay poll to the relay session's TTL.
 *
 * The library's `RelayClient.fetch` gives up on a peer slot after a fixed 60 s of 404s
 * (`maxWaitMillis`, not reachable through `DevicePairing`) — 401 polls at 150 ms,
 * exactly the count the node's relay log showed for the failed same-phone pairing:
 * Cruciform on this phone had not posted its reveal yet because the human was busy
 * creating the key / comparing / confirming over there. The relay itself keeps a
 * session for ten minutes, so this wrapper makes every GET that 404s **keep polling
 * on its own** until the slot fills, a non-404 answer arrives, or [deadlineMillis]
 * passes — at which point it throws the library's own [RelayTimeout], so
 * `PairingFailures` still classifies it as `TIMEOUT` (distinct from unreachable, which
 * surfaces as whatever the inner transport throws on the first call). The library's
 * counter then never reaches 60 s: to it, one poll simply took a while.
 *
 * Interruptible: the sleep is a plain `Thread.sleep` whose `InterruptedException` is
 * let through, so a `runInterruptible` cancel tears the wait down at once.
 */
class PatientRelayTransport(
    private val inner: HttpTransport,
    private val deadlineMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : HttpTransport {

    override fun get(url: String): HttpResponse {
        while (true) {
            if (Thread.interrupted()) throw InterruptedException("relay poll cancelled")
            val resp = inner.get(url)
            if (resp.status != 404) return resp
            if (clock() >= deadlineMillis) {
                throw RelayTimeout("relay: session expired before the peer posted $url")
            }
            sleeper(pollIntervalMillis)
        }
    }

    override fun post(url: String, body: ByteArray?, contentType: String?): HttpResponse =
        inner.post(url, body, contentType)

    override fun put(url: String, body: ByteArray, contentType: String?): HttpResponse =
        inner.put(url, body, contentType)

    override fun delete(url: String, body: ByteArray?, contentType: String?): HttpResponse =
        inner.delete(url, body, contentType)

    override fun sleep(millis: Long) = inner.sleep(millis)

    companion object {
        /** The relay is human-paced; a second between polls is plenty and kind to the phone. */
        const val DEFAULT_POLL_INTERVAL_MILLIS = 1_000L
    }
}
