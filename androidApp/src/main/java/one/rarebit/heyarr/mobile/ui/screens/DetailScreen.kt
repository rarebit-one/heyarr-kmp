package one.rarebit.heyarr.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Verified
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.rarebit.heyarr.mobile.catalog.ContinueEntry
import one.rarebit.heyarr.core.heyarr.Candidate
import one.rarebit.heyarr.core.heyarr.DesiredItem
import one.rarebit.heyarr.mobile.heyarr.HeyarrApi
import one.rarebit.heyarr.mobile.heyarr.McpResult
import one.rarebit.heyarr.mobile.heyarr.seriesWantState
import one.rarebit.heyarr.mobile.library.Episode
import one.rarebit.heyarr.mobile.library.Season
import one.rarebit.heyarr.mobile.library.Series
import one.rarebit.heyarr.mobile.library.Variants
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.core.mcp.Explanation
import one.rarebit.heyarr.core.mcp.ExternalId
import one.rarebit.heyarr.core.mcp.ReleaseAttributes
import one.rarebit.heyarr.core.mcp.ReleaseToExplain
import one.rarebit.heyarr.core.mcp.Renderer
import one.rarebit.heyarr.core.mcp.Replica
import one.rarebit.heyarr.core.mcp.Satisfaction
import one.rarebit.heyarr.mobile.music.Tracks
import one.rarebit.heyarr.mobile.music.isPrimaryRole
import one.rarebit.heyarr.mobile.music.trackTitle
import one.rarebit.heyarr.mobile.nav.Route
import one.rarebit.heyarr.mobile.nav.detailRoute
import one.rarebit.heyarr.mobile.personalstate.ItemRef
import one.rarebit.heyarr.mobile.playback.QueueEntry
import one.rarebit.heyarr.mobile.reader.ReaderFormat
import one.rarebit.heyarr.mobile.search.FollowedItem
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.mobile.state.ExternalEpisode
import one.rarebit.heyarr.mobile.state.ExternalMeta
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.mobile.state.MetaKey
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.ui.theme.LocalMediaTheme
import one.rarebit.heyarr.ui.theme.MediaScope
import one.rarebit.heyarr.ui.theme.MediaThemes
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.mobile.ui.components.Artwork
import one.rarebit.heyarr.ui.components.Cell
import one.rarebit.heyarr.ui.components.DataTable
import one.rarebit.heyarr.ui.components.ErrorState
import one.rarebit.heyarr.ui.components.Field
import one.rarebit.heyarr.ui.components.FilterChip
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.mobile.ui.components.Hero
import one.rarebit.heyarr.mobile.ui.components.HeroSkeleton
import one.rarebit.heyarr.ui.components.IconButtonRound
import one.rarebit.heyarr.ui.components.MediaRowSkeleton
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.Panel
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.ReasonList
import one.rarebit.heyarr.ui.components.RejectedBy
import one.rarebit.heyarr.ui.components.RuleCode
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.components.Section
import one.rarebit.heyarr.ui.components.SectionHeader
import one.rarebit.heyarr.ui.components.Skeleton
import one.rarebit.heyarr.ui.components.StatusPill
import one.rarebit.heyarr.ui.components.TableColumn
import one.rarebit.heyarr.ui.components.focusRing
import one.rarebit.heyarr.mobile.ui.components.rememberCover
import one.rarebit.heyarr.ui.components.verdictColor

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
private fun AssetPersonalActions(personal: DetailPersonal, assetId: String, title: String) {
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
private fun queueOf(work: Work, seasons: List<Season>, session: AppSession): List<QueueEntry> =
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

private fun toggleCast(session: AppSession, state: DetailState, assetId: String, scope: CoroutineScope) {
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

/** The synopsis: the node's when it has one, else a public source's (labelled), else an honest line. */
@Composable
private fun SynopsisBlock(work: Work, state: DetailState, coverIsExternal: Boolean) {
    val own = work.synopsis
    val ext = state.external
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            own != null -> Text(own, style = MaterialTheme.typography.bodyLarge, color = Tokens.textPrimary)
            ext?.synopsis != null -> {
                Text(ext.synopsis, style = MaterialTheme.typography.bodyLarge, color = Tokens.textPrimary, maxLines = 6, overflow = TextOverflow.Ellipsis)
                Text("Synopsis${if (coverIsExternal) " and cover" else ""} via ${ext.source} — not from your library. The node has no metadata provider (TVDB, ADR-0058).", style = MaterialTheme.typography.labelSmall, color = Tokens.textDisabled)
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Info, contentDescription = null, tint = Tokens.textDisabled, modifier = Modifier.size(14.dp))
                Text("No synopsis — the node has no metadata provider and no public source knew this title. Titles, seasons and episodes below come from the files themselves.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
            }
        }
    }
}

