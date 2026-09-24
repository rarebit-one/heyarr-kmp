package one.rarebit.heyarr.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.rarebit.heyarr.mobile.catalog.ContinueEntry
import one.rarebit.heyarr.core.heyarr.Candidate
import one.rarebit.heyarr.core.heyarr.DesiredItem
import one.rarebit.heyarr.mobile.heyarr.HeyarrApi
import one.rarebit.heyarr.mobile.heyarr.McpResult
import one.rarebit.heyarr.mobile.library.Season
import one.rarebit.heyarr.core.library.Series
import one.rarebit.heyarr.core.library.Variants
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.core.mcp.ExternalId
import one.rarebit.heyarr.core.mcp.Renderer
import one.rarebit.heyarr.core.mcp.Replica
import one.rarebit.heyarr.core.mcp.Satisfaction
import one.rarebit.heyarr.mobile.music.Tracks
import one.rarebit.heyarr.mobile.nav.Route
import one.rarebit.heyarr.mobile.personalstate.ItemRef
import one.rarebit.heyarr.mobile.playback.QueueEntry
import one.rarebit.heyarr.mobile.reader.ReaderFormat
import one.rarebit.heyarr.mobile.search.FollowedItem
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.core.state.ExternalEpisode
import one.rarebit.heyarr.core.state.ExternalMeta
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.state.MetaKey
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.ui.theme.LocalMediaTheme
import one.rarebit.heyarr.ui.theme.MediaScope
import one.rarebit.heyarr.ui.theme.MediaThemes
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.ui.components.ErrorState
import one.rarebit.heyarr.ui.components.FilterChip
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.mobile.ui.components.Hero
import one.rarebit.heyarr.mobile.ui.components.HeroSkeleton
import one.rarebit.heyarr.ui.components.IconButtonRound
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.Panel
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.components.Skeleton
import one.rarebit.heyarr.ui.components.focusRing
import one.rarebit.heyarr.mobile.ui.components.rememberCover

/** The two faces of a work: what you came to watch, and the tooling that keeps it that way. */
enum class DetailTab(val label: String) { WATCH("Watch"), CURATE("Curate") }

/** Everything the detail screen loads for one work, each piece independently. */
class DetailState(val workId: String) {
    var tab by mutableStateOf(DetailTab.WATCH)
    var detail by mutableStateOf<Work?>(null)
    var detailError by mutableStateOf<String?>(null)
    var loading by mutableStateOf(true)
    var assets by mutableStateOf<List<WorkAsset>?>(null)
    var season by mutableStateOf<Int?>(null)
    var continueEntry by mutableStateOf<ContinueEntry?>(null)
    var feedItems by mutableStateOf<List<FollowedItem>?>(null)
    var externalIds by mutableStateOf<List<ExternalId>>(emptyList())
    var satisfaction by mutableStateOf<Map<String, McpResult<Satisfaction?>>>(emptyMap())
    var candidates by mutableStateOf<Map<String, List<Candidate>>>(emptyMap())
    var replicas by mutableStateOf<McpResult<List<Replica>>?>(null)
    var renderers by mutableStateOf<List<Renderer>?>(null)
    /** The asset a "Play on…" picker is open for, if any. */
    var castAssetId by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)
    var wantMenu by mutableStateOf(false)
    /** What a public source said about this work (cover, synopsis, TVmaze id) — labelled as external wherever shown. */
    var external by mutableStateOf<ExternalMeta?>(null)
    var externalEpisodes by mutableStateOf<List<ExternalEpisode>>(emptyList())
    /** Works the scanner minted for the same title's download folders (heyarr-core#470), folded under this one. */
    var variants by mutableStateOf<List<Work>>(emptyList())
}

/** How the detail screen starts playback — the phone's players, wired by the shell. */
data class DetailPlayback(
    /** Play a video file in the in-app player, with the work's other episodes as "up next". */
    val playVideo: (work: Work, assetId: String, blobHash: String, mime: String?, title: String, startSeconds: Double?, queue: List<QueueEntry>, artworkUrl: String?, subtitles: List<WorkAsset>) -> Unit,
    /** Queue audio tracks (an album, an audiobook) from [start]. */
    val playAudio: (work: Work, tracks: List<WorkAsset>, start: Int) -> Unit,
    /** Open a readable file (EPUB / PDF / comic) in the reader. */
    val read: (work: Work, asset: WorkAsset) -> Unit,
)

