package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.auth.GuestGate
import one.rarebit.heyarr.core.auth.Surface
import one.rarebit.heyarr.core.feeds.FollowedSource
import one.rarebit.heyarr.core.mcp.SearchHit
import one.rarebit.heyarr.core.mcp.Want
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.heyarr.HeyarrApi
import one.rarebit.heyarr.desktop.library.Work
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.ui.Route
import one.rarebit.heyarr.desktop.ui.components.Hero
import one.rarebit.heyarr.desktop.ui.components.HeroSkeleton
import one.rarebit.heyarr.desktop.ui.components.MediaCard
import one.rarebit.heyarr.desktop.ui.components.Rail
import one.rarebit.heyarr.desktop.ui.components.rememberCover
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.RailState
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.theme.CardAspect
import one.rarebit.heyarr.ui.theme.MediaScope
import one.rarebit.heyarr.ui.theme.MediaThemes
import one.rarebit.heyarr.ui.theme.Tokens
import one.rarebit.heyarr.ui.theme.CardAspect as Aspect

/** Everything the Home screen shows, loaded rail by rail so a slow one never blanks the page. */
class HomeState {
    var spotlight by mutableStateOf<RailState<Work>>(RailState.Loading)
    var recent by mutableStateOf<RailState<Work>>(RailState.Loading)
    var byType by mutableStateOf<Map<MediaType, RailState<SearchHit>>>(
        MediaType.SEARCHABLE.associateWith {
            RailState.Loading
        },
    )
    var missing by mutableStateOf<RailState<Want>>(RailState.Loading)
    var upgrades by mutableStateOf<RailState<Want>>(RailState.Loading)
    var followed by mutableStateOf<RailState<FollowedSource>>(RailState.Loading)
    var loadedOnce = false
}

/**
 * Home — a media-mixed spotlight over themed rails. Spotlight and "Recently
 * added" come from the works list (recent first, artwork preferred); the per-type rails
 * from `search_content` by content type; "Wanted but missing" and "Could be better"
 * from `get_missing_content` / `get_upgrade_candidates`; "Following" from `list_followed`.
 */
