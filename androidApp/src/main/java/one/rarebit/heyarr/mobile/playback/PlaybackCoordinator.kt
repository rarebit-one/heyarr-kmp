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
import one.rarebit.heyarr.core.playback.Copies
import one.rarebit.heyarr.core.playback.Copy
import one.rarebit.heyarr.core.playback.DisplayFit
import one.rarebit.heyarr.core.playback.QualityPreference
import one.rarebit.heyarr.mobile.consumption.ProgressReporter
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
    /** Optional starting offset requested by the caller; 0 starts at the beginning. */
    val startSeconds: Double = 0.0,
    /** `watch` | `listen` — the media kind of the active item. */
    val verb: String = "watch",
    /** The work's kind (`movie`, `series`, `music`…), so the player and the bar skin themselves. */
    val kind: String? = null,
    /** The work's poster, for the now-playing bar. */
    val artworkUrl: String? = null,
    /** The work this item belongs to, when the caller knew it — what a copy choice is remembered under. */
    val workId: String? = null,
    /** Every copy of the work held (heyarr-kmp#225); two or more is what shows the Version menu. */
    val copies: List<Copy<WorkAsset>> = emptyList(),
    /** Which of [copies] is playing, null when the item is not one of a film's copies. */
    val copy: Copy<WorkAsset>? = null,
    /** The `max_height` the plan declared, so a re-plan asks for the same picture. */
    val maxHeight: Int? = null,
)

