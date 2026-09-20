package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.material.icons.rounded.DeleteOutline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.auth.GuestGate
import one.rarebit.heyarr.core.auth.Surface
import one.rarebit.heyarr.core.feeds.FollowedItem
import one.rarebit.heyarr.core.heyarr.Candidate
import one.rarebit.heyarr.core.heyarr.ContinueEntry
import one.rarebit.heyarr.core.heyarr.DesiredItem
import one.rarebit.heyarr.desktop.heyarr.McpResult
import one.rarebit.heyarr.desktop.library.Episode
import one.rarebit.heyarr.desktop.library.PrimaryAsset
import one.rarebit.heyarr.desktop.library.Season
import one.rarebit.heyarr.desktop.library.Series
import one.rarebit.heyarr.desktop.library.Variants
import one.rarebit.heyarr.desktop.library.Work
import one.rarebit.heyarr.desktop.library.WorkDetail
import one.rarebit.heyarr.core.mcp.Explanation
import one.rarebit.heyarr.core.mcp.ExternalId
import one.rarebit.heyarr.core.mcp.ReleaseAttributes
import one.rarebit.heyarr.core.mcp.ReleaseToExplain
import one.rarebit.heyarr.core.mcp.Renderer
import one.rarebit.heyarr.core.mcp.Replica
import one.rarebit.heyarr.core.mcp.Satisfaction
import one.rarebit.heyarr.desktop.music.Track
import one.rarebit.heyarr.desktop.playback.PlayResult
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.state.ExternalEpisode
import one.rarebit.heyarr.desktop.state.ExternalMeta
import one.rarebit.heyarr.desktop.state.MetaKey
import one.rarebit.heyarr.desktop.ui.components.rememberCover
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.desktop.state.Toast
import one.rarebit.heyarr.desktop.state.rememberArtwork
import one.rarebit.heyarr.desktop.theme.LocalMediaTheme
import one.rarebit.heyarr.desktop.theme.MediaScope
import one.rarebit.heyarr.ui.theme.MediaThemes
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.ui.theme.Tokens
import one.rarebit.heyarr.desktop.ui.Route
import one.rarebit.heyarr.desktop.ui.components.Artwork
import one.rarebit.heyarr.desktop.ui.components.ErrorState
import one.rarebit.heyarr.desktop.ui.components.FilterChip
import one.rarebit.heyarr.desktop.ui.components.GhostButton
import one.rarebit.heyarr.desktop.ui.components.Hero
import one.rarebit.heyarr.desktop.ui.components.HeroSkeleton
import one.rarebit.heyarr.desktop.ui.components.IconButtonRound
import one.rarebit.heyarr.desktop.ui.components.KeyValue
import one.rarebit.heyarr.desktop.ui.components.MediaRowSkeleton
import one.rarebit.heyarr.desktop.ui.components.Notice
import one.rarebit.heyarr.desktop.ui.components.Panel
import one.rarebit.heyarr.desktop.ui.components.PrimaryButton
import one.rarebit.heyarr.desktop.ui.components.ReasonList
import one.rarebit.heyarr.desktop.ui.components.RejectedBy
import one.rarebit.heyarr.desktop.ui.components.RuleCode
import one.rarebit.heyarr.desktop.ui.components.SecondaryButton
import one.rarebit.heyarr.desktop.ui.components.SectionHeader
import one.rarebit.heyarr.desktop.ui.components.Skeleton
import one.rarebit.heyarr.desktop.ui.components.StatusPill
import one.rarebit.heyarr.desktop.ui.components.focusRing
import one.rarebit.heyarr.desktop.ui.components.verdictColor
import one.rarebit.heyarr.desktop.ui.components.Cell
import one.rarebit.heyarr.desktop.ui.components.Column as TableColumn
import one.rarebit.heyarr.desktop.ui.components.DataTable
import one.rarebit.heyarr.desktop.ui.components.Section

/** The two faces of a work: what you came to watch, and the tooling that keeps it that way. */
enum class DetailTab(val label: String) { WATCH("Watch"), CURATE("Curate") }

/** Everything the detail screen loads for one work, each piece independently. */
class DetailState(val workId: String) {
    var tab by mutableStateOf(DetailTab.WATCH)
    var detail by mutableStateOf<WorkDetail?>(null)
    var detailError by mutableStateOf<String?>(null)
    var loading by mutableStateOf(true)
    var assets by mutableStateOf<List<Track>?>(null)
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
    /** Set by the screen each composition: how to open the embedded player. */
    var openPlayer: (Route.Player) -> Unit = {}
    /** Set by the screen each composition: how to open the e-book reader. */
    var openReader: (Route.Reader) -> Unit = {}
    var openVariant: (Work) -> Unit = {}
    var wantMenu by mutableStateOf(false)
    /** What a public source said about this work (cover, synopsis, TVmaze id) — labelled as external wherever shown. */
    var external by mutableStateOf<ExternalMeta?>(null)
    var externalEpisodes by mutableStateOf<List<ExternalEpisode>>(emptyList())
    /** Works the scanner minted for the same title's download folders (heyarr-core#470), folded under this one. */
    var variants by mutableStateOf<List<Work>>(emptyList())
}

/**
 * The one adaptive detail template, built for consumption first. **Watch** is what
 * you came for: the art, a synopsis when the node has one (and an honest line when it
 * has not), and the thing itself — seasons and episodes with their thumbnails for a
 * series, tracks for an album, the file for a film, the archive for a feed. **Curate**
 * keeps every technical surface — why this release, indexer candidates, scoring, health,
 * captions and artwork inventory, files — one tab away, never on the way.
 */