/** Seasons as chips, then the selected season's episodes with the thumbnails the scan already recorded. */
@Composable
private fun SeasonsBlock(session: AppSession, work: Work, seasons: List<Season>, state: DetailState, wants: List<DesiredItem>, play: DetailPlayback, art: String?) {
    if (state.assets == null) { MediaRowSkeleton(5); return }
    val ext = state.externalEpisodes
    val extSeasons = ext.map { it.season }.distinct().filter { n -> seasons.none { it.number == n } }.sorted()
    val all: List<Season> = (seasons + extSeasons.map { Season(it, emptyList()) }).sortedWith(compareBy({ it.number == null }, { if (it.number == 0) Int.MAX_VALUE else it.number ?: 0 }))
    if (all.isEmpty()) { Notice("No episode files are held for this series yet.${if (wants.isNotEmpty()) " heyarr is looking — Curate → Indexer candidates shows what it found." else ""}"); return }
    val selected = all.firstOrNull { it.number == state.season } ?: seasons.firstOrNull() ?: all.first()
    val extForSeason = ext.filter { it.season == selected.number }.associateBy { it.number }
    val known = maxOf(selected.episodes.mapNotNull { it.number }.maxOrNull() ?: 0, extForSeason.keys.maxOrNull() ?: 0)
    val queue = queueOf(work, seasons, session)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader("Episodes", subtitle = "${selected.held} of $known held" + (if (ext.isNotEmpty()) "  ·  calendar via TVmaze" else ""), trailing = {
            SecondaryButton(if (state.wantMenu) "Close" else "Want more…", { state.wantMenu = !state.wantMenu }, icon = Icons.Rounded.Add, compact = true)
        })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in all) FilterChip(s.label, s == selected, { state.season = s.number }, count = maxOf(s.episodes.size, ext.count { it.season == s.number }).takeIf { it > 0 })
        }
        if (state.wantMenu) WantSeasonsPanel(session, work, all, wants, state)
        val rows: List<Any> = buildList {
            val byNumber = selected.episodes.associateBy { it.number }
            for (n in 1..known) add(byNumber[n] ?: n)
            addAll(selected.episodes.filter { it.number == null || it.number > known })
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in rows) when (row) {
                is Episode -> EpisodeRow(session, work, row, state, extForSeason[row.number], onPlay = { ep -> play.playVideo(work, ep.asset.id, ep.asset.blobHash!!, ep.asset.mime ?: work.mime, Series.playTitle(work, ep), null, queue, art, ep.subtitles) })
                is Int -> MissingEpisodeRow(session, selected, row, wants, extForSeason[row])
            }
        }
    }
}

/**
 * Wanting more of a series. One season is a genuine one-off (an edition-scope want —
 * heyarr's edition of an episodic work IS its season). Wanting the *whole* series is not
 * a one-off: per ADR-0089 a work-scoped want on a series establishes a standing follow
 * whose poll enumerates every episode, so we present it as following, and read the
 * "already following" signal the same way heyarr-desktop does — from the item-scoped
 * wants the follow projects ([seriesWantState]).
 */
