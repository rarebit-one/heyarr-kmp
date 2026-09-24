package one.rarebit.heyarr.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.rarebit.heyarr.mobile.catalog.ContinueEntry
import one.rarebit.heyarr.mobile.library.Variants
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.core.mcp.SearchHit
import one.rarebit.heyarr.core.mcp.Want
import one.rarebit.heyarr.mobile.nav.Route
import one.rarebit.heyarr.mobile.nav.detailRoute
import one.rarebit.heyarr.mobile.search.FollowedSource
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.ui.theme.CardAspect
import one.rarebit.heyarr.mobile.theme.MediaScope
import one.rarebit.heyarr.ui.theme.MediaThemes
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.mobile.ui.components.CardAction
import one.rarebit.heyarr.mobile.ui.components.GhostButton
import one.rarebit.heyarr.mobile.ui.components.Hero
import one.rarebit.heyarr.mobile.ui.components.HeroSkeleton
import one.rarebit.heyarr.mobile.ui.components.MediaCard
import one.rarebit.heyarr.mobile.ui.components.Notice
import one.rarebit.heyarr.mobile.ui.components.PrimaryButton
import one.rarebit.heyarr.mobile.ui.components.Rail
import one.rarebit.heyarr.mobile.ui.components.RailState
import one.rarebit.heyarr.mobile.ui.components.SecondaryButton
import one.rarebit.heyarr.mobile.ui.components.rememberCover

/** Everything the Home screen shows, loaded rail by rail so a slow one never blanks the page. */
class HomeState {
    var spotlight by mutableStateOf<RailState<Work>>(RailState.Loading)
    var recent by mutableStateOf<RailState<Work>>(RailState.Loading)
    var byType by mutableStateOf<Map<MediaType, RailState<SearchHit>>>(MediaType.SEARCHABLE.associateWith { RailState.Loading })
    var missing by mutableStateOf<RailState<Want>>(RailState.Loading)
    var upgrades by mutableStateOf<RailState<Want>>(RailState.Loading)
    var followed by mutableStateOf<RailState<FollowedSource>>(RailState.Loading)
    var continueRail by mutableStateOf<RailState<ContinueEntry>>(RailState.Loading)
    var loadedOnce = false
}

/**
 * The personal rows this phone can decrypt (starred, recently played) and the actions
 * behind a card's long-press — present only on an enrolled device with a key. Nothing
 * here comes from the node in the clear; it is folded on this device (`personalstate/`).
 */
data class PersonalRows(
    val starred: List<Work> = emptyList(),
    val recentlyPlayed: List<Work> = emptyList(),
    val starredIds: Set<String> = emptySet(),
    val onToggleStar: ((Work) -> Unit)? = null,
    val onAddToPlaylist: ((Work) -> Unit)? = null,
    val onOpenPlaylists: (() -> Unit)? = null,
)

/**
 * Home / Discover — a media-mixed spotlight over themed rails, ported from
 * heyarr-desktop's `HomeScreen`. Spotlight and "Recently added" come from the works
 * list (recent first, artwork preferred); the per-type rails from `search_content` by
 * content type; "Continue" from the node's own consumption sessions; "Wanted but
 * missing" and "Could be better" from `get_missing_content` / `get_upgrade_candidates`;
 * "Following" from `list_followed`. Starred and Recently played are this phone's
 * decrypted personal state, labelled as such, and absent without a device key.
 */
