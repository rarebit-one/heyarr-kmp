@file:Suppress("FunctionNaming")

package one.rarebit.heyarr.mobile.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.heyarr.Candidate
import one.rarebit.heyarr.core.heyarr.DesiredItem
import one.rarebit.heyarr.core.library.Series
import one.rarebit.heyarr.core.library.Variants
import one.rarebit.heyarr.core.mcp.ExternalId
import one.rarebit.heyarr.core.mcp.Renderer
import one.rarebit.heyarr.core.mcp.Replica
import one.rarebit.heyarr.core.mcp.Satisfaction
import one.rarebit.heyarr.core.state.ExternalEpisode
import one.rarebit.heyarr.core.state.ExternalMeta
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.state.MetaKey
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.catalog.Artwork
import one.rarebit.heyarr.mobile.heyarr.HeyarrApi
import one.rarebit.heyarr.mobile.heyarr.McpResult
import one.rarebit.heyarr.mobile.library.LibraryClient
import one.rarebit.heyarr.mobile.library.Season
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.mobile.music.Tracks
import one.rarebit.heyarr.mobile.nav.Route
import one.rarebit.heyarr.mobile.personalstate.ItemRef
import one.rarebit.heyarr.mobile.playback.QueueEntry
import one.rarebit.heyarr.mobile.reader.ReaderFormat
import one.rarebit.heyarr.mobile.search.FollowedItem
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.mobile.ui.components.Hero
import one.rarebit.heyarr.mobile.ui.components.HeroSkeleton
import one.rarebit.heyarr.mobile.ui.components.artworkForDp
import one.rarebit.heyarr.mobile.ui.components.rememberCover
import one.rarebit.heyarr.ui.components.ErrorState
import one.rarebit.heyarr.ui.components.FilterChip
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.IconButtonRound
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.Panel
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.components.SectionHeader
import one.rarebit.heyarr.ui.components.Skeleton
import one.rarebit.heyarr.ui.theme.MediaScope
import one.rarebit.heyarr.ui.theme.MediaThemes

/** The two faces of a work: its title page (what you came for), and its Manage view (the tooling behind it). */
enum class DetailTab(val label: String) { WATCH("Title page"), CURATE("Manage") }

/** Everything the detail screen loads for one work, each piece independently. */
class DetailState(val workId: String) {
    var tab by mutableStateOf(DetailTab.WATCH)
    var detail by mutableStateOf<Work?>(null)
    var detailError by mutableStateOf<String?>(null)
    var loading by mutableStateOf(true)
    var assets by mutableStateOf<List<WorkAsset>?>(null)
    var season by mutableStateOf<Int?>(null)
    var feedItems by mutableStateOf<List<FollowedItem>?>(null)
    var externalIds by mutableStateOf<List<ExternalId>>(emptyList())
    var satisfaction by mutableStateOf<Map<String, McpResult<Satisfaction?>>>(emptyMap())
    var candidates by mutableStateOf<Map<String, List<Candidate>>>(emptyMap())
    var acquisitionError by mutableStateOf<String?>(null)
    var acquisitionRefused by mutableStateOf(false)
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
    val playVideo: (
        work: Work,
        assetId: String,
        blobHash: String,
        mime: String?,
        title: String,
        startSeconds: Double?,
        queue: List<QueueEntry>,
        artworkUrl: String?,
        subtitles: List<WorkAsset>,
    ) -> Unit,
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
        { personal.onToggleStar(entryId) },
        size = 36.dp,
    )
    IconButtonRound(Icons.Rounded.PlaylistAdd, "Add $title to a playlist", {
        personal.onAddToPlaylist(entryId)
    }, size = 36.dp)
}

/**
 * The one adaptive detail template, built for consumption first — ported from
 * heyarr-desktop's `DetailScreen`. **Watch** is what you came for: the art, a synopsis
 * when the node has one (a public source's, labelled, when it has not; an honest line
 * when neither knows), and the thing itself — seasons and episodes with their
 * thumbnails for a series, tracks for an album, the file for a film, the archive for a
 * feed — with no want state, scores or rule codes on the way. **Manage** (the top bar's
 * button) is the same work's curator view — status, held files with verdicts, indexer
 * candidates, scoring, health, captions and artwork, variants — without the hero in front.
 */