@Composable
fun DetailScreen(session: AppSession, route: Route.Detail, state: DetailState, onBack: () -> Unit, onOpen: (Route) -> Unit, onWant: (String, String, MediaType) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var confirmRemoval by remember(route.workId) { mutableStateOf(false) }
    var removing by remember(route.workId) { mutableStateOf(false) }
    var removalError by remember(route.workId) { mutableStateOf<String?>(null) }
    if (confirmRemoval) androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!removing) confirmRemoval = false },
        title = { Text("Remove “${route.titleHint ?: "this title"}”?") },
        text = { Column {
            Text("Stops following this title and removes its catalogue entry and download requests. Files are not deleted immediately; unused managed data may be reclaimed later.")
            removalError?.let { Text(it, color = Tokens.danger) }
        } },
        confirmButton = { one.rarebit.heyarr.desktop.ui.components.PrimaryButton(if (removing) "Removing…" else "Remove", enabled = !removing, onClick = {
            val api = session.api ?: return@PrimaryButton
            removing = true; removalError = null
            scope.launch {
                session.io { api.removeWork(route.workId) }.fold(
                    onSuccess = { session.catalogChanged(); confirmRemoval = false; onBack() },
                    onFailure = { removalError = it.message ?: "Removal failed" },
                )
                removing = false
            }
        }) },
        dismissButton = { GhostButton("Cancel", { confirmRemoval = false }, enabled = !removing) },
    )
    val wants: List<DesiredItem> = session.index.wantsFor(route.workId)
    val detail = state.detail
    val type = detail?.work?.kind?.let { MediaType.from(it) } ?: route.typeHint

    fun load() {
        val a = session.api ?: return
        state.loading = true; state.detailError = null
        scope.launch {
            session.io { a.work(route.workId) }.fold(
                onSuccess = { d -> state.detail = d; state.detailError = if (d == null) "This work no longer exists." else null },
                onFailure = { state.detailError = it.message },
            )
            state.loading = false
            val d = state.detail ?: return@launch
            if (session.config.externalMetadata) {
                val t = MediaType.from(d.work.kind)
                val feedRef = if (t == MediaType.FEED || t == MediaType.PODCAST) session.io { a.followed() }.getOrNull()?.firstOrNull { it.workId == d.work.id }?.feedRef else null
                val meta = session.external.lookup(MetaKey(t, d.work.title, d.work.year, d.work.artist ?: d.work.author, feedRef))
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
        val a = session.api ?: return
        for (w in wants) {
            if (w.id.startsWith("pending:")) continue
            scope.launch { session.io { a.satisfaction(w.id) }.onSuccess { r -> state.satisfaction = state.satisfaction + (w.id to r) } }
            scope.launch { session.io { a.candidates(w.id) }.onSuccess { c -> state.candidates = state.candidates + (w.id to (c?.candidates ?: emptyList())) } }
        }
    }
    state.openPlayer = { r -> onOpen(r) }
    state.openReader = { r -> onOpen(r) }
    state.openVariant = { v -> onOpen(Route.Detail(v.id, MediaType.from(v.kind), v.title, from = route.titleHint ?: "Back", curate = true)) }
    LaunchedEffect(route.workId) { if (route.curate) state.tab = DetailTab.CURATE; load() }
    LaunchedEffect(wants.map { it.id }) { loadWants() }
    LaunchedEffect(detail?.primaryAsset?.blobHash) {
        val hash = detail?.primaryAsset?.blobHash ?: return@LaunchedEffect
        val a = session.api ?: return@LaunchedEffect
        session.io { a.replicas(hash) }.onSuccess { state.replicas = it }
    }
    LaunchedEffect(type, route.workId) {
        if (type != MediaType.FEED && type != MediaType.PODCAST) return@LaunchedEffect
        val a = session.api ?: return@LaunchedEffect
        val source = session.io { a.followed() }.getOrNull()?.firstOrNull { it.workId == route.workId } ?: return@LaunchedEffect
        session.io { a.followedItems(source.id) }.onSuccess { state.feedItems = it }
    }

    val seasons = remember(state.assets) { if (Series.isSeries(type.apiName ?: type.name)) Series.seasons(state.assets.orEmpty()) else emptyList() }

    MediaScope(type) {
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 32.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GhostButton(route.from, onBack, icon = Icons.Rounded.ArrowBack)
                    Spacer(Modifier.weight(1f))
                    if (!session.isGuest && detail != null) GhostButton("Remove", { confirmRemoval = true }, icon = androidx.compose.material.icons.Icons.Rounded.DeleteOutline)
                    TabSwitch(state.tab, onSelect = { state.tab = it })
                }
            }
            item {
                when {
                    state.loading && detail == null -> HeroSkeleton(340.dp)
                    detail == null -> ErrorState("Couldn't load this work", state.detailError, onRetry = ::load)
                    else -> DetailHero(session, detail, type, wants, state, seasons, onWant)
                }
            }
            if (detail != null) {
                item { SynopsisBlock(detail, type, seasons, state) }
                if (state.tab == DetailTab.WATCH) {
                    when (type) {
                        MediaType.SERIES -> item { SeasonsBlock(session, detail, seasons, state, wants) }
                        MediaType.MUSIC, MediaType.AUDIOBOOK -> item { TracksBlock(session, detail, state) }
                        MediaType.FEED, MediaType.PODCAST -> item { ArchiveBlock(session, state) }
                        else -> item { FileBlock(session, detail, state) }
                    }
                } else {
                    item { CurateTab(session, detail, type, wants, state, seasons, ::loadWants) }
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
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(if (t == DetailTab.WATCH) Icons.Rounded.PlayArrow else Icons.Rounded.Build, contentDescription = null, tint = if (active) theme.accentGradientEnd else Tokens.textMuted, modifier = Modifier.size(14.dp))
                Text(t.label.uppercase(), style = MaterialTheme.typography.labelLarge, color = if (active) Tokens.textPrimary else Tokens.textMuted)
            }
        }
    }
}

/** Open the embedded player on one asset of this work. */
private fun playLocal(session: AppSession, state: DetailState, blobHash: String, label: String, scope: kotlinx.coroutines.CoroutineScope, assetId: String? = null, type: MediaType = MediaType.MOVIE, workTitle: String? = null) {
    val id = assetId ?: state.assets?.firstOrNull { it.blobHash == blobHash }?.id ?: state.detail?.primaryAsset?.assetId ?: blobHash
    val title = workTitle ?: state.detail?.work?.title ?: label
    val sub = label.takeIf { it != title }?.removePrefix("$title — ")
    state.openPlayer(Route.Player(state.workId, id, blobHash, title, sub, typeHint = type, from = "Back"))
}

