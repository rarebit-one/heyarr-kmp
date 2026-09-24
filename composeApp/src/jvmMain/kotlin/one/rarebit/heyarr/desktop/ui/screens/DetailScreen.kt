package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.material.icons.rounded.DeleteOutline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
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
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.auth.GuestGate
import one.rarebit.heyarr.core.auth.Surface
import one.rarebit.heyarr.core.feeds.FollowedItem
import one.rarebit.heyarr.core.heyarr.Candidate
import one.rarebit.heyarr.core.heyarr.ContinueEntry
import one.rarebit.heyarr.core.heyarr.DesiredItem
import one.rarebit.heyarr.desktop.heyarr.McpResult
import one.rarebit.heyarr.desktop.library.PrimaryAsset
import one.rarebit.heyarr.desktop.library.Season
import one.rarebit.heyarr.core.library.Series
import one.rarebit.heyarr.core.library.Variants
import one.rarebit.heyarr.desktop.library.Work
import one.rarebit.heyarr.desktop.library.WorkDetail
import one.rarebit.heyarr.core.mcp.ExternalId
import one.rarebit.heyarr.core.mcp.Renderer
import one.rarebit.heyarr.core.mcp.Replica
import one.rarebit.heyarr.core.mcp.Satisfaction
import one.rarebit.heyarr.desktop.music.Track
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.core.state.ExternalEpisode
import one.rarebit.heyarr.core.state.ExternalMeta
import one.rarebit.heyarr.core.state.MetaKey
import one.rarebit.heyarr.desktop.ui.components.rememberCover
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.ui.theme.LocalMediaTheme
import one.rarebit.heyarr.ui.theme.MediaScope
import one.rarebit.heyarr.ui.theme.MediaThemes
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.ui.theme.Tokens
import one.rarebit.heyarr.desktop.ui.Route
import one.rarebit.heyarr.ui.components.ErrorState
import one.rarebit.heyarr.ui.components.FilterChip
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.desktop.ui.components.Hero
import one.rarebit.heyarr.desktop.ui.components.HeroSkeleton
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.Panel
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.components.Skeleton
import one.rarebit.heyarr.ui.components.focusRing

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
        confirmButton = { one.rarebit.heyarr.ui.components.PrimaryButton(if (removing) "Removing…" else "Remove", enabled = !removing, onClick = {
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
internal fun playLocal(session: AppSession, state: DetailState, blobHash: String, label: String, scope: kotlinx.coroutines.CoroutineScope, assetId: String? = null, type: MediaType = MediaType.MOVIE, workTitle: String? = null) {
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

internal fun toggleCast(session: AppSession, state: DetailState, assetId: String, scope: kotlinx.coroutines.CoroutineScope) {
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