/**
 * The per-item personal-state affordances (★ / Add to playlist) the detail screen
 * offers on an individual **track** or **file** (issue #41): [starredIds] holds the
 * raw CRDT entry ids so a row can show its own ★ state, and the callbacks take an
 * already-encoded entry id ([ItemRef.encode]). Disabled (and hidden) when this device
 * holds no personal-state key. The whole-work affordances still live on the cards.
 */
data class DetailPersonal(
    val enabled: Boolean = false,
    val starredIds: Set<String> = emptySet(),
    val onToggleStar: (itemId: String) -> Unit = {},
    val onAddToPlaylist: (itemId: String) -> Unit = {},
)

/** ★ and Add-to-playlist for one file, keyed by its `asset:<id>` entry id. Hidden when disabled. */
@Composable
internal fun AssetPersonalActions(personal: DetailPersonal, assetId: String, title: String) {
    if (!personal.enabled) return
    val entryId = ItemRef.asset(assetId).encode()
    val starred = entryId in personal.starredIds
    IconButtonRound(
        if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
        if (starred) "Unstar $title" else "Star $title",
        { personal.onToggleStar(entryId) }, size = 36.dp,
    )
    IconButtonRound(Icons.Rounded.PlaylistAdd, "Add $title to a playlist", { personal.onAddToPlaylist(entryId) }, size = 36.dp)
}

/**
 * The one adaptive detail template, built for consumption first — ported from
 * heyarr-desktop's `DetailScreen`. **Watch** is what you came for: the art, a synopsis
 * when the node has one (a public source's, labelled, when it has not; an honest line
 * when neither knows), and the thing itself — seasons and episodes with their
 * thumbnails for a series, tracks for an album, the file for a film, the archive for a
 * feed. **Curate** keeps every technical surface — status, held files with verdicts,
 * indexer candidates, scoring, health, captions and artwork, variants — one tab away.
 */