@Composable
private fun DetailHero(session: AppSession, detail: WorkDetail, type: MediaType, wants: List<DesiredItem>, state: DetailState, seasons: List<Season>, onWant: (String, String, MediaType) -> Unit) {
    val scope = rememberCoroutineScope()
    val cover by rememberCover(session, type, detail.work.title, detail.artworkPath, detail.work.year, detail.work.artist ?: detail.work.author)
    val art = cover.bitmap
    val theme = MediaThemes.of(type)
    val status = session.index.statusOf(detail.work.id)
    // Wanting is an enrolled-only surface; a guest never sees the Want CTA (GuestGate is the
    // single source of truth) — they can still browse and play whatever holds a file.
    val canWant = GuestGate.allows(session.mode, Surface.WANT)
    val asset = detail.primaryAsset
    val work = detail.work
    val cont = state.continueEntry
    val first = Series.firstPlayable(seasons)
    val held = seasons.sumOf { it.held }
    val meta = when (type) {
        MediaType.SERIES -> listOf(work.year?.toString(), if (seasons.isNotEmpty()) "${seasons.count { it.number != null && it.number != 0 }} seasons" else null, if (state.assets != null) "$held episodes held" else null)
        MediaType.MOVIE -> listOf(work.year?.toString(), asset?.let { Series.qualityTags(Track("x", "x", filename = state.assets?.firstOrNull { t -> t.blobHash == it.blobHash }?.filename)).joinToString(" · ").ifBlank { null } }, asset?.sizeBytes?.let { PrimaryAsset.formatBytes(it) })
        MediaType.BOOK -> listOf(work.author, work.year?.toString(), asset?.mime?.substringAfter('/')?.uppercase())
        MediaType.MUSIC -> listOf(work.artist, work.year?.toString(), "${state.assets.orEmpty().count { it.isAudio && it.isPlayable }} tracks")
        else -> listOf(work.year?.toString(), work.kind)
    }

    fun openLocal() {
        val a = asset ?: return
        scope.launch {
            state.busy = "open"
            val msg = session.io { session.openExternally.open(session.config.baseUrl, a.blobHash, session.config.bearerToken.trim(), null, a.mime, work.title) }.getOrNull()
            state.busy = null
            if (msg != null) session.toast(Toast.Kind.INFO, msg)
        }
    }
    fun openBook() {
        val a = asset ?: return
        val fn = state.assets?.firstOrNull { it.blobHash == a.blobHash }?.filename
        state.openReader(Route.Reader(state.workId, a.assetId ?: a.blobHash, a.blobHash, work.title, a.mime, fn, from = "Back"))
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Hero(
            title = work.title, type = type, meta = meta, artwork = art, status = status, height = 340.dp,
            kicker = cont?.let { "Continue · ${it.editionLabel ?: ""} ${it.progressLabel ?: ""}".trim() },
            primary = {
                when {
                    cont?.blobHash != null && type != MediaType.BOOK -> PrimaryButton("Continue", {
                        val hash = cont.blobHash ?: return@PrimaryButton
                        playLocal(session, state, hash, "${work.title} — ${cont.editionLabel ?: ""}", scope, assetId = cont.assetId, type = type)
                    }, icon = Icons.Rounded.PlayArrow, enabled = state.busy == null)
                    type == MediaType.SERIES && first != null -> PrimaryButton("Play ${first.code ?: ""}".trim(), { playLocal(session, state, first.asset.blobHash!!, Series.playTitle(work, first), scope, assetId = first.asset.id, type = type) }, icon = Icons.Rounded.PlayArrow, enabled = state.busy == null)
                    asset == null && wants.isNotEmpty() -> PrimaryButton("Look for it", {
                        val a = session.api ?: return@PrimaryButton
                        val w = wants.first()
                        scope.launch {
                            state.busy = "search"
                            session.io { a.searchReleases(w.id) }.onSuccess { r -> when (r) { is McpResult.Ok -> session.toast(Toast.Kind.INFO, "Search queued", "An indexer can take thirty seconds to answer; open Curate → Releases in a moment."); is McpResult.Refused -> session.refused(r) } }
                            state.busy = null
                        }
                    }, icon = Icons.Rounded.Search, enabled = state.busy == null)
                    asset == null && canWant -> PrimaryButton("Want", { onWant(work.id, work.title, type) }, icon = Icons.Rounded.Add, enabled = status == LibraryStatus.NOT_TRACKED)
                    // Guest, nothing to play and no want affordance to offer: no primary CTA.
                    asset == null -> Unit
                    type == MediaType.BOOK -> PrimaryButton(theme.ctaLabel, ::openBook, icon = Icons.Rounded.MenuBook, enabled = state.busy == null)
                    type == MediaType.FEED -> PrimaryButton(theme.ctaLabel, ::openLocal, icon = Icons.Rounded.OpenInNew, enabled = state.busy == null)
                    else -> PrimaryButton(theme.ctaLabel, { playLocal(session, state, asset.blobHash, work.title, scope, assetId = asset.assetId, type = type) }, icon = Icons.Rounded.PlayArrow, enabled = state.busy == null)
                }
            },
            secondary = {
                val castId = if (type == MediaType.SERIES) (first?.asset?.id) else asset?.assetId
                if (castId != null && type != MediaType.BOOK && type != MediaType.FEED) SecondaryButton("Play on…", { toggleCast(session, state, castId, scope) }, icon = Icons.Rounded.Cast)
                if (status == LibraryStatus.NOT_TRACKED && canWant && (asset != null || type == MediaType.SERIES)) SecondaryButton("Want", { onWant(work.id, work.title, type) }, icon = Icons.Rounded.Add)
            },
        )
        if (asset == null && type != MediaType.SERIES && type != MediaType.FEED && type != MediaType.PODCAST) Notice("Nothing to play yet — ${if (wants.isEmpty()) "not wanted, so nothing is looking for a copy." else "heyarr is looking. Curate → Releases shows what the indexers found."}")
        CastPicker(session, state)
    }
}

private fun toggleCast(session: AppSession, state: DetailState, assetId: String, scope: kotlinx.coroutines.CoroutineScope) {
    state.castAssetId = if (state.castAssetId == assetId) null else assetId
    if (state.renderers == null) scope.launch { session.api?.let { a -> session.io { a.renderers() }.onSuccess { state.renderers = it } } }
}

@Composable
private fun CastPicker(session: AppSession, state: DetailState) {
    val scope = rememberCoroutineScope()
    val assetId = state.castAssetId ?: return
    fun playOn(renderer: Renderer) {
        val api = session.api ?: return
        state.castAssetId = null
        // Session scope, not this picker's: clearing castAssetId (above) removes the picker
        // from composition, and a picker-scoped coroutine would be cancelled with it before the
        // cast — and its toast — completed. A codec refusal offers "Cast anyway" (force_direct);
        // forcing again is a plain refusal, so no loop.
        fun cast(force: Boolean) {
            session.launch {
                state.busy = "cast"
                session.io { api.playHere(assetId, renderer.name, renderer.udn, forceDirect = force) }
                    .onSuccess { r -> when (r) {
                        is McpResult.Ok -> session.toast(Toast.Kind.SUCCESS, "Playing on ${renderer.name}")
                        is McpResult.Refused -> if (force) session.refused(r) else session.castRefused(r, renderer.name) { cast(true) }
                    } }
                state.busy = null
            }
        }
        cast(false)
    }
    Panel("Play on a renderer", trailing = { GhostButton("Close", { state.castAssetId = null }) }) {
        val r = state.renderers
        when {
            r == null -> Skeleton(Modifier.fillMaxWidth().height(36.dp))
            r.isEmpty() -> Text("No renderers found. A device that is switched off will not be listed — that is not the same as it not existing.", style = MaterialTheme.typography.bodyMedium, color = Tokens.textMuted)
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { for (x in r) FilterChip(x.name, false, { playOn(x) }, icon = Icons.Rounded.Cast) }
        }
        GhostButton("Search the network again", { scope.launch { session.api?.let { a -> session.io { a.renderers(refresh = true) }.onSuccess { state.renderers = it } } } })
    }
}

