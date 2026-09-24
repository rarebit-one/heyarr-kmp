package one.rarebit.heyarr.mobile.playback

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.library.WorkAsset

/**
 * The playback / blob-stream seam. heyarr serves content two ways (mobile-client
 * contract + heyarr-core #432):
 *
 *  - **Direct blob stream** — `GET /api/v1/blobs/{hash}/content`, a range-capable
 *    byte hop the player pulls from, authenticated with the caller's [Credential].
 *    ExoPlayer's HTTP data source handles the `Range`/`206` seek itself.
 *  - **Planned playback** — `POST /api/v1/playback/plan {asset_id, client}` with this
 *    phone's REAL capabilities ([ClientCapabilities]); the node answers `direct`
 *    (play the blob) or `stream` (play a phone-friendly fMP4 it repackages, same
 *    origin, same credential, no seeking in v1). [resolve] is the one entry point:
 *    it plans, and **falls back to the direct blob exactly as before** when the node
 *    predates the contract (a 400 on the new fields) — or cannot be asked at all.
 *
 * What is proven in CI here (pure, unit-tested): the content URL, the auth header,
 * the plan request body, the plan reader, and the fallback. What is phone-gated:
 * a real codec decoding the bytes.
 */
class PlaybackClient(
    private val http: HttpTransport,
    private val baseUrl: String,
    private val credential: Credential,
) {
    /** The planner's answer, reduced to what the player needs to know. */
    sealed interface PlanResult {
        /** Play the blob as it is (the node may name the URL; else the blob route). */
        data class Direct(val url: String?, val reason: String?) : PlanResult

        /** Play [url] as a node-repackaged progressive fMP4 stream. */
        data class Stream(val url: String, val mime: String?, val reason: String?, val source: PlaybackJson.Source?) : PlanResult

        /** The node doesn't speak the contract (400 on `client`), or could not be asked. */
        data class Unavailable(val why: String) : PlanResult
    }

    /**
     * Build [PlaybackTarget.Sidecar]s for subtitle assets, each pointing at its own
     * range-capable blob endpoint under this client's credential — so the player pulls
     * them through the same authenticated data source as the video. The MIME is hinted
     * from the filename (a blob URL has no extension), falling back to the asset's own;
     * the language is read from the filename's tag. Assets with no blob are dropped.
     */
    fun subtitleSidecars(assets: List<WorkAsset>): List<PlaybackTarget.Sidecar> = assets.mapNotNull { a ->
        val hash = a.blobHash?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        PlaybackTarget.Sidecar(
            url = blobContentUrl(baseUrl, hash),
            mimeType = Subtitles.externalMimeType(a.filename) ?: a.mime,
            language = Subtitles.languageTag(a.filename),
            label = a.filename,
        )
    }

    /**
     * Build the direct-stream [PlaybackTarget] for a known blob hash: the range-capable
     * content URL under this client's credential.
     */
    fun blobTarget(hash: String, isVideo: Boolean, mimeType: String? = null): PlaybackTarget = PlaybackTarget(
        contentUrl = blobContentUrl(baseUrl, hash),
        credential = credential,
        isVideo = isVideo,
        mimeType = mimeType,
    )

    /**
     * `POST /api/v1/playback/plan` with [caps]. A 400 is an older node that rejects the
     * `client` field (or still demands `device_id`) — [PlanResult.Unavailable], never
     * an exception, so the caller falls back to the blob. Any other non-200 is an
     * error worth surfacing (the asset is gone, the session is refused).
     */
    fun plan(assetId: String, caps: ClientCapabilities): PlanResult {
        val resp = try {
            http.post(
                planUrl(baseUrl),
                body = caps.planRequestBody(assetId),
                contentType = "application/json",
                headers = credential.asHeader(),
            )
        } catch (e: Exception) {
            return PlanResult.Unavailable("plan request failed: ${e.message ?: e.javaClass.simpleName}")
        }
        if (resp.status == 400) return PlanResult.Unavailable("node predates the plan contract (HTTP 400)")
        require(resp.status == 200) { "playback: POST /playback/plan failed: HTTP ${resp.status}" }
        val p = PlaybackJson.parse(resp.body)
        return when {
            p.isStream -> PlanResult.Stream(absolute(p.url!!), p.mime, p.reason, p.source)
            p.isDirect -> PlanResult.Direct(p.url?.let { absolute(it) }, p.reason)
            else -> PlanResult.Unavailable("plan answered mode=${p.mode ?: p.decision ?: "?"} with no stream url")
        }
    }

    /**
     * The one call the app makes to play an asset: plan with [caps], then build the
     * target — a stream when the node offers one, else the direct blob (planned when
     * the node judged it, unplanned when the node couldn't be asked). Never null:
     * a hash always yields SOMETHING to play, exactly as before #432.
     */
    fun resolve(assetId: String, hash: String, isVideo: Boolean, mimeType: String?, caps: ClientCapabilities): PlaybackTarget {
        val direct = blobTarget(hash, isVideo, mimeType)
        return when (val r = plan(assetId, caps)) {
            is PlanResult.Stream -> PlaybackTarget(
                contentUrl = r.url,
                credential = credential,
                isVideo = isVideo,
                mimeType = r.mime ?: "video/mp4",
                seekable = false,
                origin = PlaybackTarget.Origin.STREAM,
                reason = r.reason,
                // #433 (ADR-0069): the token URL can be re-requested with ?start=<s> to
                // restart ffmpeg from a new instant — a seek the player drives itself.
                restartSeekable = true,
                streamBaseUrl = r.url,
                streamStartSeconds = 0.0,
                // Without this the scrubber has no total and every drag lands on zero.
                sourceDurationSeconds = r.source?.durationSeconds,
            )

            is PlanResult.Direct -> direct.copy(
                contentUrl = r.url ?: direct.contentUrl,
                origin = PlaybackTarget.Origin.DIRECT_PLANNED,
                reason = r.reason,
            )

            is PlanResult.Unavailable -> direct.copy(reason = r.why)
        }
    }

    /** HEAD-style range probe: does the server honour ranges for this blob (seekable)? */
    fun probe(hash: String): ProbeResult {
        val headers = credential.asHeader() + mapOf("Range" to "bytes=0-0")
        val resp = http.get(blobContentUrl(baseUrl, hash), headers)
        return ProbeResult(status = resp.status)
    }

    /** The outcome of a range probe — just the status in the scaffold. */
    data class ProbeResult(val status: Int) {
        /** 206 Partial Content means the server honours ranges (seekable stream). */
        val acceptsRanges: Boolean get() = status == 206
    }

    /** Resolve a possibly-relative plan `url` against the base origin. */
    private fun absolute(url: String): String = if (url.startsWith("http://") || url.startsWith("https://")) {
        url
    } else {
        baseUrl.trimEnd('/') + "/" + url.trimStart('/')
    }

    companion object {
        /**
         * Pure, tested: the range-capable content URL for a blob hash. The hash is
         * `blake3:<64 lowercase hex>` and goes into the path VERBATIM — the server
         * validates that exact shape and answers 400 to a percent-encoded colon
         * (`blake3%3A…`), which is how every playback used to fail against a live node.
         * Anything outside that alphabet is refused here rather than encoded.
         */
        fun blobContentUrl(baseUrl: String, hash: String): String {
            require(BLOB_HASH.matches(hash)) { "not a blob hash: $hash" }
            return baseUrl.trimEnd('/') + "/api/v1/blobs/" + hash + "/content"
        }

        private val BLOB_HASH = Regex("^blake3:[0-9a-f]{64}$")

        /** Pure, tested: the playback-negotiation endpoint (write path). */
        fun playbackUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/api/v1/playback"

        /** Pure, tested: the playback-plan endpoint (read path). */
        fun planUrl(baseUrl: String): String = baseUrl.trimEnd('/') + "/api/v1/playback/plan"
    }
}
