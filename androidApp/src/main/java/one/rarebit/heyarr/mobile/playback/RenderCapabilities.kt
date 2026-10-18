package one.rarebit.heyarr.mobile.playback

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.JsonScan
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Reader for `POST /api/v1/render/capabilities` (heyarr-core ADR-0112):
 * `{ "capabilities": [ { asset_id, render_url?, render_unavailable?, expires_at? } ] }`,
 * one entry per requested asset, a URL or a reason.
 */
object RenderCapabilitiesJson {
    data class Entry(val assetId: String, val renderUrl: String?, val unavailable: String?, val expiresAtMs: Long?)

    fun parse(body: String): List<Entry> = JsonScan.objectsOf(body, listOf("capabilities")).mapNotNull { o ->
        val id = JsonScan.stringField(o, "asset_id") ?: return@mapNotNull null
        Entry(
            assetId = id,
            renderUrl = JsonScan.stringField(o, "render_url")?.takeIf { it.isNotBlank() },
            unavailable = JsonScan.stringField(o, "render_unavailable"),
            expiresAtMs = JsonScan.stringField(o, "expires_at")
                ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
        )
    }
}

/**
 * Render capabilities for the audio queue, so music keeps playing however long the phone
 * stays locked.
 *
 * Every authenticated range read needs a Device possession proof, and a locked phone
 * cannot sign one (the device key is unlocked-device-only). The node can hand out
 * ADR-0040 capability URLs instead — one blob, six hours, no credential — and does so for
 * a first-party client through `POST /render/capabilities` without opening a session
 * (ADR-0112). So when a queue starts, while the phone is necessarily unlocked, the whole
 * queue is minted here ([prefetch]); [PlaybackService]'s data source then swaps each
 * track's blob URL for its capability as it loads ([resolve]).
 *
 * The queue's items keep their blob URLs, deliberately: the first note does not wait for
 * the mint, and a track the node would not mint for (a replica on another peer, an
 * unconfigured node, a node too old to have the route) simply plays as before, on a proof.
 * [LockedPlayback]'s deep buffer and wait-for-unlock remain the backstop for those.
 */
class RenderCapabilityCache(private val clock: () -> Long = System::currentTimeMillis) {

    /**
     * Mints capabilities for asset ids — the signed-in session's `HeyarrApi`; null for a
     * guest (whose reads carry no proof to lose) or signed out. Blocking; may throw. Set
     * through [use], never directly.
     */
    @Volatile
    private var minter: ((List<String>) -> List<RenderCapabilitiesJson.Entry>)? = null

    // [current] is the live session (see [sessionKey]); [owner] is the session whose
    // capabilities the cache holds. [generation] moves whenever [current] does, so a mint
    // still in flight from an earlier session is discarded rather than written back. All
    // guarded by [lock], as are the cache's writes and lookups.
    private val lock = Any()
    private var current: String? = null
    private var owner: String? = null
    private var generation = 0L

    /**
     * The live session is [key], minting through [minter] (null: a guest, or signed out).
     *
     * A capability is a credential-free URL the node cannot revoke for six hours, so the
     * cache only ever serves its [owner]:
     *  - a guest or signed-out session gets nothing from it ([resolve] answers null) but does
     *    not clear it. The app reopened behind a playing queue starts as a guest for a moment
     *    while it restores the device credential, and clearing then would strand the queue's
     *    later tracks once the phone locks again;
     *  - the same session coming back (that restore, an Activity recreated on rotation)
     *    resumes with everything it minted;
     *  - a DIFFERENT signed-in session clears it before minting its own.
     */
    fun use(key: String?, minter: ((List<String>) -> List<RenderCapabilitiesJson.Entry>)?) = synchronized(lock) {
        this.minter = minter
        if (key != current) {
            current = key
            generation++
        }
        if (minter != null && key != owner) {
            owner = key
            byContentUrl.clear()
        }
    }

    private data class Held(val url: String, val expiresAtMs: Long)

    // Keyed by the blob URL the queue item carries — the only thing the data source sees.
    private val byContentUrl = ConcurrentHashMap<String, Held>()

    /**
     * Mint for [items] and remember the answers. Blocking — run it on IO. Never throws: a
     * failed mint leaves those tracks on the blob URL, which is how they played before.
     */
    fun prefetch(items: List<AudioItem>) {
        val (mint, minted) = synchronized(lock) { minter to generation }
        if (mint == null) return
        byContentUrl.values.removeIf { it.expiresAtMs <= clock() }
        for (page in items.distinctBy { it.contentUrl }.chunked(MAX_PER_REQUEST)) {
            val entries = runCatching { mint(page.map { it.assetId }) }.getOrNull()
            if (entries == null || !store(page, entries, minted)) break
        }
    }