/** The synopsis: the node's when it has one, else a public source's (labelled), else an honest line. */
@Composable
private fun SynopsisBlock(detail: WorkDetail, type: MediaType, seasons: List<Season>, state: DetailState) {
    val own = listOf("overview", "synopsis", "description", "summary").firstNotNullOfOrNull { k -> detail.attributes[k]?.takeIf { it.isNotBlank() } }
    val ext = state.external
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            own != null -> Text(own, style = MaterialTheme.typography.bodyLarge, color = Tokens.textPrimary)
            ext?.synopsis != null -> {
                Text(ext.synopsis, style = MaterialTheme.typography.bodyLarge, color = Tokens.textPrimary, maxLines = 6, overflow = TextOverflow.Ellipsis)
                Text("Synopsis${if (ext.imageUrl != null && detail.artworkPath == null) " and cover" else ""} via ${ext.source} — not from your library. The node has no metadata provider (TVDB, ADR-0058).", style = MaterialTheme.typography.labelSmall, color = Tokens.textDisabled)
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
private fun SeasonsBlock(session: AppSession, detail: WorkDetail, seasons: List<Season>, state: DetailState, wants: List<DesiredItem>) {
    val scope = rememberCoroutineScope()
    if (state.assets == null) { MediaRowSkeleton(5); return }
    // The calendar: the library's seasons, plus any TVmaze knows that the library has never seen.
    val ext = state.externalEpisodes
    val extSeasons = ext.map { it.season }.distinct().filter { n -> seasons.none { it.number == n } }.sorted()
    val all: List<Season> = (seasons + extSeasons.map { Season(it, emptyList()) }).sortedWith(compareBy({ it.number == null }, { if (it.number == 0) Int.MAX_VALUE else it.number ?: 0 }))
    if (all.isEmpty()) { Notice("No episode files are held for this series yet.${if (wants.isNotEmpty()) " heyarr is looking — Curate → Releases shows what it found." else ""}"); return }
    val selected = all.firstOrNull { it.number == state.season } ?: seasons.firstOrNull() ?: all.first()
    val extForSeason = ext.filter { it.season == selected.number }.associateBy { it.number }
    val known = maxOf(selected.episodes.mapNotNull { it.number }.maxOrNull() ?: 0, extForSeason.keys.maxOrNull() ?: 0)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader("Episodes", subtitle = "${selected.held} of $known held" + (if (ext.isNotEmpty()) "  ·  calendar via TVmaze" else ""), trailing = {
            SecondaryButton(if (state.wantMenu) "Close" else "Want more…", { state.wantMenu = !state.wantMenu }, icon = Icons.Rounded.Add, compact = true)
        })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in all) FilterChip(s.label, s == selected, { state.season = s.number }, count = maxOf(s.episodes.size, ext.count { it.season == s.number }).takeIf { it > 0 })
        }
        if (state.wantMenu) WantSeasonsPanel(session, detail, all, wants)
        val rows: List<Any> = buildList {
            val byNumber = selected.episodes.associateBy { it.number }
            for (n in 1..known) add(byNumber[n] ?: n)
            addAll(selected.episodes.filter { it.number == null || it.number > known })
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in rows) when (row) {
                is Episode -> EpisodeRow(session, detail, row, state, extForSeason[row.number])
                is Int -> MissingEpisodeRow(session, selected, row, wants, state, extForSeason[row])
            }
        }
    }
}

/**
 * Wanting more of a series, at the two scopes that are genuinely distinct (ADR-0089):
 * one held season (an edition-scope want — heyarr's edition of an episodic work IS its
 * season), or the whole series. The whole-series want is the unified door: it resolves
 * the series' metadata id and establishes a full-backfill follow, so wanting the series
 * IS following it — every aired episode and each new one becomes an item-scoped want.
 * That is how seasons the library has never seen arrive; there is no separate follow step.
 */