@Composable
fun DetailScreen(session: AppSession, route: Route.Detail, state: DetailState, play: DetailPlayback, onBack: () -> Unit, onOpen: (Route) -> Unit, onWant: (String, String) -> Unit, modifier: Modifier = Modifier, personal: DetailPersonal = DetailPersonal()) {
    val scope = rememberCoroutineScope()
    val wants: List<DesiredItem> = session.index.wantsFor(route.workId)
    val detail = state.detail
    val type = detail?.kind?.let { MediaType.from(it) } ?: route.typeHint

    fun load() {
        val a = session.api
        state.loading = true; state.detailError = null
        scope.launch {
            session.io { a.work(route.workId) }.fold(
                onSuccess = { d -> state.detail = d; state.detailError = if (d == null) "This work no longer exists." else null },
                onFailure = { state.detailError = it.message },
            )
            state.loading = false
            val d = state.detail ?: return@launch
            if (session.externalMetadata) {
                val t = MediaType.from(d.kind)
                val feedRef = if (t == MediaType.FEED || t == MediaType.PODCAST) session.io { a.followed() }.getOrNull()?.firstOrNull { it.workId == d.id }?.feedRef else null
                val meta = session.external.lookup(MetaKey(t, d.title, d.year, d.artist ?: d.author, feedRef))
                state.external = meta
                meta?.tvmazeId?.let { id -> state.externalEpisodes = session.external.episodes(id) }
            }
        }
        scope.launch { session.io { a.assets(route.workId) }.onSuccess { state.assets = it } }
        scope.launch { session.io { a.externalIds(route.workId) }.onSuccess { state.externalIds = it } }
        scope.launch { session.io { a.works() }.onSuccess { all -> state.variants = Variants.group(all)[route.workId].orEmpty() } }
        scope.launch { session.io { a.continueRail() }.onSuccess { list -> state.continueEntry = list.firstOrNull { it.workId == route.workId } } }
    }
    fun loadWants() {
        val a = session.api
        for (w in wants) {
            if (w.id.startsWith("pending:")) continue
            scope.launch { session.io { a.satisfaction(w.id) }.onSuccess { r -> state.satisfaction = state.satisfaction + (w.id to r) } }
            scope.launch { session.io { a.candidates(w.id) }.onSuccess { c -> state.candidates = state.candidates + (w.id to (c?.candidates ?: emptyList())) } }
        }
    }
    LaunchedEffect(route.workId) { if (route.curate) state.tab = DetailTab.CURATE; if (state.detail == null) load() }
    LaunchedEffect(wants.map { it.id }) { loadWants() }
    LaunchedEffect(detail?.blobHash) {
        val hash = detail?.blobHash ?: return@LaunchedEffect
        session.io { session.api.replicas(hash) }.onSuccess { state.replicas = it }
    }
    LaunchedEffect(type, route.workId) {
        if (type != MediaType.FEED && type != MediaType.PODCAST) return@LaunchedEffect
        val source = session.io { session.api.followed() }.getOrNull()?.firstOrNull { it.workId == route.workId } ?: return@LaunchedEffect
        session.io { session.api.followedItems(source.id) }.onSuccess { state.feedItems = it }
    }

    val seasons = remember(state.assets, type) { if (type == MediaType.SERIES || Series.isSeries(detail?.kind)) Series.seasons(state.assets.orEmpty()) else emptyList() }
    val cover by rememberCover(session, type, detail?.title ?: route.title ?: "", detail?.artworkPath, detail?.year, detail?.artist ?: detail?.author)

    MediaScope(type) {
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Tokens.screenPadding, vertical = Tokens.s3), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GhostButton(route.from, onBack, icon = Icons.Rounded.ArrowBack)
                    Spacer(Modifier.weight(1f))
                    TabSwitch(state.tab, onSelect = { state.tab = it })
                }
            }
            item {
                when {
                    state.loading && detail == null -> HeroSkeleton(300.dp)
                    detail == null -> ErrorState("Couldn't load this work", state.detailError, onRetry = ::load)
                    else -> DetailHero(session, detail, type, wants, state, seasons, cover.url, play, onWant)
                }
            }
            if (detail != null) {
                item { SynopsisBlock(detail, state, cover.url != null && detail.artworkPath == null) }
                if (state.tab == DetailTab.WATCH) {
                    when (type) {
                        MediaType.SERIES -> item { SeasonsBlock(session, detail, seasons, state, wants, play, cover.url) }
                        MediaType.MUSIC, MediaType.AUDIOBOOK -> item { TracksBlock(session, detail, state, play, personal) }
                        MediaType.FEED, MediaType.PODCAST -> item { ArchiveBlock(state, onOpen) }
                        MediaType.BOOK -> item { BookFilesBlock(detail, state, play, personal) }
                        else -> item { FileBlock(detail, state) }
                    }
                } else {
                    item { CurateTab(session, detail, type, wants, state, seasons, onOpen, ::loadWants) }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun TabSwitch(current: DetailTab, onSelect: (DetailTab) -> Unit) {
    Row(Modifier.background(Tokens.surface1, RectangleShape).border(Tokens.hairline, Tokens.border, RectangleShape).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (t in DetailTab.entries) {
            val active = t == current
            val theme = LocalMediaTheme.current
            val interaction = remember { MutableInteractionSource() }
            Row(
                Modifier.focusRing(interaction, RectangleShape).clip(RectangleShape)
                    .background(if (active) theme.tint(0.22f) else Color.Transparent, RectangleShape)
                    .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = { onSelect(t) })
                    .semantics { contentDescription = t.label + if (active) ", selected" else "" }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(if (t == DetailTab.WATCH) Icons.Rounded.PlayArrow else Icons.Rounded.Build, contentDescription = null, tint = if (active) theme.accentGradientEnd else Tokens.textMuted, modifier = Modifier.size(14.dp))
                Text(t.label.uppercase(), style = MaterialTheme.typography.labelLarge, color = if (active) Tokens.textPrimary else Tokens.textMuted)
            }
        }
    }
}

/** The work's playable episodes as the player's "up next" list. */
internal fun queueOf(work: Work, seasons: List<Season>, session: AppSession): List<QueueEntry> =
    seasons.flatMap { it.episodes }.filter { it.isPlayable }.map { e ->
        QueueEntry(e.asset.id, e.asset.blobHash!!, work.title, e.label, e.asset.mime ?: work.mime, work.kind, e.thumbnailPath?.let { HeyarrApi.blobUrlFromPath(session.baseUrl, it) }, e.asset.sizeBytes)
    }

@Composable
private fun DetailHero(session: AppSession, work: Work, type: MediaType, wants: List<DesiredItem>, state: DetailState, seasons: List<Season>, art: String?, play: DetailPlayback, onWant: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    val theme = MediaThemes.of(type)
    val status = session.index.statusOf(work.id)
    val hash = work.blobHash
    val cont = state.continueEntry
    val first = Series.firstPlayable(seasons)
    val held = seasons.sumOf { it.held }
    val primaryFile = state.assets?.firstOrNull { it.blobHash == hash }
    val meta = when (type) {
        MediaType.SERIES -> listOf(work.year?.toString(), if (seasons.isNotEmpty()) "${seasons.count { it.number != null && it.number != 0 }} seasons" else null, if (state.assets != null) "$held episodes held" else null)
        MediaType.MOVIE -> listOf(work.year?.toString(), primaryFile?.let { Series.qualityTags(it).joinToString(" · ").ifBlank { null } }, primaryFile?.sizeBytes?.let { WorkAsset.formatBytes(it) })
        MediaType.BOOK -> listOf(work.author, work.year?.toString(), work.mime?.substringAfter('/')?.uppercase())
        MediaType.MUSIC, MediaType.AUDIOBOOK -> listOf(work.artist ?: work.author, work.year?.toString(), "${Tracks.playable(state.assets.orEmpty()).size} tracks")
        else -> listOf(work.year?.toString(), work.kind)
    }
    val readable = state.assets.orEmpty().firstOrNull { it.isPlayable && ReaderFormat.of(it.mime, it.filename)?.let { f -> f != ReaderFormat.AUDIOBOOK } == true }
    val audioTracks = Tracks.playable(state.assets.orEmpty())

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Hero(
            title = work.title, type = type, meta = meta, artwork = art, status = status, height = 300.dp,
            kicker = cont?.let { "Continue · ${it.editionLabel ?: ""} ${it.progressLabel ?: ""}".trim() },
            primary = {
                when {
                    cont?.blobHash != null && type != MediaType.BOOK && type != MediaType.MUSIC && type != MediaType.AUDIOBOOK ->
                        PrimaryButton("Continue", { play.playVideo(work, cont.assetId, cont.blobHash, cont.mime ?: work.mime, "${work.title} — ${cont.subtitle ?: cont.editionLabel ?: ""}".trimEnd(' ', '—'), cont.positionSeconds, queueOf(work, seasons, session), art, emptyList()) }, icon = Icons.Rounded.PlayArrow, enabled = state.busy == null)
                    type == MediaType.SERIES && first != null ->
                        PrimaryButton("Play ${first.code ?: ""}".trim(), { play.playVideo(work, first.asset.id, first.asset.blobHash!!, first.asset.mime ?: work.mime, Series.playTitle(work, first), null, queueOf(work, seasons, session), art, first.subtitles) }, icon = Icons.Rounded.PlayArrow, enabled = state.busy == null)
                    (type == MediaType.MUSIC || type == MediaType.AUDIOBOOK) && audioTracks.isNotEmpty() ->
                        PrimaryButton(theme.ctaLabel, { play.playAudio(work, audioTracks, 0) }, icon = if (type == MediaType.AUDIOBOOK) Icons.Rounded.Headphones else Icons.Rounded.PlayArrow)
                    type == MediaType.BOOK && readable != null ->
                        PrimaryButton("Read", { play.read(work, readable) }, icon = Icons.Rounded.MenuBook)
                    type == MediaType.BOOK && audioTracks.isNotEmpty() ->
                        PrimaryButton("Listen", { play.playAudio(work, audioTracks, 0) }, icon = Icons.Rounded.Headphones)
                    hash == null && wants.isNotEmpty() -> PrimaryButton("Look for it", {
                        val w = wants.first()
                        scope.launch {
                            state.busy = "search"
                            session.io { session.api.searchReleases(w.id) }.onSuccess { r -> when (r) { is McpResult.Ok -> session.toast(Toast.Kind.INFO, "Search queued", "An indexer can take thirty seconds to answer; open Curate → Indexer candidates in a moment."); is McpResult.Refused -> session.refused(r) } }
                            state.busy = null
                        }
                    }, icon = Icons.Rounded.Search, enabled = state.busy == null)
                    hash == null -> PrimaryButton("Want", { onWant(work.id, work.title) }, icon = Icons.Rounded.Add, enabled = status == LibraryStatus.NOT_TRACKED)
                    // Reached only past the `hash == null` branches above.
                    type == MediaType.FEED || type == MediaType.PODCAST -> PrimaryButton(theme.ctaLabel, { play.playVideo(work, work.primaryAssetId ?: hash!!, hash!!, work.mime, work.title, null, emptyList(), art, emptyList()) }, icon = Icons.Rounded.OpenInNew, enabled = state.busy == null)
                    else -> PrimaryButton(theme.ctaLabel, { play.playVideo(work, work.primaryAssetId ?: hash!!, hash!!, work.mime, work.title, null, emptyList(), art, emptyList()) }, icon = Icons.Rounded.PlayArrow, enabled = state.busy == null)
                }
            },
            secondary = {
                val castId = if (type == MediaType.SERIES) first?.asset?.id else work.primaryAssetId
                if (castId != null && type != MediaType.BOOK && type != MediaType.FEED) SecondaryButton("Play on…", { toggleCast(session, state, castId, scope) }, icon = Icons.Rounded.Cast)
                if (status == LibraryStatus.NOT_TRACKED && (hash != null || type == MediaType.SERIES)) SecondaryButton("Want", { onWant(work.id, work.title) }, icon = Icons.Rounded.Add)
            },
        )
        if (hash == null && type != MediaType.SERIES && type != MediaType.FEED && type != MediaType.PODCAST && type != MediaType.MUSIC) Notice("Nothing to play yet — ${if (wants.isEmpty()) "not wanted, so nothing is looking for a copy." else "heyarr is looking. Curate → Indexer candidates shows what the indexers found."}")
        CastPicker(session, state)
    }
}

