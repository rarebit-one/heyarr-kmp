package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.ui.components.ArchiveVignette
import one.rarebit.heyarr.ui.components.EmptyState
import one.rarebit.heyarr.ui.components.SectionHeader

/**
 * Sources: the feeds and series the node follows (`list_followed`, `follow_source`, `unfollow`).
 * It used to sit inside Settings; the brief gives it its own place in the rail, because what the
 * archive listens to is ongoing work, not configuration.
 */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
fun SourcesScreen(
    session: AppSession,
    state: SettingsState,
    onSourcesChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    fun load() {
        val a = session.api ?: return
        // Followed sources are an enrolled surface; a guest cannot read them.
        if (session.isGuest) return
        scope.launch { session.io { a.followed() }.onSuccess { state.followed = it } }
    }
    LaunchedEffect(session.config) { load() }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { SectionHeader("Sources", subtitle = "Series, podcasts and feeds the node follows and archives") }
        if (session.isGuest) {
            item {
                EmptyState(
                    "Sign in to follow sources",
                    detail = "Following is enrolled-only. Add a bearer token in Settings to sign in.",
                    vignette = ArchiveVignette.SIGNAL,
                )
            }
        } else {
            item {
                FollowedPanel(session, state, {
                    load()
                    onSourcesChanged()
                })
            }
        }
    }
}