@Composable
private fun WantSeasonsPanel(session: AppSession, work: Work, seasons: List<Season>, wants: List<DesiredItem>, state: DetailState) {
    val scope = rememberCoroutineScope()
    val profiles = session.profiles
    var profile by remember(profiles) { mutableStateOf(profiles.firstOrNull { it.name == session.defaultProfile }?.name ?: profiles.firstOrNull()?.name ?: session.defaultProfile) }
    val tvdb = state.externalIds.firstOrNull { it.source.equals("tvdb", true) }?.value ?: work.externalIds["tvdb"]
    val followState = seriesWantState(wants)
    Panel("Want more of ${work.title}") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Profile", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
            for (p in profiles) FilterChip(p.name, profile == p.name, { profile = p.name })
        }
        Text("Seasons the library knows", style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in seasons) {
                val editionId = s.episodes.firstOrNull()?.asset?.editionId
                val already = editionId != null && wants.any { it.scope == "edition" && it.editionId == editionId }
                SecondaryButton(
                    if (already) "${s.label} · wanted" else "Want ${s.label}${s.gaps.takeIf { it.isNotEmpty() }?.let { " (${it.size} missing)" } ?: ""}",
                    {
                        if (editionId == null) return@SecondaryButton
                        scope.launch {
                            session.io { session.api.wantEdition(work.id, editionId, profile) }.onSuccess { r ->
                                when (r) {
                                    is McpResult.Ok -> { session.toast(Toast.Kind.SUCCESS, "Wanted ${s.label}", "Measured against $profile; heyarr will look for what this season is missing."); session.refreshIndex() }
                                    is McpResult.Refused -> session.refused(r)
                                }
                            }
                        }
                    },
                    icon = Icons.Rounded.Add, compact = true, enabled = editionId != null && !already && profile.isNotBlank(),
                )
            }
        }
        Text("Seasons the library has never seen have nothing to point a want at yet — they arrive through a follow.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
        Text("The whole series", style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // ADR-0089: wanting the whole series IS following it — the node resolves the
            // series and its poll enumerates every episode as a want. One door.
            SecondaryButton(if (followState.everythingCovered) "Following · every episode" else "Follow the whole series", {
                scope.launch {
                    session.io { session.api.wantWork(work.id, profile) }.onSuccess { r ->
                        when (r) {
                            is McpResult.Ok -> { session.toast(Toast.Kind.SUCCESS, "Following ${work.title}", "Every episode, past and future, becomes a want. If this node can't identify the series yet, it stays a plain want until it can."); session.refreshIndex() }
                            is McpResult.Refused -> session.refused(r)
                        }
                    }
                }
            }, icon = Icons.Rounded.Add, compact = true, enabled = !followState.everythingCovered && profile.isNotBlank())
            // The explicit TVDB-id path is a fallback for a node whose metadata provider
            // can't resolve the series by title; only offered when we know the id and
            // aren't following yet.
            if (tvdb != null && !followState.everythingCovered) SecondaryButton("Follow by TVDB id", {
                scope.launch { session.io { session.api.follow(null, tvdb, null, profile, backfill = "full", reason = "followed from the phone") }.onSuccess { r -> when (r) { is McpResult.Ok -> { session.toast(Toast.Kind.SUCCESS, "Following ${work.title}", "Every episode, past and future, becomes a want."); session.refreshIndex() }; is McpResult.Refused -> session.refused(r) } } }
            }, icon = Icons.Rounded.Search, compact = true, enabled = profile.isNotBlank())
        }
        if (!followState.everythingCovered) Text(
            if (tvdb == null) "Following the whole series lets the node find it by name. If it can't, add the TVDB URL in Settings → Followed sources."
            else "Following the whole series resolves it by name; the TVDB-id button is the exact-match fallback.",
            style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted,
        )
    }
}

@Composable
private fun EpisodeRow(session: AppSession, work: Work, ep: Episode, state: DetailState, ext: ExternalEpisode?, onPlay: (Episode) -> Unit) {
    val scope = rememberCoroutineScope()
    val theme = LocalMediaTheme.current
    val thumb = ep.thumbnailPath?.let { HeyarrApi.blobUrlFromPath(session.baseUrl, it) } ?: ext?.imageUrl?.takeIf { session.externalMetadata }
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Tokens.radiusInput)
    val cont = state.continueEntry
    val isContinue = cont != null && (cont.assetId == ep.asset.id || (cont.blobHash != null && cont.blobHash == ep.asset.blobHash))
    Row(
        Modifier.fillMaxWidth().focusRing(interaction, shape).clip(shape)
            .background(Tokens.surface1, shape).border(Tokens.hairline, if (isContinue) theme.accent.copy(alpha = 0.6f) else Tokens.border, shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, enabled = ep.isPlayable, onClick = { onPlay(ep) })
            .semantics { contentDescription = "${ep.label}${if (!ep.isPlayable) ", file missing" else ""}" }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(Tokens.radiusCard))) {
            Artwork(thumb, MediaType.SERIES, Modifier.fillMaxSize(), glyphSize = 22.dp)
            state.continueEntry?.takeIf { isContinue }?.fraction?.let { f ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Tokens.bgBase.copy(alpha = 0.5f))) {
                    Box(Modifier.fillMaxWidth(f).height(4.dp).background(theme.accent))
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ep.code?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = theme.accentGradientEnd) }
                Text(ep.title ?: ext?.name ?: ep.asset.filename ?: ep.asset.id, style = MaterialTheme.typography.titleSmall, color = if (ep.isPlayable) Tokens.textPrimary else Tokens.textDisabled, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (tag in Series.qualityTags(ep.asset)) RuleCode(tag, tone = Tokens.textMuted)
                ep.asset.sizeBytes?.let { Text(WorkAsset.formatBytes(it), style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted) }
                if (ep.subtitles.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(Icons.Rounded.ClosedCaption, contentDescription = null, tint = Tokens.textMuted, modifier = Modifier.size(14.dp))
                    Text(ep.subtitles.size.toString(), style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
                }
                ext?.airdate?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Tokens.textDisabled) }
                if (isContinue) Text("continue · ${state.continueEntry?.progressLabel}", style = MaterialTheme.typography.labelSmall, color = theme.accentGradientEnd)
                if (!ep.isPlayable) Text("file missing since ${ep.asset.missingSince?.take(10)}", style = MaterialTheme.typography.labelSmall, color = Tokens.danger)
            }
        }
        if (ep.isPlayable) {
            IconButtonRound(Icons.Rounded.Cast, "Play ${ep.label} on a renderer", { toggleCast(session, state, ep.asset.id, scope) }, size = 36.dp)
            IconButtonRound(Icons.Rounded.PlayArrow, "Play ${ep.label}", { onPlay(ep) }, size = 40.dp, filled = true, enabled = state.busy == null)
        }
    }
}