@Composable
private fun WantSeasonsPanel(session: AppSession, detail: WorkDetail, seasons: List<Season>, wants: List<DesiredItem>) {
    val scope = rememberCoroutineScope()
    val profiles = session.profiles
    var profile by remember(profiles) { mutableStateOf(profiles.firstOrNull { it.name == "living-room" }?.name ?: profiles.firstOrNull()?.name ?: "") }
    // Wanting a series IS following it (ADR-0089): a work-scoped want resolves the
    // series' metadata id and establishes a full-backfill follow, whose poll then
    // enumerates every episode as an item-scoped want. So "already following" is
    // read from those item wants, NOT from a work-scoped want row — that row no
    // longer lingers beside the follow (ADR-0089 §4/consequences).
    val following = wants.any { it.scope == "item" }
    Panel("Want more of ${detail.work.title}") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Profile", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
            for (p in profiles) FilterChip(p.name, profile == p.name, { profile = p.name })
        }
        Text("Seasons the library knows", style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in seasons) {
                val editionId = s.episodes.firstOrNull()?.asset?.editionId
                val already = editionId != null && wants.any { it.scope == "edition" && it.editionId == editionId }
                SecondaryButton(
                    if (already) "${s.label} · wanted" else "Want ${s.label}${s.gaps.takeIf { it.isNotEmpty() }?.let { " (${it.size} missing)" } ?: ""}",
                    {
                        val a = session.api ?: return@SecondaryButton
                        if (editionId == null) return@SecondaryButton
                        scope.launch {
                            session.io { a.wantEdition(detail.work.id, editionId, profile) }.onSuccess { r ->
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
        Text("Seasons the library hasn't seen yet arrive when you want the whole series below — wanting a series follows it.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
        Text("The whole series", style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // One door for a series (ADR-0089): want_content resolves the metadata id and
            // establishes a full-backfill follow, so this single button both wants and
            // follows. No separate "Follow on TVDB" step, and no TVDB id needed up front.
            SecondaryButton(if (following) "Following · every episode wanted" else "Want the whole series", {
                val a = session.api ?: return@SecondaryButton
                scope.launch {
                    session.io { a.wantWork(detail.work.id, profile) }.onSuccess { r ->
                        when (r) {
                            is McpResult.Ok -> {
                                session.toast(Toast.Kind.SUCCESS, "Wanting ${detail.work.title}", "Wanting a series follows it — every episode, past and future, becomes a want.")
                                session.refreshIndex()
                            }
                            is McpResult.Refused -> session.refused(r)
                        }
                    }
                }
            }, icon = Icons.Rounded.Add, compact = true, enabled = !following && profile.isNotBlank())
        }
        Text("Wanting the whole series follows it: heyarr resolves its metadata id automatically (no TVDB URL needed) and backfills every aired episode plus each new one. To follow a source that has no work here, use Settings → Followed sources.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
    }
}

@Composable
private fun EpisodeRow(session: AppSession, detail: WorkDetail, ep: Episode, state: DetailState, ext: ExternalEpisode? = null) {
    val scope = rememberCoroutineScope()
    val theme = LocalMediaTheme.current
    val thumb by rememberCover(session, MediaType.SERIES, "", ep.thumbnailPath).let { c -> androidx.compose.runtime.derivedStateOf { c.value.bitmap } }
    val extThumb by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, ext?.imageUrl, ep.thumbnailPath) {
        if (ep.thumbnailPath == null && ext?.imageUrl != null && session.config.externalMetadata) value = session.artwork.load(ext.imageUrl)
    }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(Tokens.radiusInput)
    val cont = state.continueEntry
    val isContinue = cont != null && (if (cont.assetId != null) cont.assetId == ep.asset.id else cont.blobHash != null && cont.blobHash == ep.asset.blobHash)
    Row(
        Modifier.fillMaxWidth().focusRing(interaction, shape).clip(shape)
            .background(if (hovered) Tokens.surface2 else Tokens.surface1, shape).border(Tokens.hairline, if (isContinue) theme.accent.copy(alpha = 0.6f) else Tokens.border, shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, enabled = ep.isPlayable, onClick = { ep.asset.blobHash?.let { playLocal(session, state, it, Series.playTitle(detail.work, ep), scope, assetId = ep.asset.id, type = MediaType.SERIES) } })
            .semantics { contentDescription = "${ep.label}${if (!ep.isPlayable) ", file missing" else ""}" }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.width(152.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(Tokens.radiusCard))) {
            Artwork(thumb ?: extThumb, MediaType.SERIES, Modifier.fillMaxSize(), glyphSize = 22.dp)
            if (hovered && ep.isPlayable) Box(Modifier.fillMaxSize().background(Tokens.bgBase.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(40.dp).background(theme.ctaGradientStart, RectangleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = theme.onAccent, modifier = Modifier.size(22.dp))
                }
            }
            state.continueEntry?.takeIf { isContinue }?.fraction?.let { f ->
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Tokens.bgBase.copy(alpha = 0.5f))) {
                    Box(Modifier.fillMaxWidth(f).height(4.dp).background(theme.accent))
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ep.code?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = theme.accentGradientEnd) }
                Text(ep.title ?: ext?.name ?: ep.asset.filename ?: ep.asset.id, style = MaterialTheme.typography.titleMedium, color = if (ep.isPlayable) Tokens.textPrimary else Tokens.textDisabled, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ext?.airdate?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Tokens.textDisabled) }
                if (isContinue) Text("continue · ${state.continueEntry?.progressLabel}", style = MaterialTheme.typography.labelSmall, color = theme.accentGradientEnd)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (tag in Series.qualityTags(ep.asset)) RuleCode(tag, tone = Tokens.textMuted)
                ep.asset.sizeBytes?.let { Text(PrimaryAsset.formatBytes(it), style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted) }
                if (ep.subtitles.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(Icons.Rounded.ClosedCaption, contentDescription = null, tint = Tokens.textMuted, modifier = Modifier.size(14.dp))
                    Text(ep.subtitles.size.toString(), style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
                }
                if (!ep.isPlayable) Text("file missing since ${ep.asset.missingSince?.take(10)}", style = MaterialTheme.typography.labelSmall, color = Tokens.danger)
            }
            ext?.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (ep.isPlayable) {
            IconButtonRound(Icons.Rounded.Cast, "Play ${ep.label} on a renderer", { toggleCast(session, state, ep.asset.id, scope) }, size = 34.dp)
            IconButtonRound(Icons.Rounded.PlayArrow, "Play ${ep.label}", { ep.asset.blobHash?.let { playLocal(session, state, it, Series.playTitle(detail.work, ep), scope, assetId = ep.asset.id, type = MediaType.SERIES) } }, size = 34.dp, filled = true, enabled = state.busy == null)
        }
    }
}

/** A numbered gap in a season: nothing held, and the one honest action — ask the indexers. */
@Composable
private fun MissingEpisodeRow(session: AppSession, season: Season, number: Int, wants: List<DesiredItem>, state: DetailState, ext: ExternalEpisode? = null) {
    val scope = rememberCoroutineScope()
    val code = "S%02dE%02d".format(season.number ?: 0, number)
    val extThumb by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, ext?.imageUrl) {
        if (ext?.imageUrl != null && session.config.externalMetadata) value = session.artwork.load(ext.imageUrl)
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Tokens.radiusInput)).border(Tokens.hairline, Tokens.border.copy(alpha = 0.6f), RoundedCornerShape(Tokens.radiusInput)).padding(8.dp)
            .semantics { contentDescription = "$code not held" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.width(152.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(Tokens.radiusCard)).background(Tokens.surface1), contentAlignment = Alignment.Center) {
            if (extThumb != null) androidx.compose.foundation.Image(extThumb!!, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(0.45f))
            Text("not held", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(code, style = MaterialTheme.typography.labelMedium, color = Tokens.textDisabled)
                Text(ext?.name ?: "Not held", style = MaterialTheme.typography.titleMedium, color = Tokens.textDisabled)
                ext?.airdate?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Tokens.textDisabled) }
            }
            Text(if (wants.isEmpty()) "Want this series and heyarr will look for it." else "Wanted — heyarr searches on its schedule; ask now to jump the queue.", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
        }
        if (wants.isNotEmpty()) SecondaryButton("Look for it", {
            val a = session.api ?: return@SecondaryButton
            scope.launch { session.io { a.searchReleases(wants.first().id) }.onSuccess { r -> when (r) { is McpResult.Ok -> session.toast(Toast.Kind.INFO, "Search queued for ${season.label}", "Results land under Curate → Releases."); is McpResult.Refused -> session.refused(r) } } }
        }, icon = Icons.Rounded.Search, compact = true)
    }
}