/** A film's copies as this phone sees them — `:core`'s, over the phone's asset model. */
typealias CopyChoice = one.rarebit.heyarr.core.playback.CopyChoice<WorkAsset>

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
    // Test seam; production clients do not report consumption.
    private val reporter: ProgressReporter = ProgressReporter.NoOp,
) {
    /**
     * Settings → Playback and the copy of each film picked on this phone (Version menu, detail
     * list). In memory with the default preference until the ViewModel hands over the phone's.
     */
    @Volatile
    var copies: CopyPreferences = CopyPreferences()

    private val copyChoices: CopyChoiceStore get() = copies.choices

    private fun preference(): QualityPreference = copies.preference()

    /**
     * What this phone can decode, for `POST /playback/plan`. Null (tests, or before the
     * Activity probes `MediaCodecList`) means "don't plan — stream the blob directly".
     */
    @Volatile
    var capabilities: ClientCapabilities? = null

    /** The screen [capabilities] were probed on; before the probe, a 1080-line SDR screen — the cautious guess. */
    private val display: DisplayFit get() = capabilities?.display ?: DisplayFit(DEFAULT_DISPLAY_HEIGHT)

    /**
     * Which of a film's copies this phone plays (heyarr-kmp#225): `:core`'s [Copies] over the
     * screen, the Settings → Playback preference and the copy picked for [workId] on this
     * phone. Null for a work with no video copies; the caller then plays the node's primary
     * asset as before.
     */
    fun copiesFor(workId: String, assets: List<WorkAsset>, defaultId: String?): CopyChoice? =
        Copies.choose(assets, display, preference(), copyChoices.chosen(workId), defaultId)

    /**
     * Play [work] from the start on [choice]'s picked copy, carrying every copy so the player
     * can offer the others. [chosen] marks a copy the person picked just now (the detail
     * screen's list), which is remembered for the work like a Version-menu switch.
     */
    fun playCopy(work: Work, choice: CopyChoice, artworkUrl: String? = null, chosen: Boolean = choice.chosen) {
        val copy = choice.picked
        val hash = copy.asset.blobHash ?: return
        if (chosen) copyChoices.choose(work.id, copy.asset.id)
        val mime = copy.asset.mime ?: work.mime
        playCopyAt(work.id, work.title, work.kind, copy, choice.copies, hash, mime, chosen, 0.0, artworkUrl)
    }

    /**
     * The Version menu: switch the playing film to [copy] at [atSeconds], and remember it as
     * this work's copy on this phone. A plan, not a seek — a different file, so the node is
     * asked again with the cap lifted to the copy (the person asked for that picture).
     */
    fun switchCopy(copy: Copy<WorkAsset>, atSeconds: Double) {
        val playing = _nowPlaying.value ?: return
        val workId = playing.workId
        val hash = copy.asset.blobHash
        if (workId == null || hash == null || copy.asset.id == playing.assetId) return
        copyChoices.choose(workId, copy.asset.id)
        val mime = copy.asset.mime ?: playing.target.mimeType
        playCopyAt(
            workId, playing.title, playing.kind, copy, playing.copies, hash, mime, true, atSeconds, playing.artworkUrl,
        )
    }

    @Suppress("LongParameterList") // one play of one copy: who, what, which file, from where
    private fun playCopyAt(
        workId: String,
        title: String,
        kind: String?,
        copy: Copy<WorkAsset>,
        copies: List<Copy<WorkAsset>>,
        hash: String,
        mime: String?,
        chosen: Boolean,
        atSeconds: Double,
        artworkUrl: String?,
    ) {
        val client = PlaybackClient(transport, baseUrl(), effectiveCred())
        val isVideo = PlaybackTarget.looksLikeVideo(mime, kind)
        val maxHeight = Copies.declaredMaxHeight(copy, display, preference(), chosen)
        val np = NowPlaying(
            target = client.blobTarget(hash, isVideo, mime),
            title = title,
            assetId = copy.asset.id,
            blobHash = hash,
            verb = verbFor(isVideo),
            kind = kind,
            artworkUrl = artworkUrl,
            workId = workId,
            copies = copies,
            copy = copy,
            maxHeight = maxHeight,
        )
        val caps = capabilities
        if (caps == null) {
            present(np, atSeconds)
        } else {
            resolveInto(client, np, caps.forMaxHeight(maxHeight), atSeconds)
        }
    }

    // When there is no credential the caller is browsing as a guest. Play as [Credential.Guest]
    // (sends no Authorization header) so heyarr's trusted-network guest path applies. A vault
    // asset that requires enrolment will 404 at the blob endpoint, which the player surfaces as
    // any other HTTP error — correct per ADR-0074.
    private fun effectiveCred(): Credential = credential() ?: Credential.Guest

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
        val cred = effectiveCred()
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
            present(
                NowPlaying(
                    target = client.blobTarget(hash, isVideo, work.mime),
                    title = work.title,
                    assetId = assetId,
                    blobHash = hash,
                    verb = verbFor(isVideo),
                    kind = work.kind,
                ),
            )
            return
        }
        resolveInto(client, assetId, hash, isVideo, work.mime, work.title, caps, kind = work.kind)
    }

    /** Play one of a work's files: the asset's own blob hash and MIME drive the target. */
    fun playAsset(work: Work, asset: WorkAsset, subtitles: List<WorkAsset> = emptyList()) {
        val cred = effectiveCred()
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
            present(
                NowPlaying(
                    target = client.blobTarget(hash, isVideo, mime).copy(subtitles = subs),
                    title = title,
                    assetId = asset.id,
                    blobHash = hash,
                    verb = verbFor(isVideo),
                    kind = work.kind,
                ),
            )
            return
        }
        resolveInto(client, asset.id, hash, isVideo, mime, title, caps, kind = work.kind, subtitles = subs)
    }

    /**
     * Play a file the caller already resolved to an asset and blob — an episode hit or a
     * continue card. Same plan/fallback path as [playAsset]; [kind] steers video-vs-audio
     * when the MIME does not.
     */
    fun playFile(
        title: String,
        assetId: String,
        blobHash: String,
        mime: String?,
        kind: String?,
        startSeconds: Double? = null,
        artworkUrl: String? = null,
        subtitles: List<WorkAsset> = emptyList(),
    ) {
        val cred = effectiveCred()
        val isVideo = PlaybackTarget.looksLikeVideo(mime, kind)
        val client = PlaybackClient(transport, baseUrl(), cred)
        val subs = client.subtitleSidecars(subtitles)
        val caps = capabilities
        if (caps == null) {
            present(
                NowPlaying(
                    target = client.blobTarget(blobHash, isVideo, mime).copy(subtitles = subs),
                    title = title,
                    assetId = assetId,
                    blobHash = blobHash,
                    verb = verbFor(isVideo),
                    kind = kind,
                    artworkUrl = artworkUrl,
                ),
                startSeconds,
            )
            return
        }
        resolveInto(
            client,
            assetId,
            blobHash,
            isVideo,
            mime,
            title,
            caps,
            startSeconds,
            kind = kind,
            artworkUrl = artworkUrl,
            subtitles = subs,
        )
    }

    /**
     * Put an item in front at the caller's explicit offset, or from the beginning.
     */
    private fun present(np: NowPlaying, knownStart: Double? = null) {
        scope.launch {
            val start = knownStart ?: 0.0
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

    private fun resolveInto(
        client: PlaybackClient,
        assetId: String,
        hash: String,
        isVideo: Boolean,
        mime: String?,
        title: String,
        caps: ClientCapabilities,
        knownStart: Double? = null,
        kind: String? = null,
        artworkUrl: String? = null,
        subtitles: List<PlaybackTarget.Sidecar> = emptyList(),
    ) {
        // Every other play (an episode, a continue card) declares the screen as the
        // preference caps it; only a film's copies lift it to the copy (`playCopyAt`).
        val maxHeight = Copies.declaredMaxHeight(null, display, preference())
        val np = NowPlaying(
            target = client.blobTarget(hash, isVideo, mime).copy(subtitles = subtitles),
            title = title,
            assetId = assetId,
            blobHash = hash,
            verb = verbFor(isVideo),
            kind = kind,
            artworkUrl = artworkUrl,
            maxHeight = maxHeight,
        )
        resolveInto(client, np, caps.forMaxHeight(maxHeight), knownStart)
    }

    /**
     * Plan [np]'s asset with [caps] and present it on whatever came back. [np] carries the
     * direct blob target (its subtitles ride over onto the planned one) and everything else
     * the player shows.
     */
    private fun resolveInto(client: PlaybackClient, np: NowPlaying, caps: ClientCapabilities, knownStart: Double?) {
        val assetId = np.assetId ?: return present(np, knownStart)
        val hash = np.blobHash ?: return present(np, knownStart)
        val direct = np.target
        // Plan first: the node may repackage for this phone. A node that predates the
        // contract answers 400 and `resolve` falls back to the blob.
        scope.launch {
            val target = withContext(io) {
                runCatching { client.resolve(assetId, hash, direct.isVideo, direct.mimeType, caps) }
                    .getOrElse { direct.copy(reason = it.message) }
            }.copy(subtitles = direct.subtitles)
            present(np.copy(target = target), knownStart)
        }
    }

    /**
     * The player found a renderer type Media3 can't decode. If the node spoke the plan
     * contract and judged the blob direct, ask ONCE more with that codec struck off — a
     * `stream` answer swaps the target; anything else leaves an honest banner.
     */
    fun onIssue(issue: PlaybackDiagnostics.Issue) {
        val playing = _nowPlaying.value ?: return
        val cred = effectiveCred()
        val caps = capabilities ?: return
        if (playing.target.origin != PlaybackTarget.Origin.DIRECT_PLANNED || playing.replanned) return
        val assetId = playing.assetId ?: return
        val hash = playing.blobHash ?: return
        val codec = issue.codec ?: return
        val client = PlaybackClient(transport, baseUrl(), cred)
        val declared = playing.maxHeight?.let { caps.forMaxHeight(it) } ?: caps
        scope.launch {
            val replanned = withContext(io) {
                runCatching {
                    client.resolve(
                        assetId,
                        hash,
                        playing.target.isVideo,
                        playing.target.mimeType,
                        declared.without(codec),
                    )
                }.getOrNull()
            }
            if (_nowPlaying.value !== playing) return@launch // the user moved on
            if (replanned != null && replanned.origin == PlaybackTarget.Origin.STREAM) {
                _nowPlaying.value = playing.copy(target = replanned, replanned = true)
            } else {
                _nowPlaying.value =
                    playing.copy(banner = PlaybackDiagnostics.afterReplanFailed(issue), replanned = true)
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

    private companion object {
        const val DEFAULT_DISPLAY_HEIGHT = 1080
    }
}