/** A numbered gap in a season: nothing held, and the one honest action — ask the indexers. */
@Composable
private fun MissingEpisodeRow(session: AppSession, season: Season, number: Int, wants: List<DesiredItem>, ext: ExternalEpisode?) {
    val scope = rememberCoroutineScope()
    val code = "S%02dE%02d".format(season.number ?: 0, number)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Tokens.radiusInput)).border(Tokens.hairline, Tokens.border.copy(alpha = 0.6f), RoundedCornerShape(Tokens.radiusInput)).padding(8.dp)
            .semantics { contentDescription = "$code not held" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(Tokens.radiusCard)).background(Tokens.surface1), contentAlignment = Alignment.Center) {
            Text("not held", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(code, style = MaterialTheme.typography.labelMedium, color = Tokens.textDisabled)
                Text(ext?.name ?: "Not held", style = MaterialTheme.typography.titleSmall, color = Tokens.textDisabled, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(if (wants.isEmpty()) "Want this series and heyarr will look for it." else "Wanted — heyarr searches on its schedule; ask now to jump the queue.", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
        }
        if (wants.isNotEmpty()) IconButtonRound(Icons.Rounded.Search, "Look for $code now", {
            scope.launch { session.io { session.api.searchReleases(wants.first().id) }.onSuccess { r -> when (r) { is McpResult.Ok -> session.toast(Toast.Kind.INFO, "Search queued for ${season.label}", "Results land under Curate → Indexer candidates."); is McpResult.Refused -> session.refused(r) } } }
        }, size = 36.dp)
    }
}

@Composable
private fun TracksBlock(session: AppSession, work: Work, state: DetailState, play: DetailPlayback, personal: DetailPersonal) {
    val assets = state.assets
    if (assets == null) { MediaRowSkeleton(5); return }
    val tracks = Tracks.all(assets)
    val playable = Tracks.playable(assets)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader("Tracks", subtitle = "${playable.size} playable", trailing = {
            if (playable.isNotEmpty()) PrimaryButton("Play all", { play.playAudio(work, playable, 0) }, icon = Icons.Rounded.PlayArrow, compact = true)
        })
        if (tracks.isEmpty()) Notice("No audio files held for this work yet.")
        for ((i, t) in tracks.withIndex()) {
            val theme = LocalMediaTheme.current
            val idx = playable.indexOfFirst { it.id == t.id }
            Row(Modifier.fillMaxWidth().background(Tokens.surface1, RoundedCornerShape(Tokens.radiusInput)).border(Tokens.hairline, Tokens.border, RoundedCornerShape(Tokens.radiusInput)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("%02d".format(i + 1), style = MaterialTheme.typography.labelMedium, color = theme.accentGradientEnd, modifier = Modifier.width(28.dp))
                Text(trackTitle(t), style = MaterialTheme.typography.titleSmall, color = if (t.isPlayable) Tokens.textPrimary else Tokens.textDisabled, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                t.sizeBytes?.let { Text(WorkAsset.formatBytes(it), style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted) }
                AssetPersonalActions(personal, t.id, trackTitle(t))
                if (idx >= 0) IconButtonRound(Icons.Rounded.PlayArrow, "Play ${trackTitle(t)}", { play.playAudio(work, playable, idx) }, size = 36.dp, filled = true)
            }
        }
    }
}

@Composable
private fun ArchiveBlock(state: DetailState, onOpen: (Route) -> Unit) {
    val items = state.feedItems
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader("Archive", subtitle = items?.let { "${it.count { i -> i.archived }} of ${it.size} archived" })
        when {
            items == null -> MediaRowSkeleton(4)
            items.isEmpty() -> Notice("Nothing archived yet — the node polls this source on its schedule.")
            else -> for (item in items) Row(Modifier.fillMaxWidth().background(Tokens.surface1, RoundedCornerShape(Tokens.radiusInput)).border(Tokens.hairline, Tokens.border, RoundedCornerShape(Tokens.radiusInput)).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, color = if (item.archived) Tokens.textPrimary else Tokens.textDisabled)
                    Text(listOfNotNull(item.publishedAt?.take(10), if (item.archived) "archived" else "not archived yet", item.want?.summary?.takeIf { it.isNotBlank() }).joinToString("  ·  "), style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
                }
                val wid = item.workId
                if (item.archived && wid != null) SecondaryButton("Open", { onOpen(detailRoute(wid, MediaType.UNKNOWN, item.title, from = "Archive")) }, icon = Icons.Rounded.OpenInNew, compact = true)
            }
        }
    }
}

