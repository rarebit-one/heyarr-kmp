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
import one.rarebit.heyarr.core.playback.PlaybackPreference
import one.rarebit.heyarr.core.playback.QualityPreference
import one.rarebit.heyarr.core.playback.ResumeItem
import one.rarebit.heyarr.core.playback.ResumePoint
import one.rarebit.heyarr.core.playback.ResumePoints
import one.rarebit.heyarr.core.playback.ResumeStore
import one.rarebit.heyarr.mobile.cast.CastGate
import one.rarebit.heyarr.mobile.consumption.ProgressReporter
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.mobile.library.WorkDetailClient

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
    /** The `preference` the plan sent (heyarr-core#887), so a re-plan sends it again; null sent none. */
    val preference: PlaybackPreference? = null,
    /**
     * Which play this is, counted by the coordinator: two presses of Start over on the same
     * film are two requests, and an identical value would be swallowed by the state flow.
     */
    val request: Int = 0,
    /**
     * Load it paused: what a process that died in the background was playing, put back where
     * it was (heyarr-kmp#232). It waits in the now-playing bar rather than taking the screen.
     */
    val startPaused: Boolean = false,
) {
    /** What a resume point of this item is about (heyarr-kmp#232); null without an asset to key it by. */
    fun resumeItem(): ResumeItem? = assetId?.let {
        ResumeItem(it, workId, title, kind, blobHash, target.mimeType, artworkUrl)
    }
}

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

    /**
     * Where each item was left on this phone (heyarr-kmp#232): a play the caller gave no
     * position for resumes there. Null (tests, previews) starts every play at the beginning.
     */
    @Volatile
    var resume: ResumeStore? = null

    private var requests = 0

    private fun preference(): PlaybackPreference = copies.preference()

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
     * Play [work] on [choice]'s picked copy, carrying every copy so the player can offer the
     * others. [chosen] marks a copy the person picked just now (the detail screen's list),
     * which is remembered for the work like a Version-menu switch. [startSeconds] null resumes
     * where the film was left on this phone, on whichever copy; 0 is Start over.
     */
    fun playCopy(
        work: Work,
        choice: CopyChoice,
        artworkUrl: String? = null,
        chosen: Boolean = choice.chosen,
        startSeconds: Double? = null,
    ) {
        val copy = choice.picked
        val hash = copy.asset.blobHash ?: return
        if (chosen) copyChoices.choose(work.id, copy.asset.id)
        val mime = copy.asset.mime ?: work.mime
        playCopyAt(work.id, work.title, work.kind, copy, choice.copies, hash, mime, chosen, startSeconds, artworkUrl)
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
        atSeconds: Double?,
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
            // A copy picked by hand is the picture the person asked for: no preference
            // narrows it, and its own height is the cap (heyarr-core#887).
            preference = if (chosen) null else preference(),
        )
        val caps = capabilities?.forMaxHeight(maxHeight)?.withPreference(np.preference)
        when {
            caps == null -> present(np, atSeconds)

            // A copy the person picked is planned by asset: an explicit asset always wins.
            chosen -> resolveInto(client, np, caps, atSeconds)

            else -> resolveByWork(client, np, caps, atSeconds)
        }
    }

    /**
     * Plan [np]'s film by `work_id` (heyarr-core#885): the node chooses the copy from what
     * this phone declares — the capped height, HDR, the codecs it decodes, which the phone's
     * own rule cannot see — and plans it, under the person's preference; [np] then plays the
     * copy the node chose. A node that does not plan by film (400), or answers with a copy the
     * phone does not hold, falls back to planning the copy the phone chose itself, which is how
     * every play worked before. So does best quality on a node that did not apply the
     * preference (before heyarr-core#887): to it, the screen is still the limit.
     */
    private fun resolveByWork(client: PlaybackClient, np: NowPlaying, caps: ClientCapabilities, knownStart: Double?) {
        val workId = np.workId ?: return resolveInto(client, np, caps, knownStart)
        scope.launch {
            val bestUnheard = { p: PlaybackClient.WorkPlan ->
                caps.preference?.quality == QualityPreference.BEST && !p.preferenceApplied
            }
            val plan = withContext(io) { runCatching { client.planWork(workId, caps) }.getOrNull() }
                ?.takeIf { it.result !is PlaybackClient.PlanResult.Unavailable && !bestUnheard(it) }
            val copy = plan?.assetId?.let { id -> np.copies.firstOrNull { it.asset.id == id } }
            val hash = copy?.asset?.blobHash
            if (plan == null || copy == null || hash == null) {
                resolveInto(client, np, caps, knownStart)
                return@launch
            }
            val mime = copy.asset.mime ?: np.target.mimeType
            val target = client.targetFor(plan.result, hash, np.target.isVideo, mime)
                .copy(subtitles = np.target.subtitles)
            present(np.copy(target = target, assetId = copy.asset.id, blobHash = hash, copy = copy), knownStart)
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
                    workId = work.id,
                ),
            )
            return
        }
        resolveInto(client, assetId, hash, isVideo, work.mime, work.title, caps, kind = work.kind, workId = work.id)
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
                    workId = work.id,
                ),
            )
            return
        }
        resolveInto(
            client, asset.id, hash, isVideo, mime, title, caps, kind = work.kind, subtitles = subs, workId = work.id,
        )
    }

    /**
     * Play a file the caller already resolved to an asset and blob — an episode hit or a
     * continue card. Same plan/fallback path as [playAsset]; [kind] steers video-vs-audio
     * when the MIME does not. [workId] files the resume point under its work, so a series'
     * episodes share one Continue watching card.
     */
    @Suppress("LongParameterList") // one resolved file: what it is, from where, and whose
    fun playFile(
        title: String,
        assetId: String,
        blobHash: String,
        mime: String?,
        kind: String?,
        startSeconds: Double? = null,
        artworkUrl: String? = null,
        subtitles: List<WorkAsset> = emptyList(),
        workId: String? = null,
        startPaused: Boolean = false,
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
                    workId = workId,
                    startPaused = startPaused,
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
            workId = workId,
            startPaused = startPaused,
        )
    }

    /**
     * Put back what the player held when a process died (heyarr-kmp#232): the same file, at
     * the same position, paused. Planned afresh, as any play is: a stream URL or lease from
     * before the process died may well have expired.
     */
    fun restore(point: ResumePoint) {
        val hash = point.item.blobHash ?: return
        playFile(
            point.item.title, point.assetId, hash, point.item.mime, point.item.kind,
            startSeconds = point.positionSeconds, artworkUrl = point.item.artworkUrl, workId = point.workId,
            startPaused = true,
        )
    }

    /**
     * Put an item in front at the caller's explicit offset; without one, where it was left on
     * this phone (on any copy of the same film), else from the beginning (heyarr-kmp#232). The
     * lookup is made here, after the plan, because only now is the copy known.
     */
    private fun present(np: NowPlaying, knownStart: Double? = null) {
        scope.launch {
            val full = withCopies(np)
            val start = knownStart ?: resumeStart(full) ?: 0.0
            val playing = full.copy(startSeconds = start.coerceAtLeast(0.0))
            val sink = cast
            when (sink?.route(playing) ?: CastGate.Route.LOCAL) {
                CastGate.Route.LOCAL -> Unit

                CastGate.Route.CAST -> {
                    sink?.send(playing)
                    return@launch
                }

                CastGate.Route.BLOCKED -> {
                    _notice.value = "Playing on ${sink?.device ?: "the TV"}. Stop casting to watch it here."
                    return@launch
                }
            }
            playing.assetId?.let { reporter.begin(it, playing.verb) }
            _nowPlaying.value = playing.copy(request = ++requests)
        }
    }

    /**
     * Google Cast's door into playback (heyarr-kmp#250): while a session is up, a video goes
     * to the TV instead of this phone's player, and the film this phone is casting cannot
     * start here beside it ([CastGate]). Not a player: the coordinator still plans and finds
     * the resume point, then hands the film over.
     */
    interface CastSink {
        fun route(np: NowPlaying): CastGate.Route

        fun send(np: NowPlaying)

        /** The TV the cast is on, for the notice that it is playing there. */
        val device: String?
    }

    /** Where a video goes while Google Cast is up; null (tests, previews, no Play services) plays everything here. */
    @Volatile
    var cast: CastSink? = null

    /**
     * A film that comes back by its file alone (put back after process death, a Continue
     * watching card) carries no list of its copies, so the player had no Version menu for it
     * (heyarr-kmp#232 item 6). List them again, as the detail screen does, and keep the copy it
     * was left on, which is the copy chosen for it. Films only: a series' files are episodes,
     * not copies. Without an answer from the node it plays as it came.
     */
    private suspend fun withCopies(np: NowPlaying): NowPlaying {
        val workId = np.workId?.takeIf { np.kind == FILM && np.copies.isEmpty() } ?: return np
        val assets = withContext(io) {
            runCatching { WorkDetailClient(transport, baseUrl(), effectiveCred()).assetsForWork(workId) }.getOrNull()
        }
        val copies = Copies.of(assets.orEmpty())
        val copy = copies.firstOrNull { it.asset.id == np.assetId }
        return if (copy == null) np else np.copy(copies = copies, copy = copy)
    }

    private fun resumeStart(np: NowPlaying): Double? {
        val store = resume
        val assetId = np.assetId
        if (store == null || assetId == null) return null
        return ResumePoints.startFor(store, assetId, np.copies.map { it.asset.id })
    }

    private fun verbFor(isVideo: Boolean) = if (isVideo) "watch" else "listen"

    // ── What the player tells us ──────────────────────────────────────────────────
    fun reportProgress(seconds: Double) = reporter.progress(seconds)
    fun reportPause(seconds: Double) = reporter.pause(seconds)
    fun reportResume(seconds: Double) = reporter.resume(seconds)
    fun reportEnded(seconds: Double, completed: Boolean) = reporter.end(seconds, completed)

    @Suppress("LongParameterList") // the NowPlaying it builds, field by field
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
        workId: String? = null,
        startPaused: Boolean = false,
    ) {
        // Every other play (an episode, a continue card) declares the screen as the
        // preference caps it; only a film's copies lift it to the copy (`playCopyAt`).
        val preference = preference()
        val maxHeight = Copies.declaredMaxHeight(null, display, preference)
        val np = NowPlaying(
            target = client.blobTarget(hash, isVideo, mime).copy(subtitles = subtitles),
            title = title,
            assetId = assetId,
            blobHash = hash,
            verb = verbFor(isVideo),
            kind = kind,
            artworkUrl = artworkUrl,
            maxHeight = maxHeight,
            preference = preference,
            workId = workId,
            startPaused = startPaused,
        )
        resolveInto(client, np, caps.forMaxHeight(maxHeight).withPreference(preference), knownStart)
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
        val declared = asDeclared(caps, playing)
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

    /**
     * The playing item's stream URL or lease lapsed mid-film (401 / 403 / 410, heyarr-kmp#232):
     * plan the same file again, with what this phone declared the first time, and carry on at
     * [atSeconds], playing if [play]. A target that was never planned is the blob again, whose
     * reads re-stamp a live credential each time.
     */
    fun refresh(atSeconds: Double, play: Boolean) {
        val playing = _nowPlaying.value ?: return
        val hash = playing.blobHash
        val client = PlaybackClient(transport, baseUrl(), effectiveCred())
        val direct = if (hash != null) {
            client.blobTarget(hash, playing.target.isVideo, playing.target.mimeType)
                .copy(subtitles = playing.target.subtitles)
        } else {
            playing.target
        }
        val np = playing.copy(target = direct, startPaused = !play)
        val caps = capabilities
        if (caps == null || playing.assetId == null || hash == null) return present(np, atSeconds)
        resolveInto(client, np, asDeclared(caps, playing), atSeconds)
    }

    /** [caps] as [playing]'s plan declared them: the same height and the same preference. */
    private fun asDeclared(caps: ClientCapabilities, playing: NowPlaying): ClientCapabilities =
        (playing.maxHeight?.let { caps.forMaxHeight(it) } ?: caps).withPreference(playing.preference)

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
        const val FILM = "movie"
    }
}