@Composable
fun HomeScreen(
    session: AppSession,
    state: HomeState,
    onOpen: (Route) -> Unit,
    onWant: (String, String) -> Unit,
    onPlayContinue: (ContinueEntry) -> Unit,
    modifier: Modifier = Modifier,
    discover: Boolean = false,
    personal: PersonalRows = PersonalRows(),
    /** Raise the "Sign in to save" upgrade — shown on the guest banner. */
    onSignInToSave: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()

    fun load() {
        val a = session.api
        state.loadedOnce = true
        scope.launch {
            session.io { a.works() }.fold(
                onSuccess = { all ->
                    val variants = Variants.variantIds(all)
                    val works = all.filter { it.id !in variants }
                    state.recent = RailState.Loaded(works.filter { it.kind != "document" }.take(24))
                    state.spotlight = RailState.Loaded(works.filter { it.kind != "document" && it.kind != "unknown" }.take(6))
                },
                onFailure = { state.recent = RailState.Failed(it.message ?: "failed"); state.spotlight = RailState.Failed(it.message ?: "failed") },
            )
        }
        for (t in MediaType.SEARCHABLE) scope.launch {
            val r = session.io { a.listByType(t, limit = 40) }.fold(onSuccess = { RailState.Loaded(it.works) }, onFailure = { RailState.Failed(it.message ?: "failed") })
            state.byType = state.byType + (t to r)
        }
        // The want index (Missing / Could-be-better), followed sources and the continue rail
        // are enrolled-only surfaces (GuestGate): a guest would only earn a 403, so skip them
        // and leave the rails empty (they hide themselves) rather than showing an error.
        if (session.isGuest) {
            state.missing = RailState.Loaded(emptyList()); state.upgrades = RailState.Loaded(emptyList())
            state.followed = RailState.Loaded(emptyList()); state.continueRail = RailState.Loaded(emptyList())
        } else {
            scope.launch { state.missing = session.io { a.missing(40) }.fold(onSuccess = { RailState.Loaded(it) }, onFailure = { RailState.Failed(it.message ?: "failed") }) }
            scope.launch { state.upgrades = session.io { a.upgradeCandidates(40) }.fold(onSuccess = { RailState.Loaded(it) }, onFailure = { RailState.Failed(it.message ?: "failed") }) }
            scope.launch { state.followed = session.io { a.followed() }.fold(onSuccess = { RailState.Loaded(it) }, onFailure = { RailState.Failed(it.message ?: "failed") }) }
            scope.launch { state.continueRail = session.io { a.continueRail() }.fold(onSuccess = { RailState.Loaded(it) }, onFailure = { RailState.Failed(it.message ?: "failed") }) }
        }
    }

    LaunchedEffect(Unit) { if (!state.loadedOnce) load() }

    fun actionsFor(w: Work): List<CardAction> = buildList {
        personal.onToggleStar?.let { toggle -> add(CardAction(if (w.id in personal.starredIds) "Unstar" else "Star", if (w.id in personal.starredIds) Icons.Rounded.Star else Icons.Rounded.StarBorder) { toggle(w) }) }
        personal.onAddToPlaylist?.let { add -> add(CardAction("Add to playlist", Icons.Rounded.PlaylistAdd) { add(w) }) }
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = Tokens.s4), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        if (session.isGuest) item { Box(Modifier.padding(horizontal = Tokens.screenPadding)) { GuestBanner(onSignInToSave) } }
        item { Box(Modifier.padding(horizontal = Tokens.screenPadding)) { SpotlightBlock(session, state, onOpen, onWant) } }
        if (discover) item {
            Notice(
                "Discover asks the metadata provider for content the library does not hold.",
                Modifier.padding(horizontal = Tokens.screenPadding),
                detail = "A node with no TVDB provider configured (ADR-0058) answers discovery with a refusal, quoted as sent. Search still finds everything already catalogued, and Missing lets you Want a title the library has never seen.",
                icon = Icons.Rounded.Info,
            )
        }
        val cont = state.continueRail
        if (cont !is RailState.Loaded || cont.items.isNotEmpty()) item {
            Rail("Continue", cont, subtitle = "Unfinished sessions this node recorded — where a device stopped, not personal history", emptyText = "", skeletonAspect = CardAspect.SQUARE, skeletonWidth = 200.dp, key = { it.sessionId }) { e ->
                val type = MediaType.from(e.contentType)
                val cover by rememberCover(session, type, e.workTitle, e.artworkPath, e.year)
                MediaCard(
                    e.workTitle, type, onOpen = { if (e.isPlayable) onPlayContinue(e) else onOpen(detailRoute(e.workId, type, e.workTitle, from = "Home")) },
                    subtitle = listOfNotNull(e.subtitle, e.progressLabel).joinToString("  ·  "), meta = listOf(e.state), artwork = cover.url,
                    status = session.index.statusOf(e.workId), width = 200.dp, progress = e.fraction, aspectOverride = CardAspect.SQUARE,
                    actions = listOf(CardAction("Open ${e.workTitle}") { onOpen(detailRoute(e.workId, type, e.workTitle, from = "Home")) }),
                )
            }
        }
        if (personal.starred.isNotEmpty()) item {
            Rail("Starred", RailState.Loaded(personal.starred), subtitle = "Decrypted on this phone — the node never sees it", key = { it.id }, trailing = personal.onOpenPlaylists?.let { f -> @Composable { GhostButton("Playlists", f) } }) { w ->
                WorkCard(session, w, onOpen, onWant, actionsFor(w), from = "Home")
            }
        }
        if (personal.recentlyPlayed.isNotEmpty()) item {
            Rail("Recently played", RailState.Loaded(personal.recentlyPlayed), subtitle = "This phone's own history, decrypted here", key = { it.id }) { w ->
                WorkCard(session, w, onOpen, onWant, actionsFor(w), from = "Home")
            }
        }
        item {
            Rail("Recently added", state.recent, emptyText = "Nothing added yet.", trailing = { GhostButton("Refresh", ::load, icon = Icons.Rounded.Refresh) }, key = { it.id }) { w ->
                WorkCard(session, w, onOpen, onWant, actionsFor(w), from = "Home")
            }
        }
        for (t in MediaType.SEARCHABLE) item {
            MediaScope(t) {
                val square = MediaThemes.of(t).aspect == CardAspect.SQUARE
                Rail(
                    t.plural, state.byType[t] ?: RailState.Loading, emptyText = "No ${t.plural.lowercase()} in the library yet.",
                    skeletonAspect = MediaThemes.of(t).aspect, skeletonWidth = if (square) Tokens.squareWidth else Tokens.posterWidth, key = { it.workId },
                ) { hit ->
                    val type = MediaType.from(hit.contentType)
                    val cover by rememberCover(session, type, hit.title, hit.artworkPath, hit.year, hit.creator)
                    MediaCard(
                        hit.title, type, onOpen = { onOpen(detailRoute(hit.workId, t, hit.title, from = "Home")) },
                        subtitle = hit.creator, meta = listOf(hit.year?.toString()), artwork = cover.url, status = session.index.statusOf(hit.workId),
                        onWant = { onWant(hit.workId, hit.title) }, width = if (square) Tokens.squareWidth else Tokens.posterWidth, showBadge = false,
                    )
                }
            }
        }
        if (!session.isGuest) item { WantRail("Wanted but missing", state.missing, onOpen, subtitle = "Wants nothing acceptable has satisfied yet", emptyText = "Nothing is missing — every want is satisfied.") }
        if (!session.isGuest) item { WantRail("Could be better", state.upgrades, onOpen, subtitle = "Satisfied and monitored; a better release may still turn up", emptyText = "No upgrade candidates.") }
        if (!session.isGuest) item {
            MediaScope(MediaType.PODCAST) {
                Rail("Following", state.followed, subtitle = "Standing subscriptions the node polls", emptyText = "You follow nothing yet — add a feed or TVDB series in Settings.", skeletonAspect = CardAspect.SQUARE, skeletonWidth = Tokens.squareWidth, key = { it.id }) { s ->
                    val type = MediaType.from(s.type)
                    val cover by rememberCover(session, type, s.title, null, feedRef = s.feedRef)
                    MediaCard(
                        s.title, type, onOpen = { s.workId?.let { onOpen(detailRoute(it, type, s.title, from = "Home")) } },
                        subtitle = s.feedRef, meta = listOf("${s.itemsArchived ?: 0}/${s.itemsKnown ?: 0} archived", s.health), artwork = cover.url, width = Tokens.squareWidth,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** The guest affordance at the top of Home: browse freely, and a route to "Sign in to save". */
@Composable
private fun GuestBanner(onSignIn: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Notice(
            "Browsing as guest — watch, listen and read freely.",
            tone = Tokens.slate,
            icon = Icons.Rounded.Info,
            detail = "Sign in to keep wants, follows, playlists and your place across devices.",
        )
        SecondaryButton("Sign in to save", onSignIn, icon = Icons.Rounded.Star)
    }
}

@Composable
private fun WorkCard(session: AppSession, w: Work, onOpen: (Route) -> Unit, onWant: (String, String) -> Unit, actions: List<CardAction>, from: String) {
    val type = MediaType.from(w.kind)
    val status = session.index.statusOf(w.id)
    val cover by rememberCover(session, type, w.title, w.artworkPath, w.year, w.artist ?: w.author)
    MediaCard(w.title, type, onOpen = { onOpen(detailRoute(w.id, type, w.title, from = from)) }, subtitle = w.artist ?: w.author, meta = listOf(w.year?.toString()), artwork = cover.url, status = status, onWant = { onWant(w.id, w.title) }, actions = actions)
}

@Composable
private fun SpotlightBlock(session: AppSession, state: HomeState, onOpen: (Route) -> Unit, onWant: (String, String) -> Unit) {
    when (val s = state.spotlight) {
        RailState.Loading -> HeroSkeleton()
        is RailState.Failed -> Notice("Couldn't load the library: ${s.message}", tone = Tokens.danger)
        is RailState.Loaded -> {
            var i by remember(s.items) { mutableStateOf(0) }
            val work = s.items.getOrNull(i)
            if (work == null) { Notice("The library is empty. Want something from Search, or scan a library root on the node.", icon = Icons.Rounded.Info); return }
            val type = MediaType.from(work.kind)
            val cover by rememberCover(session, type, work.title, work.artworkPath, work.year, work.artist ?: work.author)
            val status = session.index.statusOf(work.id)
            Hero(
                title = work.title, type = type, kicker = "Spotlight", meta = listOf(work.year?.toString(), work.artist ?: work.author, work.kind),
                artwork = cover.url, status = status,
                description = work.synopsis?.take(220) ?: cover.external?.synopsis?.let { it.take(220) + (cover.external?.source?.let { s -> "  ·  via $s" } ?: "") },
                primary = {
                    val theme = MediaThemes.of(type)
                    PrimaryButton(theme.ctaLabel, { onOpen(detailRoute(work.id, type, work.title, from = "Home")) }, icon = Icons.Rounded.PlayArrow)
                },
                secondary = {
                    if (status == LibraryStatus.NOT_TRACKED) SecondaryButton("Want", { onWant(work.id, work.title) }, icon = Icons.Rounded.Add)
                    if (s.items.size > 1) GhostButton("Next", { i = (i + 1) % s.items.size })
                },
            )
        }
    }
}

@Composable
private fun WantRail(title: String, state: RailState<Want>, onOpen: (Route) -> Unit, subtitle: String, emptyText: String) {
    Rail(title, state, subtitle = subtitle, emptyText = emptyText, key = { it.desiredItemId }) { w ->
        val status = LibraryStatus.ofState(w.state)
        MediaCard(w.title, MediaType.UNKNOWN, onOpen = { w.workId?.let { onOpen(detailRoute(it, MediaType.UNKNOWN, w.title, from = "Missing")) } }, subtitle = w.qualityProfile?.let { "profile: $it" }, meta = listOf(w.state.lowercase().replace('_', ' ')), status = status, showBadge = false)
    }
}