internal fun toggleCast(session: AppSession, state: DetailState, assetId: String, scope: CoroutineScope) {
    state.castAssetId = if (state.castAssetId == assetId) null else assetId
    if (state.renderers == null) scope.launch { session.io { session.api.renderers() }.onSuccess { state.renderers = it } }
}

@Composable
private fun CastPicker(session: AppSession, state: DetailState) {
    val scope = rememberCoroutineScope()
    val assetId = state.castAssetId ?: return
    fun playOn(renderer: Renderer) {
        state.castAssetId = null
        scope.launch {
            state.busy = "cast"
            session.io { session.api.playHere(assetId, renderer.name, renderer.udn) }.onSuccess { r -> when (r) { is McpResult.Ok -> session.toast(Toast.Kind.SUCCESS, "Playing on ${renderer.name}"); is McpResult.Refused -> session.refused(r) } }
            state.busy = null
        }
    }
    Panel("Play on a renderer", trailing = { GhostButton("Close", { state.castAssetId = null }) }) {
        val r = state.renderers
        when {
            r == null -> Skeleton(Modifier.fillMaxWidth().height(36.dp))
            r.isEmpty() -> Text("No renderers found. A device that is switched off will not be listed — that is not the same as it not existing.", style = MaterialTheme.typography.bodyMedium, color = Tokens.textMuted)
            else -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { for (x in r) FilterChip(x.name, false, { playOn(x) }, icon = Icons.Rounded.Cast) }
        }
        GhostButton("Search the network again", { scope.launch { session.io { session.api.renderers(refresh = true) }.onSuccess { state.renderers = it } } })
    }
}
