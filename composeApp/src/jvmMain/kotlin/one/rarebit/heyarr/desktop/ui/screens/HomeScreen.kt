package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import one.rarebit.heyarr.core.state.Availability
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.heyarr.HeyarrApi
import one.rarebit.heyarr.desktop.library.Work
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.ui.ContextPane
import one.rarebit.heyarr.desktop.ui.Route
import one.rarebit.heyarr.desktop.ui.components.Hero
import one.rarebit.heyarr.desktop.ui.components.HeroSkeleton
import one.rarebit.heyarr.desktop.ui.components.MediaCard
import one.rarebit.heyarr.desktop.ui.components.Rail
import one.rarebit.heyarr.desktop.ui.components.rememberCover
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.HeyarrIcons
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
    var loadedOnce = false
}

/**
 * Home — a media-mixed spotlight over themed rails, for watching, listening and reading.
 * Spotlight and "Recently catalogued" come from the works list (recent first), holding only
 * what can be played; the per-type rails from `search_content` by content type. Wants,
 * upgrades and followed sources live in Manage, not here.
 */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
fun HomeScreen(session: AppSession, state: HomeState, onOpen: (Route) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()

    fun load() = loadHome(session, state, scope)

    LaunchedEffect(session.config) { if (!state.loadedOnce || session.api != null) load() }

    if (session.api == null) {
        ConnectFirst(onOpen, modifier)
        return
    }
    ContextPane { HomeContext(session, onOpen) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        item { SpotlightBlock(session, state, onOpen) }
        if (session.isGuest) {
            item { GuestNotice() }
        }
        item {
            WorkRail("Recently catalogued", state.recent, session, onOpen, trailing = {
                GhostButton("Refresh", ::load, icon = HeyarrIcons.Refresh)
            })
        }
        for (t in MediaType.SEARCHABLE) {
            item { TypeRail(session, t, state.byType[t] ?: RailState.Loading, onOpen) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** Load every rail independently, so a slow or failing one never blanks the page. */
private fun loadHome(session: AppSession, state: HomeState, scope: CoroutineScope) {
    val a = session.api ?: return
    state.loadedOnce = true
    scope.launch {
        session.io { a.recentWorks() }.fold(
            onSuccess = { all ->
                val variants = one.rarebit.heyarr.core.library.Variants.variantIds(all)
                // Only what can be played: a work a want created before anything arrived has no file.
                val works = all.filter { it.id !in variants && it.playable != false }
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
}

/** A guest browses and plays freely; saving anything needs sign-in. */
@Composable
private fun GuestNotice() {
    Notice(
        "Browsing as a guest — watch and listen freely, no account needed.",
        detail = "Sign in to keep playlists, and want or follow things. Open Settings to sign in.",
        icon = HeyarrIcons.Info,
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
            icon = HeyarrIcons.Info,
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Open Settings", { onOpen(Route.Settings) }, icon = HeyarrIcons.Add)
    }
}

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
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
                    "The library is empty. Want something from Search, or scan a library root on the node.",
                    icon = HeyarrIcons.Info,
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
            Hero(
                title = work.title,
                type = type,
                kicker = "From your library",
                meta = listOf(
                    work.year?.toString(),
                    work.artist ?: work.author,
                ),
                artwork = cover.bitmap,
                description = cover.external?.synopsis?.let {
                    it.take(220) +
                        (cover.external?.source?.let { s -> "  ·  via $s" } ?: "")
                },
                primary = {
                    val theme = MediaThemes.of(type)
                    PrimaryButton(theme.ctaLabel, {
                        onOpen(Route.Detail(work.id, type, work.title, from = "Home"))
                    }, icon = HeyarrIcons.Play)
                },
                secondary = {
                    if (s.items.size > 1) GhostButton("Next", { i = (i + 1) % s.items.size })
                },
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun WorkRail(
    title: String,
    state: RailState<Work>,
    session: AppSession,
    onOpen: (Route) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Rail(title, state, emptyText = "Nothing added yet.", trailing = trailing, key = { it.id }) { w ->
        val type = MediaType.from(w.kind)
        val cover by rememberCover(session, type, w.title, w.artworkPath, w.year, w.artist ?: w.author)
        MediaCard(
            w.title,
            type,
            onOpen = { onOpen(Route.Detail(w.id, type, w.title, from = "Home")) },
            subtitle = w.artist ?: w.author,
            meta = listOf(w.year?.toString()),
            artwork = cover.bitmap,
            mode = session.mode,
            availability = Availability.READY,
        )
    }
}