/** A film / single-file work: the one file, its quality, and what plays it. */
@Composable
private fun FileBlock(work: Work, state: DetailState) {
    val hash = work.blobHash ?: return
    val file = state.assets?.firstOrNull { it.blobHash == hash }
    Panel("This copy") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (tag in file?.let { Series.qualityTags(it) }.orEmpty()) RuleCode(tag, tone = Tokens.textMuted)
            work.mime?.let { RuleCode(it, tone = Tokens.textMuted) }
            file?.sizeBytes?.let { Text(WorkAsset.formatBytes(it), style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted) }
        }
        val subs = state.assets.orEmpty().filter { Series.isSubtitle(it) }
        Text(if (subs.isEmpty()) "No caption files held — captions inside the container still show in the player's CC menu." else "Caption files: " + subs.joinToString(", ") { it.filename?.substringBeforeLast('.')?.substringAfterLast('.') ?: "?" }, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
    }
}

/** A book: each readable file with its format — Read opens the reader, an audiobook file listens through the queue. */
@Composable
private fun BookFilesBlock(work: Work, state: DetailState, play: DetailPlayback, personal: DetailPersonal) {
    val assets = state.assets
    if (assets == null) { MediaRowSkeleton(3); return }
    val readable = assets.filter { it.isPlayable }.map { it to ReaderFormat.of(it.mime, it.filename) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader("Files", subtitle = "${readable.size} held")
        if (readable.isEmpty()) Notice("No readable file in the catalog yet.")
        for ((asset, format) in readable) {
            Row(Modifier.fillMaxWidth().background(Tokens.surface1, RoundedCornerShape(Tokens.radiusInput)).border(Tokens.hairline, Tokens.border, RoundedCornerShape(Tokens.radiusInput)).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(asset.filename ?: asset.id, style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(format?.label, asset.sizeBytes?.let { WorkAsset.formatBytes(it) }).joinToString("  ·  "), style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
                }
                AssetPersonalActions(personal, asset.id, asset.filename ?: asset.id)
                when (format) {
                    ReaderFormat.AUDIOBOOK -> PrimaryButton("Listen", { play.playAudio(work, listOf(asset), 0) }, icon = Icons.Rounded.Headphones, compact = true)
                    null -> SecondaryButton("Unsupported", {}, enabled = false, compact = true)
                    else -> PrimaryButton("Read", { play.read(work, asset) }, icon = Icons.Rounded.MenuBook, compact = true)
                }
            }
        }
    }
}

/** Curate → captions and artwork: what is held per episode, and the honest limits of what the node can fetch. */
@Composable
private fun SidecarsPanel(state: DetailState, seasons: List<Season>) {
    val assets = state.assets.orEmpty()
    val subs = assets.filter { Series.isSubtitle(it) }
    val art = assets.filter { it.role == "artwork" }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.ClosedCaption, contentDescription = null, tint = Tokens.textMuted, modifier = Modifier.size(16.dp))
            Text("${subs.size} caption file${if (subs.size == 1) "" else "s"}", style = MaterialTheme.typography.bodyMedium, color = Tokens.textPrimary)
            if (seasons.isNotEmpty()) Text("· ${seasons.sumOf { s -> s.episodes.count { it.subtitles.isEmpty() && it.isPlayable } }} held episodes without captions", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Image, contentDescription = null, tint = Tokens.textMuted, modifier = Modifier.size(16.dp))
            Text("${art.size} artwork file${if (art.size == 1) "" else "s"}", style = MaterialTheme.typography.bodyMedium, color = Tokens.textPrimary)
            if (seasons.isNotEmpty()) Text("· ${seasons.sumOf { s -> s.episodes.count { it.thumbnail == null } }} episodes without a thumbnail", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
        }
        Notice("heyarr's tool surface has no caption or artwork search yet: sidecars arrive with a release or a scan. Asking the indexers again (Indexer candidates) is the only fetch this node can queue.", tone = Tokens.slate)
    }
}