@Composable
private fun TracksBlock(session: AppSession, detail: WorkDetail, state: DetailState) {
    val assets = state.assets
    if (assets == null) { MediaRowSkeleton(5); return }
    val tracks = assets.filter { it.isAudio && it.isPrimaryRole }.sortedWith(compareBy({ it.filename?.lowercase() ?: "" }, { it.id }))
    val playable = tracks.filter { it.isPlayable }.mapNotNull { track ->
        track.blobHash?.let { Route.Player(detail.work.id, track.id, it, detail.work.title, track.title, MediaType.from(detail.work.kind), "Listen") }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader("Tracks", subtitle = "${playable.size} playable", trailing = {
            if (playable.isNotEmpty()) PrimaryButton("Play all", { session.playback.queueAudio(playable) }, icon = Icons.Rounded.PlayArrow, compact = true)
        })
        if (tracks.isEmpty()) Notice("No audio files held for this work yet.")
        for ((i, t) in tracks.withIndex()) {
            val theme = LocalMediaTheme.current
            Row(Modifier.fillMaxWidth().background(Tokens.surface1, RoundedCornerShape(Tokens.radiusInput)).border(Tokens.hairline, Tokens.border, RoundedCornerShape(Tokens.radiusInput)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("%02d".format(i + 1), style = MaterialTheme.typography.labelMedium, color = theme.accentGradientEnd, modifier = Modifier.width(28.dp))
                Text(t.title, style = MaterialTheme.typography.titleSmall, color = if (t.isPlayable) Tokens.textPrimary else Tokens.textDisabled, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                t.sizeBytes?.let { Text(PrimaryAsset.formatBytes(it), style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted) }
                if (t.isPlayable) IconButtonRound(Icons.Rounded.PlayArrow, "Play ${t.title}", { session.playback.queueAudio(playable, playable.indexOfFirst { it.assetId == t.id }) }, size = 32.dp, filled = true)
            }
        }
    }
}

@Composable
private fun ArchiveBlock(session: AppSession, state: DetailState) {
    val scope = rememberCoroutineScope()
    val items = state.feedItems
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader("Archive", subtitle = items?.let { "${it.count { i -> i.archived }} of ${it.size} archived" })
        when {
            items == null -> MediaRowSkeleton(4)
            items.isEmpty() -> Notice("Nothing archived yet — the node polls this source on its schedule.")
            else -> for (item in items) Row(Modifier.fillMaxWidth().background(Tokens.surface1, RoundedCornerShape(Tokens.radiusInput)).border(Tokens.hairline, Tokens.border, RoundedCornerShape(Tokens.radiusInput)).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, color = if (item.archived) Tokens.textPrimary else Tokens.textDisabled)
                    Text(listOfNotNull(item.publishedAt?.take(10), if (item.archived) "archived" else "not archived yet").joinToString("  ·  "), style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
                }
                if (item.archived && item.workId != null) SecondaryButton("Open", {
                    val a = session.api ?: return@SecondaryButton
                    val wid = item.workId ?: return@SecondaryButton
                    scope.launch {
                        val d = session.io { a.work(wid) }.getOrNull()
                        val asset = d?.primaryAsset
                        if (asset == null) session.toast(Toast.Kind.INFO, "No archived bytes held for this item yet.")
                        else session.io { session.openExternally.open(session.config.baseUrl, asset.blobHash, session.config.bearerToken.trim(), null, asset.mime ?: "text/html", item.title) }.getOrNull()?.let { session.toast(Toast.Kind.INFO, it) }
                    }
                }, icon = Icons.Rounded.OpenInNew, compact = true)
            }
        }
    }
}

/** A film / single-file work: the one file, its quality, and what plays it. */
@Composable
private fun FileBlock(session: AppSession, detail: WorkDetail, state: DetailState) {
    val asset = detail.primaryAsset ?: return
    val file = state.assets?.firstOrNull { it.blobHash == asset.blobHash }
    Panel("This copy") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (tag in file?.let { Series.qualityTags(it) }.orEmpty()) RuleCode(tag, tone = Tokens.textMuted)
            asset.mime?.let { RuleCode(it, tone = Tokens.textMuted) }
            asset.sizeBytes?.let { Text(PrimaryAsset.formatBytes(it), style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted) }
        }
        val subs = state.assets.orEmpty().filter { Series.isSubtitle(it) }
        Text(if (subs.isEmpty()) "No captions held." else "Captions: " + subs.joinToString(", ") { it.filename?.substringAfterLast('.', "")?.let { ext -> it.filename!!.removeSuffix(".$ext").substringAfterLast('.') } ?: "?" }, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
    }
}

/** Curate → captions and artwork: what is held per episode, and the honest limits of what the node can fetch. */
@Composable
private fun SidecarsPanel(session: AppSession, state: DetailState, seasons: List<Season>, wants: List<DesiredItem>) {
    val assets = state.assets.orEmpty()
    val subs = assets.filter { Series.isSubtitle(it) }
    val art = assets.filter { it.role == "artwork" }
    Panel("Captions & artwork") {
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
        Notice("heyarr's tool surface has no caption or artwork search yet: sidecars arrive with a release or a scan. Asking the indexers again (Releases) is the only fetch this node can queue.", tone = Tokens.slate)
    }
}

