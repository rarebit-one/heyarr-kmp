package one.rarebit.heyarr.mobile.cast

import one.rarebit.heyarr.mobile.playback.ClientCapabilities
import one.rarebit.heyarr.mobile.playback.PlaybackClient.PlanResult
import java.net.Inet4Address
import java.net.InetAddress

/**
 * What the node offers a Cast receiver for one film, planned with [CastRouting.PROFILE].
 *
 * Today that is always the live fMP4 repackage (`/api/v1/playback/stream/{token}`, ADR-0069):
 * its token is bound to the credential that planned it, and the node refuses it without that
 * credential on every request. A Default Media Receiver cannot send an `Authorization`
 * header, so [credentialFree] is false and the film must be relayed by this phone.
 *
 * TODO(heyarr-core#908): the planner is gaining a seekable HLS presentation beside the stream
 * (`hls_url` in the open PR), a capability URL fetchable without a credential. When the
 * vendored OpenAPI spec carries it, a plan that names one becomes a source with [credentialFree] true and
 * [restartSeekable] false (the receiver seeks a VOD playlist itself), and [CastRouting.decide]
 * can then hand the TV the node's own URL. Nothing else here needs to change for that.
 */
data class CastSource(
    /** The node's absolute URL for the film, never shown and never handed to a receiver unless [credentialFree]. */
    val url: String,
    val mime: String,
    /** The film's full runtime: a live repackage cannot say, so LOAD declares it from here. */
    val durationSeconds: Double?,
    /** A receiver may fetch [url] itself, with no credential on the request. */
    val credentialFree: Boolean = false,
    /** It seeks by restarting at `?start=` (the live fMP4), not natively. */
    val restartSeekable: Boolean = true,
)

/**
 * How the film reaches the receiver. [Direct]: the receiver fetches it from the node.
 * [Relay]: it fetches from this phone's [CastRelay], which fetches from the node with this
 * phone's credential. [why] is said in the Cast panel, so it is written for a person.
 */
sealed interface CastRoute {
    val why: String

    data class Direct(override val why: String) : CastRoute

    data class Relay(override val why: String) : CastRoute
}

/**
 * The Cast-profile plan and the per-cast reachability decision (heyarr-kmp#250).
 *
 * **The decision rule**, in order:
 *  1. A source that needs a credential is relayed. No receiver can authenticate, so there is
 *     nothing to decide — and today every source is one (see [CastSource]).
 *  2. A credential-free source goes [CastRoute.Direct] only when the node's address and the
 *     receiver's are both RFC 1918 private IPv4: the home LANs, where a TV routes to the node
 *     the way the phone does. Anything else is relayed: a node reached at a public name, over
 *     a VPN or a tailnet (100.64/10 is carrier-grade NAT, not RFC 1918), or a receiver on a
 *     hotel network — the TV cannot reach the node there, and the phone can.
 *  3. A direct cast the receiver then fails to load falls back to the relay for the rest of
 *     the cast ([CastManager]): the receiver's own fetch is the ground truth this rule guesses at.
 *
 * Relaying makes the phone the film's only path to the TV. When travelling that is the point,
 * and it is why the relay runs in [CastRelayService] with a wake lock and a Wi-Fi lock: a phone
 * that sleeps or drops off the network leaves the TV to play out its buffer and stop.
 */
object CastRouting {
    /**
     * What a Default Media Receiver is asked to be (the desktop's proven Cast profile): MP4,
     * H.264, and no audio codec declared, so the node always encodes stereo AAC — an AAC
     * source may carry eight channels, which the receiver plays as silence. 1080 lines, which
     * every Cast device decodes in H.264.
     */
    val PROFILE =
        ClientCapabilities(containers = listOf("mp4"), video = listOf("h264"), audio = emptyList(), maxHeight = 1080)

    /**
     * The source a plan made with [PROFILE] offers a receiver. Only the planner's repackaged
     * stream is known Cast-safe: a direct MP4 may still hold HEVC or multichannel AAC when the
     * node cannot transcode, so it is refused rather than cast as a black screen.
     */
    fun sourceOf(plan: PlanResult): CastSource = when (plan) {
        is PlanResult.Stream -> {
            val mime = plan.mime ?: "video/mp4"
            check(mime == "video/mp4") { "The server offered a $mime stream, which a Cast receiver cannot play" }
            CastSource(plan.url, mime, plan.source?.durationSeconds)
        }

        is PlanResult.Direct -> error("The server cannot convert this film for Cast (H.264 with stereo sound)")

        is PlanResult.Unavailable -> error("The server could not plan this film for Cast: ${plan.why}")
    }

    /** The rule in the object doc. [node] resolves the node's address, only when it matters. */
    fun decide(source: CastSource, receiver: Inet4Address?, node: () -> InetAddress?): CastRoute = when {
        !source.credentialFree -> CastRoute.Relay(
            "Relayed through this phone: the server's stream needs this phone's sign-in, which a TV cannot send.",
        )

        !privateV4(receiver) -> CastRoute.Relay("Relayed through this phone: the TV is not on a private network.")

        !privateV4(node()) -> CastRoute.Relay(
            "Relayed through this phone: the server is not on a private network the TV can reach.",
        )

        else -> CastRoute.Direct("Straight from the server: the TV and the server share the home network.")
    }

    /** RFC 1918: 10/8, 172.16/12, 192.168/16. Not loopback, link-local or carrier-grade NAT. */
    @Suppress("MagicNumber") // Address prefixes are the RFC's.
    fun privateV4(address: InetAddress?): Boolean {
        if (address !is Inet4Address) return false
        val b = address.address.map { it.toInt() and 0xff }
        return b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168)
    }
}
