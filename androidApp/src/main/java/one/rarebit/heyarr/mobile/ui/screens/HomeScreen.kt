@file:Suppress("FunctionNaming")

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
import one.rarebit.heyarr.core.library.CanonicalLookup
import one.rarebit.heyarr.core.library.Resolution
import one.rarebit.heyarr.core.library.Variants
import one.rarebit.heyarr.core.mcp.SearchHit
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.catalog.Artwork
import one.rarebit.heyarr.mobile.library.LibraryClient
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.availability
import one.rarebit.heyarr.mobile.nav.Route
import one.rarebit.heyarr.mobile.nav.detailRoute
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.mobile.ui.components.CardAction
import one.rarebit.heyarr.mobile.ui.components.Hero
import one.rarebit.heyarr.mobile.ui.components.HeroSkeleton
import one.rarebit.heyarr.mobile.ui.components.MediaCard
import one.rarebit.heyarr.mobile.ui.components.Rail
import one.rarebit.heyarr.mobile.ui.components.artworkForDp
import one.rarebit.heyarr.mobile.ui.components.rememberCover
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.RailState
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.components.StatusMark
import one.rarebit.heyarr.ui.theme.CardAspect
import one.rarebit.heyarr.ui.theme.MediaScope
import one.rarebit.heyarr.ui.theme.MediaThemes

// --- #135: named quotas and paging helpers ---

/** Maximum non-document, non-unknown works to put in the spotlight rail. */
internal const val SPOTLIGHT_QUOTA = 6

/** Maximum non-document works to put in the recent rail. */
internal const val RECENT_QUOTA = 24

/** Maximum pages to fetch before accepting whatever we have (avoids a full catalogue walk). */
internal const val HOME_PAGE_CAP = 3

/**
 * Splits [works] into (spotlight, recent) rails after document-kind filtering.
 * Spotlight excludes `"unknown"` kind; recent includes it.
 * Both rails are capped at their quotas.
 *
 * Pure helper so it can be unit-tested on plain JVM without Compose (#135).
 */
internal fun homeRails(works: List<Work>): Pair<List<Work>, List<Work>> {
    val eligible = works.filter { it.kind != "document" }
    return eligible.filter { it.kind != "unknown" }.take(SPOTLIGHT_QUOTA) to eligible.take(RECENT_QUOTA)
}

/**
 * True once both quotas are filled — the paging loop can stop early (#135).
 * Evaluated on the visible set (variants already removed) so a page full of hidden variants
 * does not falsely satisfy the quota.
 */
internal fun quotasMet(works: List<Work>): Boolean {
    val eligible = works.filter { it.kind != "document" }
    return eligible.filter { it.kind != "unknown" }.size >= SPOTLIGHT_QUOTA &&
        eligible.size >= RECENT_QUOTA
}

/**
 * True when the home paging loop should stop fetching pages.
 *
 * Hard stops ([worksPage] null, [cursor] null, [pages] cap) always apply.
 * The [quotasMet] shortcut fires **only when [complete] is true** — when a canonical lookup
 * failed, some variants may still be visible and the canonical that would collapse them
 * may appear on a later page, so we must keep paging (#139).
 *
 * Pure helper so it can be unit-tested on plain JVM without Compose.
 */
internal fun loadDone(worksPage: Any?, cursor: String?, pages: Int, complete: Boolean, quotasMet: Boolean): Boolean =
    worksPage == null || cursor == null || pages >= HOME_PAGE_CAP || (complete && quotasMet)

/** Everything the Home screen shows, loaded rail by rail so a slow one never blanks the page. */
class HomeState {
    var spotlight by mutableStateOf<RailState<Work>>(RailState.Loading)
    var recent by mutableStateOf<RailState<Work>>(RailState.Loading)
    var byType by mutableStateOf<Map<MediaType, RailState<SearchHit>>>(
        MediaType.SEARCHABLE.associateWith {
            RailState.Loading
        },
    )
    var loadedOnce = false