@Composable
// One screen, two views of a work, each with its own loads and callbacks.
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
fun DetailScreen(
    session: AppSession,
    route: Route.Detail,
    state: DetailState,
    play: DetailPlayback,
    onBack: () -> Unit,
    onOpen: (Route) -> Unit,
    onWant: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    personal: DetailPersonal = DetailPersonal(),
    /** "Get it" for a work nothing holds: a one-click want, or the Want sheet when no profile fits. */
    onGetIt: (String, String, MediaType) -> Unit = { id, title, _ -> onWant(id, title) },
) {
    val scope = rememberCoroutineScope()
    val wants: List<DesiredItem> = session.index.wantsFor(route.workId)
    val detail = state.detail
    val type = detail?.kind?.let { MediaType.from(it) } ?: route.typeHint

    fun load() {
        val a = session.api
        state.loading = true
        state.detailError = null
        scope.launch {
            session.io { a.work(route.workId) }.fold(
                onSuccess = { d ->
                    state.detail = d
                    state.detailError = if (d == null) "This work no longer exists." else null
                },
                onFailure = { state.detailError = it.message },
            )
            state.loading = false
            val d = state.detail ?: return@launch
            // Fetch variant siblings concurrently with external metadata. q= is a
            // sort_title substring match on the server (case-insensitive, heyarr-core#470);
            // Variants.group on this narrow page replaces a full catalogue walk.
            scope.launch {
                val query = LibraryClient.variantQuery(d.title, d.sortTitle)
                val kind = d.kind
                session.io { a.worksMatching(query, kind) }.onSuccess { siblings ->
                    state.variants = Variants.group(siblings)[route.workId].orEmpty()
                }
            }
            if (session.externalMetadata) {
                val t = MediaType.from(d.kind)
                val feedRef = if (t == MediaType.FEED ||
                    t == MediaType.PODCAST
                ) {
                    session.io { a.followed() }.getOrNull()?.firstOrNull { it.workId == d.id }?.feedRef
                } else {
                    null
                }
                val meta = session.external.lookup(MetaKey(t, d.title, d.year, d.artist ?: d.author, feedRef))
                state.external = meta
                meta?.tvmazeId?.let { id -> state.externalEpisodes = session.external.episodes(id) }
            }
        }
        scope.launch { session.io { a.assets(route.workId) }.onSuccess { state.assets = it } }
        scope.launch { session.io { a.externalIds(route.workId) }.onSuccess { state.externalIds = it } }
    }
    fun loadWants() {
        val a = session.api
        for (w in wants) {
            if (w.id.startsWith("pending:")) continue
            scope.launch {
                session.io { a.satisfaction(w.id) }.onSuccess { r ->
                    state.satisfaction =
                        state.satisfaction + (w.id to r)
                }
            }
            scope.launch {
                session.io { a.candidates(w.id) }.onSuccess { c ->
                    state.candidates =
                        state.candidates + (w.id to (c?.candidates ?: emptyList()))
                }
            }
        }
    }
    LaunchedEffect(route.workId) { if (state.detail == null) load() }
    // Each visit opens where its route says: the title page, or Manage from Missing or Downloads.
    LaunchedEffect(route) { state.tab = if (route.curate) DetailTab.CURATE else DetailTab.WATCH }
    LaunchedEffect(wants.map { it.id }) { loadWants() }
    DetailSideLoads(session, route.workId, state, type)

    val seasons = rememberSeasons(state, type)
    val cover by rememberCover(
        session,
        type,
        detail?.title ?: route.title ?: "",
        detail?.artworkForDp(Artwork.HERO_ARTWORK_DP),
        detail?.year,
        detail?.artist ?: detail?.author,
    )

    MediaScope(type) {
        // One scroll position per view, so switching to Manage starts at its top.
        val listState = remember(route.workId, state.tab) { LazyListState() }
        LazyColumn(
            modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = Tokens.screenPadding, vertical = Tokens.s3),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { DetailTopBar(route, state, onBack) }
            if (state.tab == DetailTab.WATCH) {
                item {
                    when {
                        state.loading && detail == null -> HeroSkeleton(300.dp)
                        detail == null -> ErrorState("Couldn't load this work", state.detailError, onRetry = ::load)
                        else -> DetailHero(session, detail, type, state, seasons, cover.url, play, onGetIt)
                    }
                }
                if (detail != null) {
                    item { SynopsisBlock(detail, state, cover.url != null && detail.artworkPath == null) }
                    when (type) {
                        MediaType.SERIES -> item {
                            SeasonsBlock(session, detail, seasons, state, wants, play, cover.url)
                        }

                        MediaType.MUSIC, MediaType.AUDIOBOOK -> item {
                            TracksBlock(session, detail, state, play, personal)
                        }

                        MediaType.FEED, MediaType.PODCAST -> item { ArchiveBlock(state, onOpen) }

                        MediaType.BOOK -> item { BookFilesBlock(detail, state, play, personal) }

                        else -> item { FileBlock(detail, state) }
                    }
                }
            } else {
                item {
                    SectionHeader(
                        "Manage “${detail?.title ?: route.title ?: "this title"}”",
                        subtitle = "Status, releases, rules and health",
                    )
                }
                when {
                    state.loading && detail == null -> item { HeroSkeleton(120.dp) }

                    detail == null -> item {
                        ErrorState("Couldn't load this work", state.detailError, onRetry = ::load)
                    }

                    else -> {
                        if (type == MediaType.SERIES && !session.isGuest) {
                            item { SeasonWants(session, detail, seasons, wants, state) }
                        }
                        item { CurateTab(session, detail, type, wants, state, seasons, onOpen, ::loadWants) }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** The loads that follow from what the work turned out to be: its blob's replicas, and a feed's archive. */
@Composable
private fun DetailSideLoads(session: AppSession, workId: String, state: DetailState, type: MediaType) {
    val detail = state.detail
    LaunchedEffect(detail?.blobHash) {
        val hash = detail?.blobHash ?: return@LaunchedEffect
        session.io { session.api.replicas(hash) }.onSuccess { state.replicas = it }
    }
    LaunchedEffect(type, workId) {
        if (type != MediaType.FEED && type != MediaType.PODCAST) return@LaunchedEffect
        val source =
            session.io { session.api.followed() }.getOrNull()?.firstOrNull { it.workId == workId }
                ?: return@LaunchedEffect
        session.io { session.api.followedItems(source.id) }.onSuccess { state.feedItems = it }
    }
}

/** A series' held files grouped into seasons; empty for any other kind of work. */
@Composable
private fun rememberSeasons(state: DetailState, type: MediaType): List<Season> = remember(state.assets, type) {
    if (type == MediaType.SERIES ||
        Series.isSeries(state.detail?.kind)
    ) {
        Series.seasons(state.assets.orEmpty())
    } else {
        emptyList()
    }
}

/**
 * Back, and the door to the other view. On the title page: back to where the work was opened
 * from, and Manage. In Manage opened from the title page: back to it. In Manage opened straight
 * from Missing or Downloads: back there, and Title page.
 */
@Composable
private fun DetailTopBar(route: Route.Detail, state: DetailState, onBack: () -> Unit) {
    val managing = state.tab == DetailTab.CURATE
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (managing && !route.curate) {
            GhostButton(state.detail?.title ?: route.title ?: "Title page", {
                state.tab = DetailTab.WATCH
            }, icon = Icons.Rounded.ArrowBack)
        } else {
            GhostButton(route.from, onBack, icon = Icons.Rounded.ArrowBack)
        }
        Spacer(Modifier.weight(1f))
        when {
            !managing && state.detail != null -> SecondaryButton(
                "Manage",
                { state.tab = DetailTab.CURATE },
                icon = Icons.Rounded.Build,
                compact = true,
                contentDescription = "Manage",
            )

            managing && route.curate -> GhostButton("Title page", { state.tab = DetailTab.WATCH })

            else -> Unit
        }
    }
}

/** Want more of a series, a season at a time — the panel the title page used to carry. */
@Composable
private fun SeasonWants(
    session: AppSession,
    work: Work,
    seasons: List<Season>,
    wants: List<DesiredItem>,
    state: DetailState,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton(if (state.wantMenu) "Close" else "Want seasons…", {
            state.wantMenu = !state.wantMenu
        }, icon = Icons.Rounded.Add, compact = true)
        if (state.wantMenu) {
            WantSeasonsPanel(session, work, allSeasons(seasons, state.externalEpisodes), wants, state)
        }
    }
}

/** The work's playable episodes as the player's "up next" list. */
internal fun queueOf(work: Work, seasons: List<Season>, session: AppSession): List<QueueEntry> =
    seasons.flatMap { it.episodes }.filter { it.isPlayable }.map { e ->
        QueueEntry(
            e.asset.id,
            e.asset.blobHash!!,
            work.title,
            e.label,
            e.asset.mime ?: work.mime,
            work.kind,
            e.thumbnailPath?.let {
                HeyarrApi.blobUrlFromPath(session.baseUrl, it)
            },
            e.asset.sizeBytes,
        )
    }

internal fun toggleCast(session: AppSession, state: DetailState, assetId: String, scope: CoroutineScope) {
    state.castAssetId = if (state.castAssetId == assetId) null else assetId
    if (state.renderers ==
        null
    ) {
        scope.launch { session.io { session.api.renderers() }.onSuccess { state.renderers = it } }
    }
}

@Composable
internal fun CastPicker(session: AppSession, state: DetailState) {
    val scope = rememberCoroutineScope()
    val assetId = state.castAssetId ?: return
    fun playOn(renderer: Renderer) {
        state.castAssetId = null
        scope.launch {
            state.busy = "cast"
            session.io { session.api.playHere(assetId, renderer.name, renderer.udn) }.onSuccess { r ->
                when (r) {
                    is McpResult.Ok -> session.toast(Toast.Kind.SUCCESS, "Playing on ${renderer.name}")
                    is McpResult.Refused -> session.refused(r)
                }
            }
            state.busy = null
        }
    }
    Panel("Play on a renderer", trailing = { GhostButton("Close", { state.castAssetId = null }) }) {
        val r = state.renderers
        when {
            r == null -> Skeleton(Modifier.fillMaxWidth().height(36.dp))

            r.isEmpty() -> Text(
                "No renderers found. A device that is switched off will not be listed — that is not the same as it not existing.",
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.textMuted,
            )

            else -> Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (x in r) FilterChip(x.name, false, { playOn(x) }, icon = Icons.Rounded.Cast)
            }
        }
        GhostButton("Search the network again", {
            scope.launch {
                session.io { session.api.renderers(refresh = true) }.onSuccess {
                    state.renderers =
                        it
                }
            }
        })
    }
}
