package one.rarebit.heyarr.mobile.playback

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.WorkAsset

/**
 * A resolved item the player is showing: its stream target, a display title, the asset
 * it came from (so a decoder the phone lacks can be re-planned into a stream, once),
 * and a banner the app decided on after that re-plan.
 */
data class NowPlaying(
    val target: PlaybackTarget,
    val title: String,
    val assetId: String? = null,
    val blobHash: String? = null,
    val banner: String? = null,
    val replanned: Boolean = false,
    /** Where to start, in seconds — the continue rail's position, or 0. */
    val startSeconds: Double = 0.0,
    /** `watch` | `listen` — what the consumption session records. */
    val verb: String = "watch",
    /** The work's kind (`movie`, `series`, `music`…), so the player and the bar skin themselves. */
    val kind: String? = null,
    /** The work's poster, for the now-playing bar. */
    val artworkUrl: String? = null,
)

/**
 * What is playing, and how it came to be: the one place that turns a tapped work or
 * file into a [PlaybackTarget] — planning against this phone's real capabilities
 * (heyarr-core #432), falling back to the direct blob when the node predates the
 * contract, and re-planning ONCE when the player hits a codec it cannot decode.
 *
 * Extracted from the app ViewModel so it is testable over a scripted transport and so
 * a full-screen player route can read it without the ViewModel in the middle.
 * [credential] and [baseUrl] are read per call: both change over a session.
 */