    // Holds a lookup cache for off-page canonicals (#134); lives here so it
    // survives recompositions for the duration of the screen visit.
    val canonicalLookup = CanonicalLookup()
}

/**
 * The personal row this phone can decrypt (starred) and the actions
 * behind a card's long-press — present only on an enrolled device with a key. Nothing
 * here comes from the node in the clear; it is folded on this device (`personalstate/`).
 */
data class PersonalRows(
    val starred: List<Work> = emptyList(),
    val starredIds: Set<String> = emptySet(),
    val onToggleStar: ((Work) -> Unit)? = null,
    val onAddToPlaylist: ((Work) -> Unit)? = null,
    val onOpenPlaylists: (() -> Unit)? = null,
)

/**
 * Home / Discover — a media-mixed spotlight over themed rails, ported from
 * heyarr-desktop's `HomeScreen`. Spotlight and "Recently added" come from the works
 * list (recent first, artwork preferred); the per-type rails from `search_content` by
 * content type — only what can be played. Wants, upgrades and followed sources live in
 * Manage, not on the way in to watching something. Starred is this phone's decrypted
 * personal state, labelled as such, and absent without a device key.
 */
@Composable
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod") // Compose the independent archive rails.
fun HomeScreen(
    session: AppSession,
    state: HomeState,
    onOpen: (Route) -> Unit,
    modifier: Modifier = Modifier,
    personal: PersonalRows = PersonalRows(),
    /** Raise the "Sign in to save" upgrade — shown on the guest banner. */
    onSignInToSave: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()

    fun load() {
        // Clear any stale canonicals from a previous load so a rescan's added/removed works
        // are reflected immediately rather than being shadowed by the old cache (#142).
        state.canonicalLookup.clear()
        val a = session.api
        state.loadedOnce = true
        scope.launch {
            // Page up to HOME_PAGE_CAP times so we have enough non-variant works to fill both
            // rails even when the first page is heavy with season variants (#135). We stop
            // early once both quotas are filled (or we run out of pages / hit an error).
            // Off-page canonicals are resolved before every quota check, so a page heavy with
            // season variants doesn't stop the paging early (Codex on #138). The lookup caches
            // per (base, kind), so re-resolving as pages accumulate costs no extra request.
            //
            // The early-stop fires only when the last resolveFor call was complete (no lookup
            // failed). An incomplete pass means a variant's canonical is unknown, so it stays
            // visible and the quota may be falsely satisfied (#139).
            suspend fun visible(works: List<Work>): Pair<List<Work>, Boolean> {
                val resolution = session.io {
                    state.canonicalLookup.resolveFor(works) { q, kind ->
                        a.worksMatching(LibraryClient.variantQuery(q, null), kind)
                    }
                }.getOrElse { Resolution(emptyList(), complete = false) }
                val variantIds = Variants.variantIds(works + resolution.resolved)
                // Only what can be played counts, so the quotas page on until the rails can fill with it:
                // a work a want created before anything arrived has no file.
                return works.filter { it.id !in variantIds && it.isPlayable } to resolution.complete
            }
            var cursor: String? = null
            val accumulated = mutableListOf<Work>()
            var pageError: Throwable? = null
            var pages = 0
            var shown = emptyList<Work>()
            var done = false
            while (!done) {
                val worksPage = session.io { a.libraryPage(cursor, limit = 100) }.getOrElse {
                    pageError = it
                    null
                }
                worksPage?.let {
                    accumulated.addAll(it.items)
                    cursor = it.nextCursor
                }
                pages++
                val (visibleWorks, complete) = visible(accumulated)
                shown = visibleWorks
                done = loadDone(worksPage, cursor, pages, complete, quotasMet(shown))
            }
            val err = pageError
            if (accumulated.isEmpty() && err != null) {
                val msg = err.message ?: "failed"
                state.recent = RailState.Failed(msg)
                state.spotlight = RailState.Failed(msg)
            } else {
                val (spotlight, recent) = homeRails(shown)
                state.recent = RailState.Loaded(recent)
                state.spotlight = RailState.Loaded(spotlight)
            }
        }
        for (t in MediaType.SEARCHABLE) {
            scope.launch {
                val r = session.io {
                    a.listByType(t, limit = 40)
                }.fold(onSuccess = {
                    RailState.Loaded(it.works)
                }, onFailure = { RailState.Failed(it.message ?: "failed") })
                state.byType = state.byType + (t to r)
            }
        }
    }

    LaunchedEffect(Unit) { if (!state.loadedOnce) load() }

    fun actionsFor(w: Work): List<CardAction> = buildList {
        personal.onToggleStar?.let { toggle ->
            add(
                CardAction(
                    if (w.id in
                        personal.starredIds
                    ) {
                        "Unstar"
                    } else {
                        "Star"
                    },
                    if (w.id in
                        personal.starredIds
                    ) {
                        Icons.Rounded.Star
                    } else {
                        Icons.Rounded.StarBorder
                    },
                ) { toggle(w) },
            )
        }
        personal.onAddToPlaylist?.let { add ->
            add(CardAction("Add to playlist", Icons.Rounded.PlaylistAdd) { add(w) })
        }
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = Tokens.s4),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (session.isGuest) {
            item {
                Box(Modifier.padding(horizontal = Tokens.screenPadding)) { GuestBanner(onSignInToSave) }
            }
        }
        item {
            val (label, tone) = when (session.connection) {
                one.rarebit.heyarr.mobile.state.Connection.ONLINE -> "Node connected" to Tokens.success
                one.rarebit.heyarr.mobile.state.Connection.OFFLINE -> "Node offline" to Tokens.danger
                one.rarebit.heyarr.mobile.state.Connection.UNAUTHORIZED -> "Node refused connection" to Tokens.warning
                one.rarebit.heyarr.mobile.state.Connection.SIGNING_FAILED -> "Device locked" to Tokens.warning
                one.rarebit.heyarr.mobile.state.Connection.UNKNOWN -> "Checking node" to Tokens.textMuted
            }
            StatusMark(label, tone, Modifier.padding(horizontal = Tokens.screenPadding))
        }
        item {
            Box(Modifier.padding(horizontal = Tokens.screenPadding)) { SpotlightBlock(session, state, onOpen) }
        }
        if (personal.starred.isNotEmpty()) {
            item {
                Rail(
                    "Starred",
                    RailState.Loaded(
                        personal.starred,
                    ),
                    subtitle = "Decrypted on this phone — the node never sees it",
                    key = {
                        it.id
                    },
                    trailing = personal.onOpenPlaylists?.let { f -> @Composable { GhostButton("Playlists", f) } },
                ) { w ->
                    // Starred works come from the single-work endpoint, which carries no file embed.
                    WorkCard(session, w, onOpen, actionsFor(w), from = "Home", listed = false)
                }
            }
        }
        item {
            Rail("Recently catalogued", state.recent, emptyText = "Nothing added yet.", trailing = {
                GhostButton("Refresh", ::load, icon = Icons.Rounded.Refresh)
            }, key = { it.id }) { w ->
                WorkCard(session, w, onOpen, actionsFor(w), from = "Home")
            }
        }
        for (t in MediaType.SEARCHABLE) {
            item {
                MediaScope(t) {
                    val square = MediaThemes.of(t).aspect == CardAspect.SQUARE
                    Rail(
                        if (t == MediaType.BOOK) "For reading" else t.plural,
                        state.byType[t] ?: RailState.Loading,
                        emptyText = "No ${t.plural.lowercase()} in the library yet.",
                        skeletonAspect = MediaThemes.of(t).aspect,
                        skeletonWidth = if (square) Tokens.squareWidth else Tokens.posterWidth,
                        key = {
                            it.workId
                        },
                    ) { hit ->
                        val type = MediaType.from(hit.contentType)
                        val cover by rememberCover(session, type, hit.title, hit.artworkPath, hit.year, hit.creator)
                        MediaCard(
                            hit.title, type, onOpen = { onOpen(detailRoute(hit.workId, t, hit.title, from = "Home")) },
                            subtitle = hit.creator,
                            meta = listOf(
                                hit.year?.toString(),
                            ),
                            artwork = cover.url,
                            availability = session.cataloguedAvailability(hit.workId),
                            width = if (square) Tokens.squareWidth else Tokens.posterWidth,
                            showBadge = false,
                        )
                    }
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
            detail = "Sign in to keep wants and follows, and use playlists across devices.",
        )
        SecondaryButton("Sign in to save", onSignIn, icon = Icons.Rounded.Star)
    }
}

/**
 * A work's card. [listed] is a row of the works listing, whose `primary_asset` embed says whether
 * it has a file; a work from anywhere else reads its availability from the catalogue instead.
 */
@Composable
@Suppress("LongParameterList") // The card's work, its menu actions and where it was opened from.
private fun WorkCard(
    session: AppSession,
    w: Work,
    onOpen: (Route) -> Unit,
    actions: List<CardAction>,
    from: String,
    listed: Boolean = true,
) {
    val type = MediaType.from(w.kind)
    val status = session.index.statusOf(w.id)
    // Wide-aspect types (MOVIE, SERIES, VIDEO) render at 280 dp; poster types render at
    // CARD_ARTWORK_DP. Request the matching bucket to avoid a blurry downscale at 2× density
    // (ADR-0109, #142).
    val artworkDp = if (MediaThemes.of(type).aspect == CardAspect.WIDE) {
        Artwork.WIDE_CARD_ARTWORK_DP
    } else {
        Artwork.CARD_ARTWORK_DP
    }
    val cover by rememberCover(
        session,
        type,
        w.title,
        w.artworkForDp(artworkDp),
        w.year,
        w.artist ?: w.author,
    )
    MediaCard(
        w.title,
        type,
        onOpen = { onOpen(detailRoute(w.id, type, w.title, from = from)) },
        subtitle =
        w.artist ?: w.author,
        meta = listOf(w.year?.toString()),
        artwork = cover.url,
        availability = if (listed) w.availability(status) else session.cataloguedAvailability(w.id),
        actions = actions,
    )
}

@Composable
private fun SpotlightBlock(session: AppSession, state: HomeState, onOpen: (Route) -> Unit) {
    when (val s = state.spotlight) {
        RailState.Loading -> HeroSkeleton()

        is RailState.Failed -> Notice("Couldn't load the library: ${s.message}", tone = Tokens.danger)

        is RailState.Loaded -> {
            var i by remember(s.items) { mutableStateOf(0) }
            val work = s.items.getOrNull(i)
            if (work ==
                null
            ) {
                Notice(
                    "Nothing to play yet. Find something in Search and choose Get it.",
                    icon = Icons.Rounded.Info,
                )
                return
            }
            val type = MediaType.from(work.kind)
            val cover by rememberCover(
                session,
                type,
                work.title,
                work.artworkForDp(Artwork.HERO_ARTWORK_DP),
                work.year,
                work.artist ?: work.author,
            )
            Hero(
                title = work.title,
                type = type,
                kicker = "Featured from your library",
                meta = listOf(
                    work.year?.toString(),
                    work.artist ?: work.author,
                    work.kind,
                ),
                artwork = cover.url,
                description =
                work.synopsis?.take(220)
                    ?: cover.external?.synopsis?.let {
                        it.take(220) +
                            (cover.external?.source?.let { s -> "  ·  via $s" } ?: "")
                    },
                primary = {
                    val theme = MediaThemes.of(type)
                    PrimaryButton(theme.ctaLabel, {
                        onOpen(detailRoute(work.id, type, work.title, from = "Home"))
                    }, icon = Icons.Rounded.PlayArrow)
                },
                secondary = {
                    if (s.items.size > 1) GhostButton("Next", { i = (i + 1) % s.items.size })
                },
            )
        }
    }
}