/** "Would this be accepted?" — describe a release, get every rule back. Absent fields stay absent so they read as undetermined. */
@Composable
private fun ExplainPanel(session: AppSession, wants: List<DesiredItem>) {
    val scope = rememberCoroutineScope()
    val profiles = session.profiles
    var profile by remember(profiles) { mutableStateOf(wants.firstOrNull()?.let { w -> profiles.firstOrNull { it.id == w.qualityProfileId }?.name } ?: profiles.firstOrNull()?.name ?: session.defaultProfile) }
    var title by remember { mutableStateOf("") }
    var resolution by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var codec by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<McpResult<Explanation?>?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Describe a release and heyarr explains, rule by rule, whether the profile would accept it. Leave a field blank when you do not know — a blank reads as undetermined, a guess reads as a claim.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (p in profiles) FilterChip(p.name, profile == p.name, { profile = p.name }) }
        Field("Title", title) { title = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Resolution (480/720/1080/2160)", resolution, Modifier.weight(1f), keyboard = KeyboardType.Number) { resolution = it.filter { c -> c.isDigit() } }
            Field("Source (remux/bluray/web-dl/…)", source, Modifier.weight(1f)) { source = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Video codec", codec, Modifier.weight(1f)) { codec = it }
            Field("Size (bytes)", size, Modifier.weight(1f), keyboard = KeyboardType.Number) { size = it.filter { c -> c.isDigit() } }
        }
        PrimaryButton("Explain", {
            busy = true
            scope.launch {
                val rel = ReleaseToExplain("candidate", title.ifBlank { "untitled release" }, ReleaseAttributes(resolution = resolution.toIntOrNull(), source = source, videoCodec = codec, sizeBytes = size.toLongOrNull()))
                session.io { session.api.explain(profile, listOf(rel)) }.onSuccess { result = it }
                busy = false
            }
        }, icon = Icons.Rounded.Verified, compact = true, enabled = !busy && profile.isNotBlank())
        when (val r = result) {
            null -> {}
            is McpResult.Refused -> Notice("explain_release: ${r.message}", tone = Tokens.danger)
            is McpResult.Ok -> r.value?.ranked?.firstOrNull()?.let { ranked ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (ranked.accepted) "Would be accepted" else "Would be rejected", style = MaterialTheme.typography.titleSmall, color = verdictColor(if (ranked.accepted) "pass" else "fail"))
                    Text("score ${ranked.score}${if (ranked.terminal) " · terminal" else ""}", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
                }
                RejectedBy(ranked.rejectedBy)
                ReasonList(ranked.reasons)
            } ?: Text("No verdict returned.", color = Tokens.textMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ── Curate ────────────────────────────────────────────────────────────────────────────

/**
 * The curation surface as tables, one column, each section collapsible: what each want
 * is measured against and where it stands; every held file with the verdict the profile
 * gave it (rules behind a tap); the indexer candidates with Acquire; a release scorer;
 * health; captions and artwork; the works the scanner minted for the same title; the
 * identifiers; the raw file list. Nothing here is paraphrased — rule codes, states and
 * the node's detail lines are shown as sent. Wide tables scroll sideways.
 */
@Composable
private fun CurateTab(session: AppSession, work: Work, type: MediaType, wants: List<DesiredItem>, state: DetailState, seasons: List<Season>, onOpen: (Route) -> Unit, reload: () -> Unit) {
    val scope = rememberCoroutineScope()
    val assets = state.assets.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        // 1. Status
        Section("Wants & status", subtitle = if (wants.isEmpty()) "Not wanted — nothing measures this work" else "${wants.size} want${if (wants.size == 1) "" else "s"} on this work", trailing = { GhostButton("Refresh", reload) }) {
            DataTable(
                columns = listOf(TableColumn("Scope", width = 70.dp), TableColumn("Profile", width = 110.dp), TableColumn("State", width = 110.dp), TableColumn("Content", width = 110.dp), TableColumn("Placement", width = 140.dp), TableColumn("Upgrade", width = 220.dp), TableColumn("Monitor", width = 120.dp, alignEnd = true)),
                rowCount = wants.size, emptyText = "Not wanted. Want it (or a season) to see every rule heyarr would apply.", minWidth = 900.dp,
            ) { r, c ->
                val w = wants[r]
                val sat = (state.satisfaction[w.id] as? McpResult.Ok)?.value
                when (c) {
                    0 -> Cell(if (w.id.startsWith("pending:")) "sending…" else w.scope, muted = true)
                    1 -> Cell(session.profiles.firstOrNull { it.id == w.qualityProfileId }?.name ?: w.qualityProfileId ?: "?", mono = true)
                    2 -> StatusPill(LibraryStatus.ofState(w.state))
                    3 -> Text(sat?.contentSatisfaction?.replace('_', ' ') ?: (w.content ?: "…"), style = MaterialTheme.typography.labelMedium, color = verdictColor(if ((sat?.contentSatisfaction ?: w.content) == "satisfied") "pass" else "fail"))
                    4 -> Cell(sat?.let { if (it.placementUnproven) "unproven (single node)" else it.placementSatisfaction } ?: (w.placement ?: "…"), muted = true)
                    5 -> Cell(sat?.let { (if (it.upgradeEligible) "eligible" else it.upgradeStatus.replace('_', ' ')) + (it.upgradeDetail.takeIf { d -> d.isNotBlank() }?.let { d -> " — $d" } ?: "") } ?: (w.detail ?: ""), muted = true, maxLines = 2)
                    6 -> FilterChip(if (w.monitor) "Monitoring" else "Off", w.monitor, {
                        scope.launch { session.io { session.api.monitor(w.id, !w.monitor) }.onSuccess { res -> if (res is McpResult.Refused) session.refused(res) else session.refreshIndex() } }
                    })
                }
            }
            for (w in wants) (state.satisfaction[w.id] as? McpResult.Refused)?.let { Notice("get_content_satisfaction: ${it.message}", tone = Tokens.danger) }
        }

        // 2. Held files with verdicts
        val verdicts = wants.flatMap { w -> (state.satisfaction[w.id] as? McpResult.Ok)?.value?.assets.orEmpty() }.associateBy { it.assetId }
        val held = assets.filter { it.isPrimaryRole && it.blobHash != null }
        Section("Held files", subtitle = "${held.size} playable file${if (held.size == 1) "" else "s"} · ${verdicts.size} judged against a profile") {
            DataTable(
                columns = listOf(TableColumn("File", width = 260.dp), TableColumn("Size", width = 80.dp, alignEnd = true), TableColumn("Verdict", width = 100.dp), TableColumn("Score", width = 60.dp, alignEnd = true), TableColumn("Rejected by", width = 220.dp)),
                rowCount = held.size, emptyText = "Nothing held for this work.", minWidth = 780.dp,
                detailLabel = { r -> held[r].filename ?: held[r].id },
                detail = { r -> verdicts[held[r].id]?.let { ReasonList(it.reasons) } ?: Text("No verdict — this file is not measured by any want.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted) },
            ) { r, c ->
                val t = held[r]; val v = verdicts[t.id]
                when (c) {
                    0 -> Cell(t.filename ?: t.id)
                    1 -> Cell(t.sizeBytes?.let { WorkAsset.formatBytes(it) } ?: "", muted = true)
                    2 -> Text(when { v == null -> "unmeasured"; v.accepted -> "accepted"; else -> "rejected" }, style = MaterialTheme.typography.labelMedium, color = verdictColor(when { v == null -> ""; v.accepted -> "pass"; else -> "fail" }))
                    3 -> Cell(v?.score?.toString() ?: "", muted = true, mono = true)
                    4 -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { for (x in v?.rejectedBy.orEmpty().take(2)) RuleCode(x.rule, tone = Tokens.danger); if ((v?.rejectedBy?.size ?: 0) > 2) Cell("+${v!!.rejectedBy.size - 2}", muted = true) }
                }
            }
        }

        // 3. Indexer candidates
        val cands = wants.flatMap { w -> state.candidates[w.id].orEmpty().map { w to it } }
        Section("Indexer candidates", subtitle = if (wants.isEmpty()) "Want it first — candidates belong to a want" else "${cands.size} from the last search", trailing = {
            if (wants.isNotEmpty()) SecondaryButton("Search now", {
                scope.launch { session.io { session.api.searchReleases(wants.first().id) }.onSuccess { r -> when (r) { is McpResult.Ok -> session.toast(Toast.Kind.INFO, "Search queued", "Indexers answer within a minute; the Downloads tab under Library shows the job."); is McpResult.Refused -> session.refused(r) } } }
            }, icon = Icons.Rounded.Search, compact = true)
        }) {
            DataTable(
                columns = listOf(TableColumn("Release", width = 260.dp), TableColumn("Provider", width = 100.dp), TableColumn("Size", width = 80.dp, alignEnd = true), TableColumn("Score", width = 60.dp, alignEnd = true), TableColumn("Verdict", width = 90.dp), TableColumn("", width = 120.dp, alignEnd = true)),
                rowCount = cands.size, emptyText = if (wants.isEmpty()) "No want, no candidates." else "The last search found nothing${wants.firstOrNull()?.detail?.let { " — $it" } ?: ""}.", minWidth = 780.dp,
                detailLabel = { r -> cands[r].second.title },
                detail = { r -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { RejectedBy(cands[r].second.rejectedBy); ReasonList(cands[r].second.reasons) } },
            ) { r, c ->
                val (w, cand) = cands[r]
                when (c) {
                    0 -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { Cell(cand.title); if (cand.selected) Text("selected", style = MaterialTheme.typography.labelSmall, color = LocalMediaTheme.current.accentGradientEnd) }
                    1 -> Cell(cand.provider ?: "", muted = true, mono = true)
                    2 -> Cell(cand.sizeBytes?.let { WorkAsset.formatBytes(it) } ?: "", muted = true)
                    3 -> Cell(cand.score.toString(), muted = true, mono = true)
                    4 -> Text(if (cand.accepted) "accepted" else "rejected", style = MaterialTheme.typography.labelMedium, color = verdictColor(if (cand.accepted) "pass" else "fail"))
                    5 -> PrimaryButton("Acquire", {
                        scope.launch {
                            state.busy = cand.candidateId
                            session.io { session.api.acquire(w.id, cand.candidateId) }.onSuccess { res -> when (res) { is McpResult.Ok -> { session.toast(Toast.Kind.SUCCESS, "Acquiring", cand.title); session.refreshIndex(); reload() }; is McpResult.Refused -> session.refused(res) } }
                            state.busy = null
                        }
                    }, icon = Icons.Rounded.Download, compact = true, enabled = state.busy == null)
                }
            }
        }

        // 4. Score a release
        Section("Score a release", subtitle = "Ask the profile about a release you are looking at", initiallyOpen = false) { ExplainPanel(session, wants) }

        // 5. Health
        val hash = work.blobHash
        Section("Health", subtitle = hash?.let { "primary blob ${it.take(20)}…" } ?: "no held bytes to check", trailing = {
            if (hash != null) SecondaryButton("Verify bytes", {
                scope.launch { session.io { session.api.verifyBlob(hash) }.onSuccess { r -> when (r) { is McpResult.Ok -> session.toast(Toast.Kind.INFO, "Verification queued", "Re-hashing runs as a job; see Library → Downloads."); is McpResult.Refused -> session.refused(r) } } }
            }, icon = Icons.Rounded.Verified, compact = true)
        }) {
            when (val r = state.replicas) {
                null -> if (hash != null) Skeleton(Modifier.fillMaxWidth().height(40.dp)) else Text("Nothing to check.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
                is McpResult.Refused -> Notice("get_replica_status: ${r.message}", tone = Tokens.danger)
                is McpResult.Ok -> DataTable(columns = listOf(TableColumn("Peer", 1f), TableColumn("Copy", width = 110.dp), TableColumn("Verified", width = 80.dp)), rowCount = r.value.size, emptyText = "No replica report — on a single-node fabric there is nowhere for bytes to converge to.") { i, c ->
                    val rep = r.value[i]
                    when (c) { 0 -> Cell(rep.peer); 1 -> Cell(rep.state, mono = true); 2 -> Text(if (rep.verified) "yes" else "no", style = MaterialTheme.typography.labelMedium, color = verdictColor(if (rep.verified) "pass" else "undetermined")) }
                }
            }
        }

        // 6. Captions & artwork
        Section("Captions & artwork", initiallyOpen = false) { SidecarsPanel(state, seasons) }

        // 7. Also catalogued as
        if (state.variants.isNotEmpty()) Section("Also catalogued as", subtitle = "Works the scanner minted for this title's download folders (heyarr-core#470) — hidden from listings, folded here") {
            DataTable(columns = listOf(TableColumn("Work", 2f), TableColumn("Season", width = 70.dp), TableColumn("", width = 80.dp, alignEnd = true)), rowCount = state.variants.size) { i, c ->
                val v = state.variants[i]
                when (c) { 0 -> Cell(v.title); 1 -> Cell(Variants.seasonOf(v)?.let { "S$it" } ?: "", mono = true, muted = true); 2 -> GhostButton("Open", { onOpen(detailRoute(v.id, MediaType.from(v.kind), v.title, from = work.title, curate = true)) }) }
            }
        }

        // 8. Identifiers
        Section("Identifiers", initiallyOpen = false) {
            val rows = listOf("work id" to work.id, "work key" to (work.workKey ?: "—"), "type" to type.label) + work.externalIds.map { it.key to it.value } + state.externalIds.map { it.source to it.value }
            DataTable(columns = listOf(TableColumn("Key", width = 110.dp), TableColumn("Value", 1f)), rowCount = rows.size) { i, c ->
                val (k, v) = rows[i]
                when (c) { 0 -> Cell(k, muted = true, mono = true); 1 -> Cell(v, mono = true) }
            }
        }

        // 9. Files
        Section("All files", subtitle = "${assets.size} scanned", initiallyOpen = false) {
            DataTable(columns = listOf(TableColumn("File", width = 260.dp), TableColumn("Role", width = 80.dp), TableColumn("Type", width = 120.dp), TableColumn("Size", width = 80.dp, alignEnd = true), TableColumn("", width = 80.dp)), rowCount = assets.size, emptyText = "No files scanned.", minWidth = 660.dp) { i, c ->
                val t = assets[i]
                when (c) {
                    0 -> Cell(t.filename ?: t.id, color = if (t.isPlayable || t.role == "artwork") Tokens.textPrimary else Tokens.textDisabled)
                    1 -> Cell(t.role ?: "primary", mono = true, muted = true)
                    2 -> Cell(t.mime ?: "", mono = true, muted = true)
                    3 -> Cell(t.sizeBytes?.let { WorkAsset.formatBytes(it) } ?: "", muted = true)
                    4 -> if (t.missingSince != null) Text("missing", style = MaterialTheme.typography.labelSmall, color = Tokens.danger) else Cell("")
                }
            }
        }
    }
}