class PlaybackCoordinator(
    private val transport: HttpTransport,
    private val baseUrl: () -> String,
    private val credential: () -> Credential?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Tells the node where playback has reached; NoOp when it cannot write. */
    private val reporter: one.rarebit.heyarr.mobile.consumption.ProgressReporter = one.rarebit.heyarr.mobile.consumption.ProgressReporter.NoOp,
    /** The position to resume an asset from, if the node remembers one (the continue rail). Called on IO. */
    private val resumeAt: (assetId: String) -> Double? = { null },
) {
    /**
     * What this phone can decode, for `POST /playback/plan`. Null (tests, or before the
     * Activity probes `MediaCodecList`) means "don't plan — stream the blob directly".
     */
    @Volatile
    var capabilities: ClientCapabilities? = null

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    /** A one-shot notice ("this row has nothing to stream") the UI shows once and clears. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /**
     * Play a browse row. A row that carries a content hash streams directly over the
     * range-capable blob endpoint; one without a hash has nothing to point a player at.
     */
    fun play(work: Work) {
        val cred = credential() ?: return
        val hash = work.blobHash
        if (hash.isNullOrBlank()) {
            _notice.value = "“${work.title}” has no directly-streamable file yet."
            return
        }
        val isVideo = PlaybackTarget.looksLikeVideo(work.mime, work.kind)
        val client = PlaybackClient(transport, baseUrl(), cred)
        val caps = capabilities
        val assetId = work.primaryAssetId
        if (caps == null || assetId == null) {
            present(NowPlaying(target = client.blobTarget(hash, isVideo, work.mime), title = work.title, assetId = assetId, blobHash = hash, verb = verbFor(isVideo), kind = work.kind))
            return
        }
        resolveInto(client, assetId, hash, isVideo, work.mime, work.title, caps, kind = work.kind)
    }

    /** Play one of a work's files: the asset's own blob hash and MIME drive the target. */
    fun playAsset(work: Work, asset: WorkAsset, subtitles: List<WorkAsset> = emptyList()) {
        val cred = credential() ?: return
        val hash = asset.blobHash
        if (hash.isNullOrBlank()) {
            _notice.value = "“${asset.filename ?: work.title}” has no blob to stream (a linked asset has none)."
            return
        }
        val mime = asset.mime ?: work.mime
        val isVideo = PlaybackTarget.looksLikeVideo(mime, work.kind)
        val title = asset.filename?.let { "${work.title} — $it" } ?: work.title
        val client = PlaybackClient(transport, baseUrl(), cred)
        val subs = client.subtitleSidecars(subtitles)
        val caps = capabilities
        if (caps == null) {
            present(NowPlaying(target = client.blobTarget(hash, isVideo, mime).copy(subtitles = subs), title = title, assetId = asset.id, blobHash = hash, verb = verbFor(isVideo), kind = work.kind))
            return
        }
        resolveInto(client, asset.id, hash, isVideo, mime, title, caps, kind = work.kind, subtitles = subs)
    }

    /**
     * Play a file the caller already resolved to an asset and blob — an episode hit or a
     * continue card. Same plan/fallback path as [playAsset]; [kind] steers video-vs-audio
     * when the MIME does not.
     */
    fun playFile(title: String, assetId: String, blobHash: String, mime: String?, kind: String?, startSeconds: Double? = null, artworkUrl: String? = null, subtitles: List<WorkAsset> = emptyList()) {
        val cred = credential() ?: return
        val isVideo = PlaybackTarget.looksLikeVideo(mime, kind)
        val client = PlaybackClient(transport, baseUrl(), cred)
        val subs = client.subtitleSidecars(subtitles)
        val caps = capabilities
        if (caps == null) {
            present(NowPlaying(target = client.blobTarget(blobHash, isVideo, mime).copy(subtitles = subs), title = title, assetId = assetId, blobHash = blobHash, verb = verbFor(isVideo), kind = kind, artworkUrl = artworkUrl), startSeconds)
            return
        }
        resolveInto(client, assetId, blobHash, isVideo, mime, title, caps, startSeconds, kind = kind, artworkUrl = artworkUrl, subtitles = subs)
    }

    /**
     * Put an item in front: look up where the node last saw this asset (unless the
     * caller already knows), open a consumption session, then show it.
     */
    private fun present(np: NowPlaying, knownStart: Double? = null) {
        scope.launch {
            val start = knownStart ?: np.assetId?.let { id -> withContext(io) { runCatching { resumeAt(id) }.getOrNull() } } ?: 0.0
            np.assetId?.let { reporter.begin(it, np.verb) }
            _nowPlaying.value = np.copy(startSeconds = start.coerceAtLeast(0.0))
        }
    }

    private fun verbFor(isVideo: Boolean) = if (isVideo) "watch" else "listen"

    // ── What the player tells us ──────────────────────────────────────────────────
    fun reportProgress(seconds: Double) = reporter.progress(seconds)
    fun reportPause(seconds: Double) = reporter.pause(seconds)
    fun reportResume(seconds: Double) = reporter.resume(seconds)
    fun reportEnded(seconds: Double, completed: Boolean) = reporter.end(seconds, completed)

    private fun resolveInto(client: PlaybackClient, assetId: String, hash: String, isVideo: Boolean, mime: String?, title: String, caps: ClientCapabilities, knownStart: Double? = null, kind: String? = null, artworkUrl: String? = null, subtitles: List<PlaybackTarget.Sidecar> = emptyList()) {
        // Plan first: the node may repackage for this phone. A node that predates the
        // contract answers 400 and `resolve` falls back to the blob.
        scope.launch {
            val target = withContext(io) {
                runCatching { client.resolve(assetId, hash, isVideo, mime, caps) }
                    .getOrElse { client.blobTarget(hash, isVideo, mime).copy(reason = it.message) }
            }.copy(subtitles = subtitles)
            present(NowPlaying(target = target, title = title, assetId = assetId, blobHash = hash, verb = verbFor(isVideo), kind = kind, artworkUrl = artworkUrl), knownStart)
        }
    }

    /**
     * The player found a renderer type Media3 can't decode. If the node spoke the plan
     * contract and judged the blob direct, ask ONCE more with that codec struck off — a
     * `stream` answer swaps the target; anything else leaves an honest banner.
     */
    fun onIssue(issue: PlaybackDiagnostics.Issue) {
        val playing = _nowPlaying.value ?: return
        val cred = credential() ?: return
        val caps = capabilities ?: return
        if (playing.target.origin != PlaybackTarget.Origin.DIRECT_PLANNED || playing.replanned) return
        val assetId = playing.assetId ?: return
        val hash = playing.blobHash ?: return
        val codec = issue.codec ?: return
        val client = PlaybackClient(transport, baseUrl(), cred)
        scope.launch {
            val replanned = withContext(io) {
                runCatching { client.resolve(assetId, hash, playing.target.isVideo, playing.target.mimeType, caps.without(codec)) }.getOrNull()
            }
            if (_nowPlaying.value !== playing) return@launch // the user moved on
            if (replanned != null && replanned.origin == PlaybackTarget.Origin.STREAM) {
                _nowPlaying.value = playing.copy(target = replanned, replanned = true)
            } else {
                _nowPlaying.value = playing.copy(banner = PlaybackDiagnostics.afterReplanFailed(issue), replanned = true)
            }
        }
    }

    /** Close the player and release its target. The player reports the final position itself. */
    fun stop() {
        _nowPlaying.value = null
    }

    /** Clear the transient notice once shown. */
    fun clearNotice() {
        _notice.value = null
    }
}