@Composable
fun HomeScreen(
    session: AppSession,
    state: HomeState,
    onOpen: (Route) -> Unit,
    onWant: (String, String, MediaType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    fun load() = loadHome(session, state, scope)

    LaunchedEffect(session.config) { if (!state.loadedOnce || session.api != null) load() }

    if (session.api == null) {
        ConnectFirst(onOpen, modifier)
        return
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        item { SpotlightBlock(session, state, onOpen, onWant) }
        if (session.isGuest) {
            item { GuestNotice() }
        }
        item {
            WorkRail("Recently catalogued", state.recent, session, onOpen, onWant, trailing = {
                GhostButton("Refresh", ::load, icon = Icons.Rounded.Refresh)
            })
        }
        for (t in MediaType.SEARCHABLE) {
            item { TypeRail(session, t, state.byType[t] ?: RailState.Loading, onOpen, onWant) }
        }
        if (!session.isGuest) {
            item {
                WantRail(
                    "Wanted but missing",
                    state.missing,
                    session,
                    onOpen,
                    subtitle = "Wants nothing acceptable has satisfied yet",
                    emptyText = "Nothing is missing — every want is satisfied.",
                )
            }
        }
        if (!session.isGuest) {
            item {
                WantRail(
                    "Could be better",
                    state.upgrades,
                    session,
                    onOpen,
                    subtitle = "Satisfied and monitored; a better release may still turn up",
                    emptyText = "No upgrade candidates.",
                )
            }
        }
        if (!session.isGuest) {
            item { FollowingRail(session, state.followed, onOpen) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Load every rail independently, so a slow or failing one never blanks the page. */
private fun loadHome(session: AppSession, state: HomeState, scope: CoroutineScope) {
    val a = session.api ?: return
    state.loadedOnce = true
    scope.launch {
        session.io { a.works() }.fold(
            onSuccess = { all ->
                val variants = one.rarebit.heyarr.core.library.Variants.variantIds(all)
                val works = all.filter { it.id !in variants }
                state.recent = RailState.Loaded(works.filter { it.kind != "document" }.take(24))
                state.spotlight =
                    RailState.Loaded(works.filter { it.kind != "document" && it.kind != "unknown" }.take(6))
            },
            onFailure = {
                state.recent = RailState.Failed(it.message ?: "failed")
                state.spotlight = RailState.Failed(it.message ?: "failed")
            },
        )
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
    loadPersonalRails(session, state, a, scope)
}

/** The personal rails: missing, upgrades and followed sources. */
private fun loadPersonalRails(session: AppSession, state: HomeState, a: HeyarrApi, scope: CoroutineScope) {
    // Missing / upgrades / following are enrolled-only, personal surfaces —
    // a guest cannot read them (they 403). Skip the calls and leave the rails empty so
    // they simply don't render; the guest sees a "Sign in to save" affordance instead.
    if (session.isGuest) {
        state.missing = RailState.Loaded(emptyList())
        state.upgrades = RailState.Loaded(emptyList())
        state.followed = RailState.Loaded(emptyList())
    } else {
        scope.launch {
            state.missing =
                session.io {
                    a.missing(40)
                }.fold(onSuccess = {
                    RailState.Loaded(it)
                }, onFailure = { RailState.Failed(it.message ?: "failed") })
        }
        scope.launch {
            state.upgrades =
                session.io {
                    a.upgradeCandidates(40)
                }.fold(onSuccess = {
                    RailState.Loaded(it)
                }, onFailure = { RailState.Failed(it.message ?: "failed") })
        }
        scope.launch {
            state.followed =
                session.io {
                    a.followed()
                }.fold(onSuccess = {
                    RailState.Loaded(it)
                }, onFailure = { RailState.Failed(it.message ?: "failed") })
        }
    }
}

/** A guest browses and plays freely; saving anything needs sign-in. */
@Composable
private fun GuestNotice() {
    Notice(
        "Browsing as a guest — watch and listen freely, no account needed.",
        detail = "Sign in to keep playlists, and want or follow things. Open Settings to sign in.",
        icon = Icons.Rounded.Info,
        tone = Tokens.slate,
    )
}

/** No node configured yet: say so, and point at Settings. */
@Composable
private fun ConnectFirst(onOpen: (Route) -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp)) {
        Notice(
            "Connect to heyarr to see your library.",
            detail = "Open Settings and paste the node URL and a bearer token.",
            icon = Icons.Rounded.Info,
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Open Settings", { onOpen(Route.Settings) }, icon = Icons.Rounded.Add)
    }
}

@Composable
private fun SpotlightBlock(
    session: AppSession,
    state: HomeState,
    onOpen: (Route) -> Unit,
    onWant: (String, String, MediaType) -> Unit,
) {
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
                    "The library is empty. Want something from Search, or scan a library root on the node.",
                    icon = Icons.Rounded.Info,
                )
                return
            }
            val type = MediaType.from(work.kind)
            val cover by rememberCover(
                session,
                type,
                work.title,
                work.artworkPath,
                work.year,
                work.artist ?: work.author,
            )
            val status = session.index.statusOf(work.id)
            Hero(
                title = work.title, type = type, kicker = "Featured from your library",
                meta = listOf(
                    work.year?.toString(),
                    work.artist ?: work.author,
                    work.kind,
                ),
                artwork = cover.bitmap, status = status,
                description = cover.external?.synopsis?.let {
                    it.take(220) +
                        (cover.external?.source?.let { s -> "  ·  via $s" } ?: "")
                },
                primary = {
                    val theme = MediaThemes.of(type)
                    PrimaryButton(theme.ctaLabel, {
                        onOpen(Route.Detail(work.id, type, work.title, from = "Home"))
                    }, icon = Icons.Rounded.PlayArrow)
                },
                secondary = {
                    if (status == LibraryStatus.NOT_TRACKED &&
                        GuestGate.allows(session.mode, Surface.WANT)
                    ) {
                        SecondaryButton("Want", { onWant(work.id, work.title, type) }, icon = Icons.Rounded.Add)
                    }
                    if (s.items.size > 1) GhostButton("Next", { i = (i + 1) % s.items.size })
                },
            )
        }
    }
}

@Composable
private fun WorkRail(
    title: String,
    state: RailState<Work>,
    session: AppSession,
    onOpen: (Route) -> Unit,
    onWant: (String, String, MediaType) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Rail(title, state, emptyText = "Nothing added yet.", trailing = trailing, key = { it.id }) { w ->
        val type = MediaType.from(w.kind)
        val status = session.index.statusOf(w.id)
        val cover by rememberCover(session, type, w.title, w.artworkPath, w.year, w.artist ?: w.author)
        MediaCard(
            w.title, type, onOpen = { onOpen(Route.Detail(w.id, type, w.title, from = "Home")) },
            subtitle =
            w.artist ?: w.author,
            meta = listOf(w.year?.toString()), artwork = cover.bitmap, status = status, onWant = {
                onWant(w.id, w.title, type)
            }, mode = session.mode,
        )
    }
}

@Composable
private fun WantRail(
    title: String,
    state: RailState<Want>,
    session: AppSession,
    onOpen: (Route) -> Unit,
    subtitle: String,
    emptyText: String,
) {
    Rail(title, state, subtitle = subtitle, emptyText = emptyText, key = { it.desiredItemId }) { w ->
        val status = LibraryStatus.ofState(w.state)
        MediaCard(
            w.title,
            MediaType.UNKNOWN,
            onOpen = {
                w.workId?.let { onOpen(Route.Detail(it, MediaType.UNKNOWN, w.title, from = "Missing")) }
            },
            subtitle = w.qualityProfile?.let {
                "profile: $it"
            },
            meta = listOf(w.state.lowercase().replace('_', ' ')),
            status = status,
            showBadge = false,
        )
    }
}