/** "Would this be accepted?" — describe a release, get every rule back. Absent fields stay absent so they read as undetermined. */
@Composable
private fun ExplainPanel(session: AppSession, wants: List<DesiredItem>) {
    val scope = rememberCoroutineScope()
    val profiles = session.profiles
    var profile by remember(profiles) { mutableStateOf(wants.firstOrNull()?.let { w -> profiles.firstOrNull { it.id == w.qualityProfileId }?.name } ?: profiles.firstOrNull()?.name ?: "") }
    var title by remember { mutableStateOf("") }
    var resolution by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var codec by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<McpResult<Explanation?>?>(null) }
    var busy by remember { mutableStateOf(false) }
    Panel("Score a release") {
        Text("Describe a release and heyarr explains, rule by rule, whether the profile would accept it. Leave a field blank when you do not know — a blank reads as undetermined, a guess reads as a claim.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (p in profiles) FilterChip(p.name, profile == p.name, { profile = p.name }) }
        Field("Title", title) { title = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Resolution (480/720/1080/2160)", resolution, Modifier.weight(1f)) { resolution = it.filter { c -> c.isDigit() } }
            Field("Source (remux/bluray/web-dl/…)", source, Modifier.weight(1f)) { source = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Video codec", codec, Modifier.weight(1f)) { codec = it }
            Field("Size (bytes)", size, Modifier.weight(1f)) { size = it.filter { c -> c.isDigit() } }
        }
        PrimaryButton("Explain", {
            val a = session.api ?: return@PrimaryButton
            busy = true
            scope.launch {
                val rel = ReleaseToExplain("candidate", title.ifBlank { "untitled release" }, ReleaseAttributes(resolution = resolution.toIntOrNull(), source = source, videoCodec = codec, sizeBytes = size.toLongOrNull()))
                session.io { a.explain(profile, listOf(rel)) }.onSuccess { result = it }
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

@Composable
fun Field(label: String, value: String, modifier: Modifier = Modifier, placeholder: String? = null, secret: Boolean = false, onChange: (String) -> Unit) {
    val accent = LocalMediaTheme.current.accent
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
        Box(
            Modifier.fillMaxWidth().background(Tokens.surface2, RoundedCornerShape(Tokens.radiusInput))
                .border(if (focused) 2.dp else Tokens.hairline, if (focused) accent else Tokens.border, RoundedCornerShape(Tokens.radiusInput))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (value.isEmpty() && placeholder != null) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = Tokens.textDisabled)
            BasicTextField(
                value, onChange, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = Tokens.textPrimary), cursorBrush = SolidColor(accent),
                interactionSource = interaction, modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            )
        }
    }
}


// ── Curate ────────────────────────────────────────────────────────────────────────────

/**
 * The curation surface as tables, one column, each section collapsible: what each want
 * is measured against and where it stands; every held file with the verdict the profile
 * gave it (rules behind a click); the indexer candidates with Acquire; a release scorer;
 * health; captions and artwork; the works the scanner minted for the same title; the
 * identifiers; the raw file list. Nothing here is paraphrased — rule codes, states and
 * the node's detail lines are shown as sent.
 */
@Composable
private fun CurateTab(session: AppSession, detail: WorkDetail, type: MediaType, wants: List<DesiredItem>, state: DetailState, seasons: List<Season>, reload: () -> Unit) {
    val scope = rememberCoroutineScope()
    val assets = state.assets.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        // 1. Status
        Section("Wants & status", subtitle = if (wants.isEmpty()) "Not wanted — nothing measures this work" else "${wants.size} want${if (wants.size == 1) "" else "s"} on this work", trailing = { GhostButton("Refresh", reload) }) {
            DataTable(
                columns = listOf(TableColumn("Scope", width = 90.dp), TableColumn("Profile", width = 120.dp), TableColumn("State", width = 120.dp), TableColumn("Content", width = 110.dp), TableColumn("Placement", 1.2f), TableColumn("Upgrade", 1.4f), TableColumn("Monitor", width = 120.dp, alignEnd = true)),
                rowCount = wants.size, emptyText = "Not wanted. Want it (or a season) to see every rule heyarr would apply.",
            ) { r, c ->
                val w = wants[r]
                val sat = (state.satisfaction[w.id] as? McpResult.Ok)?.value
                when (c) {
                    0 -> Cell(w.id.takeIf { it.startsWith("pending:") }?.let { "sending…" } ?: w.scope, muted = true)
                    1 -> Cell(session.profiles.firstOrNull { it.id == w.qualityProfileId }?.name ?: w.qualityProfileId ?: "?", mono = true)
                    2 -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        StatusPill(LibraryStatus.ofState(w.state))
                        // While a transfer is in flight, how far it has got.
                        w.downloadProgress?.let { p ->
                            Text(
                                "${(p * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = Tokens.textMuted,
                            )
                        }
                    }
                    3 -> Text(sat?.contentSatisfaction?.replace('_', ' ') ?: (w.content ?: "…"), style = MaterialTheme.typography.labelMedium, color = verdictColor(if ((sat?.contentSatisfaction ?: w.content) == "satisfied") "pass" else "fail"))
                    4 -> Cell(sat?.let { if (it.placementUnproven) "unproven (single node)" else it.placementSatisfaction } ?: (w.placement ?: "…"), muted = true)
                    5 -> Cell(sat?.let { (if (it.upgradeEligible) "eligible" else it.upgradeStatus.replace('_', ' ')) + (it.upgradeDetail.takeIf { d -> d.isNotBlank() }?.let { d -> " — $d" } ?: "") } ?: (w.detail ?: ""), muted = true, maxLines = 2)
                    6 -> FilterChip(if (w.monitor) "Monitoring" else "Off", w.monitor, {
                        val a = session.api ?: return@FilterChip
                        scope.launch { session.io { a.monitor(w.id, !w.monitor) }.onSuccess { res -> if (res is McpResult.Refused) session.refused(res) else session.refreshIndex() } }
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
                columns = listOf(TableColumn("File", 3f), TableColumn("Size", width = 80.dp, alignEnd = true), TableColumn("Verdict", width = 110.dp), TableColumn("Score", width = 60.dp, alignEnd = true), TableColumn("Rejected by", 1.6f)),
                rowCount = held.size, emptyText = "Nothing held for this work.",
                detailLabel = { r -> held[r].filename ?: held[r].id },
                detail = { r -> verdicts[held[r].id]?.let { ReasonList(it.reasons) } ?: Text("No verdict — this file is not measured by any want.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted) },
            ) { r, c ->
                val t = held[r]; val v = verdicts[t.id]
                when (c) {
                    0 -> Cell(t.filename ?: t.id)
                    1 -> Cell(t.sizeBytes?.let { PrimaryAsset.formatBytes(it) } ?: "", muted = true)
                    2 -> Text(when { v == null -> "unmeasured"; v.accepted -> "accepted"; else -> "rejected" }, style = MaterialTheme.typography.labelMedium, color = verdictColor(when { v == null -> ""; v.accepted -> "pass"; else -> "fail" }))
                    3 -> Cell(v?.score?.toString() ?: "", muted = true, mono = true)
                    4 -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { for (x in v?.rejectedBy.orEmpty().take(2)) RuleCode(x.rule, tone = Tokens.danger); if ((v?.rejectedBy?.size ?: 0) > 2) Cell("+${v!!.rejectedBy.size - 2}", muted = true) }
                }
            }
        }

        // 3. Indexer candidates
        val cands = wants.flatMap { w -> state.candidates[w.id].orEmpty().map { w to it } }
        Section("Indexer candidates", subtitle = if (wants.isEmpty()) "Want it first — candidates belong to a want" else "${cands.size} from the last search", trailing = {
            if (wants.isNotEmpty()) SecondaryButton("Search indexers now", {
                val a = session.api ?: return@SecondaryButton
                scope.launch { session.io { a.searchReleases(wants.first().id) }.onSuccess { r -> when (r) { is McpResult.Ok -> session.toast(Toast.Kind.INFO, "Search queued", "Indexers answer within a minute; the Downloads tab under Library shows the job."); is McpResult.Refused -> session.refused(r) } } }
            }, icon = Icons.Rounded.Search, compact = true)
        }) {
            DataTable(
                columns = listOf(TableColumn("Release", 3f), TableColumn("Provider", width = 110.dp), TableColumn("Size", width = 80.dp, alignEnd = true), TableColumn("Score", width = 60.dp, alignEnd = true), TableColumn("Verdict", width = 100.dp), TableColumn("", width = 110.dp, alignEnd = true)),
                rowCount = cands.size, emptyText = if (wants.isEmpty()) "No want, no candidates." else "The last search found nothing${wants.firstOrNull()?.detail?.let { " — $it" } ?: ""}.",
                detailLabel = { r -> cands[r].second.title },
                detail = { r -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { RejectedBy(cands[r].second.rejectedBy); ReasonList(cands[r].second.reasons) } },
            ) { r, c ->
                val (w, cand) = cands[r]
                when (c) {
                    0 -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { Cell(cand.title); if (cand.selected) Text("selected", style = MaterialTheme.typography.labelSmall, color = LocalMediaTheme.current.accentGradientEnd) }
                    1 -> Cell(cand.provider ?: "", muted = true, mono = true)
                    2 -> Cell(cand.sizeBytes?.let { PrimaryAsset.formatBytes(it) } ?: "", muted = true)
                    3 -> Cell(cand.score.toString(), muted = true, mono = true)
                    4 -> Text(if (cand.accepted) "accepted" else "rejected", style = MaterialTheme.typography.labelMedium, color = verdictColor(if (cand.accepted) "pass" else "fail"))
                    5 -> PrimaryButton("Acquire", {
                        val a = session.api ?: return@PrimaryButton
                        scope.launch {
                            state.busy = cand.candidateId
                            session.io { a.acquire(w.id, cand.candidateId) }.onSuccess { res -> when (res) { is McpResult.Ok -> { session.toast(Toast.Kind.SUCCESS, "Acquiring", cand.title); session.refreshIndex(); reload() }; is McpResult.Refused -> session.refused(res) } }
                            state.busy = null
                        }
                    }, icon = Icons.Rounded.Download, compact = true, enabled = state.busy == null)
                }
            }
        }

        // 4. Score a release
        Section("Score a release", subtitle = "Ask the profile about a release you are looking at", initiallyOpen = false) { ExplainPanel(session, wants) }

        // 5. Health
        val hash = detail.primaryAsset?.blobHash
        Section("Health", subtitle = hash?.let { "primary blob ${it.take(20)}…" } ?: "no held bytes to check", trailing = {
            if (hash != null) SecondaryButton("Verify bytes now", {
                val a = session.api ?: return@SecondaryButton
                scope.launch { session.io { a.verifyBlob(hash) }.onSuccess { r -> when (r) { is McpResult.Ok -> session.toast(Toast.Kind.INFO, "Verification queued", "Re-hashing runs as a job; see Library → Downloads."); is McpResult.Refused -> session.refused(r) } } }
            }, icon = Icons.Rounded.Verified, compact = true)
        }) {
            when (val r = state.replicas) {
                null -> if (hash != null) Skeleton(Modifier.fillMaxWidth().height(40.dp)) else Text("Nothing to check.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
                is McpResult.Refused -> Notice("get_replica_status: ${r.message}", tone = Tokens.danger)
                is McpResult.Ok -> DataTable(columns = listOf(TableColumn("Peer", 1f), TableColumn("Copy", width = 120.dp), TableColumn("Verified", width = 100.dp)), rowCount = r.value.size, emptyText = "No replica report — on a single-node fabric there is nowhere for bytes to converge to.") { i, c ->
                    val rep = r.value[i]
                    when (c) { 0 -> Cell(rep.peer); 1 -> Cell(rep.state, mono = true); 2 -> Text(if (rep.verified) "yes" else "no", style = MaterialTheme.typography.labelMedium, color = verdictColor(if (rep.verified) "pass" else "undetermined")) }
                }
            }
        }

        // 6. Captions & artwork
        Section("Captions & artwork", initiallyOpen = false) { SidecarsPanel(session, state, seasons, wants) }

        // 7. Also catalogued as
        if (state.variants.isNotEmpty()) Section("Also catalogued as", subtitle = "Works the scanner minted for this title's download folders (heyarr-core#470) — hidden from listings, folded here") {
            DataTable(columns = listOf(TableColumn("Work", 2f), TableColumn("Season", width = 80.dp), TableColumn("", width = 90.dp, alignEnd = true)), rowCount = state.variants.size) { i, c ->
                val v = state.variants[i]
                when (c) { 0 -> Cell(v.title); 1 -> Cell(Variants.seasonOf(v)?.let { "S$it" } ?: "", mono = true, muted = true); 2 -> GhostButton("Open", { state.openVariant(v) }) }
            }
        }

        // 8. Details
        Section("Identifiers", initiallyOpen = false) {
            DataTable(columns = listOf(TableColumn("Key", width = 140.dp), TableColumn("Value", 1f)), rowCount = 3 + state.externalIds.size) { i, c ->
                val rows = listOf("work id" to detail.work.id, "work key" to (detail.work.workKey ?: "—"), "type" to type.label) + state.externalIds.map { it.source to it.value }
                val (k, v) = rows[i]
                when (c) { 0 -> Cell(k, muted = true, mono = true); 1 -> Cell(v, mono = true) }
            }
        }

        // 9. Files
        Section("All files", subtitle = "${assets.size} scanned", initiallyOpen = false) {
            DataTable(columns = listOf(TableColumn("File", 3f), TableColumn("Role", width = 90.dp), TableColumn("Type", width = 130.dp), TableColumn("Size", width = 80.dp, alignEnd = true), TableColumn("", width = 90.dp)), rowCount = assets.size, emptyText = "No files scanned.") { i, c ->
                val t = assets[i]
                when (c) {
                    0 -> Cell(t.filename ?: t.id, color = if (t.isPlayable || t.role == "artwork") Tokens.textPrimary else Tokens.textDisabled)
                    1 -> Cell(t.role ?: "primary", mono = true, muted = true)
                    2 -> Cell(t.mime ?: "", mono = true, muted = true)
                    3 -> Cell(t.sizeBytes?.let { PrimaryAsset.formatBytes(it) } ?: "", muted = true)
                    4 -> if (t.missingSince != null) Text("missing", style = MaterialTheme.typography.labelSmall, color = Tokens.danger) else Cell("")
                }
            }
        }
    }
}