    /**
     * Remember one minted page, unless the session changed while it was minting — then its
     * URLs belong to someone else, and so would the rest of the queue's. False stops the
     * prefetch.
     */
    private fun store(page: List<AudioItem>, entries: List<RenderCapabilitiesJson.Entry>, minted: Long): Boolean =
        synchronized(lock) {
            if (generation != minted || current != owner) return@synchronized false
            val byAsset = entries.associateBy { it.assetId }
            page.forEach { item -> held(byAsset[item.assetId], item)?.let { byContentUrl[item.contentUrl] = it } }
            true
        }

    /** What to remember for [item] from its [entry], or null when there is nothing usable. */
    private fun held(entry: RenderCapabilitiesJson.Entry?, item: AudioItem): Held? {
        val url = entry?.renderUrl?.let { rebase(it, item.contentUrl) }
        val expires = entry?.expiresAtMs
        return if (url != null && expires != null) Held(url, expires) else null
    }

    /**
     * True when some of [items] hold a capability that will lapse within [withinMs], so the
     * queue should be minted again while the phone can still sign.
     *
     * Capabilities live six hours and a queue can outlast that (an audiobook, a long
     * playlist). A locked phone cannot mint, but any moment the phone CAN — the next track
     * change after an unlock — is a chance to renew, so only a phone locked for the whole of
     * a capability's life still runs out. Tracks the node would not mint for hold nothing and
     * do not count: asking again on every track change would not change the node's answer.
     */
    fun needsRenewal(items: List<AudioItem>, withinMs: Long = RENEW_WITHIN_MS): Boolean = synchronized(lock) {
        minter != null && current == owner &&
            items.any { item -> byContentUrl[item.contentUrl]?.let { it.expiresAtMs - clock() < withinMs } == true }
    }

    /**
     * The capability to fetch instead of [contentUrl], or null to fetch [contentUrl] itself.
     * A capability inside [MIN_REMAINING_MS] of expiry is not used: a long track opened on
     * it could outlive it mid-read, and the blob URL is the better bet while the phone can
     * still sign.
     */
    fun resolve(contentUrl: String): String? = synchronized(lock) {
        // Under [use]'s lock, so a lookup racing a session change cannot hand back another
        // session's URL. Once per track open: cheap.
        if (current != owner) return@synchronized null
        byContentUrl[contentUrl]?.takeIf { it.expiresAtMs - clock() > MIN_REMAINING_MS }?.url
    }

    companion object {
        /**
         * Who a session is, for [use]: the node and the credential's identity — a Device's
         * enrolment cert (not its proof, which is re-minted every couple of minutes), a
         * token, or "guest". Hashed, so the cache holds no credential. Pure.
         */
        fun sessionKey(baseUrl: String, credential: Credential): String {
            val who = when (credential) {
                is Credential.Device -> "device:" + credential.cert
                is Credential.Session -> "session:" + credential.token
                is Credential.Bearer -> "bearer:" + credential.token
                else -> credential::class.simpleName.orEmpty()
            }
            val digest = MessageDigest.getInstance("SHA-256").digest("$baseUrl|$who".toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }

        /** The node's own bound on one request. */
        const val MAX_PER_REQUEST = 200

        /** Renew when a capability has under two hours left: several chances before it lapses. */
        const val RENEW_WITHIN_MS = 2 * 60 * 60 * 1000L

        /** Ten minutes: longer than any one track takes to load. */
        const val MIN_REMAINING_MS = 10 * 60 * 1000L

        /**
         * [renderUrl]'s path on [contentUrl]'s origin. Pure.
         *
         * The node mints at its public origin, which the phone may not reach the node by
         * (a LAN address, a different hostname). `/render` is mounted on the same router as
         * `/api/v1`, and a capability is valid at the node that minted it — the one the
         * phone just asked — so the origin the phone already uses is the one that works.
         */
        fun rebase(renderUrl: String, contentUrl: String): String? = runCatching {
            val cap = URI(renderUrl)
            val base = URI(contentUrl)
            val path = cap.rawPath?.takeIf { it.startsWith("/render/") } ?: return null
            URI(base.scheme, null, base.host, base.port, null, null, null).toString() + path
        }.getOrNull()
    }
}
