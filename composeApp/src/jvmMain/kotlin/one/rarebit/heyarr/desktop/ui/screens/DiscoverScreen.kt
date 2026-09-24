package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.TravelExplore
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
import kotlinx.coroutines.launch
import one.rarebit.heyarr.desktop.heyarr.McpResult
import one.rarebit.heyarr.core.heyarr.ProviderInfo
import one.rarebit.heyarr.core.mcp.DiscoveryHit
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.state.rememberArtwork
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.ui.theme.Tokens
import one.rarebit.heyarr.desktop.ui.components.MediaRow
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.SectionHeader

/** A discovery hit the user chose to want: the sheet opens by title with the year and type filled in. */
typealias WantByTitle = (title: String, year: Int?, type: MediaType) -> Unit

class DiscoverState {
    var query by mutableStateOf("")
    var result by mutableStateOf<McpResult<List<DiscoveryHit>>?>(null)
    var asked by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var providers by mutableStateOf<List<ProviderInfo>?>(null)
    var generation = -1
}

/**
 * Discover — `discover_content` against the configured node: content the library does
 * not hold, as the node's metadata provider knows it. Nothing here is assumed about the
 * node: the providers it reports are listed, and when discovery is refused the refusal
 * is quoted with its rule text. Every hit can be Wanted by title, year and type filled in.
 */
@Composable
fun DiscoverScreen(session: AppSession, state: DiscoverState, onWantTitle: WantByTitle, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(session.generation) {
        if (state.generation != session.generation) {
            state.generation = session.generation
            state.result = null; state.asked = null; state.providers = null
            val a = session.api ?: return@LaunchedEffect
            session.io { a.providers() }.onSuccess { state.providers = it }
        }
    }
    fun ask() {
        val a = session.api ?: return
        val q = state.query.trim().ifBlank { return }
        state.busy = true; state.asked = q
        scope.launch { state.result = session.io { a.discover(q) }.getOrNull(); state.busy = false }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeader("Discover", subtitle = "Ask the node's metadata provider for content the library does not hold")
        val metadata = state.providers?.filter { "metadata" in it.capabilities || "discovery" in it.capabilities }
        when {
            state.providers == null -> {}
            metadata.isNullOrEmpty() -> Text("This node reports no metadata provider — ${state.providers?.size ?: 0} provider(s), none able to discover. Discovery will answer with a refusal; Search still finds everything catalogued, and Missing lets you Want a title by name.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
            else -> Text("Metadata via " + metadata.joinToString { p -> p.name + if (p.healthy) "" else " (unhealthy)" }, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Title to look for", state.query, Modifier.width(420.dp), placeholder = "e.g. Severance") { state.query = it }
            PrimaryButton("Ask the provider", ::ask, icon = Icons.Rounded.TravelExplore, enabled = !state.busy && state.query.isNotBlank())
        }
        state.asked?.let { q -> DiscoveryResults(session, q, state.result, state.busy, onWantTitle) }
    }
}

/** The answer to one `discover_content` call: a refusal quoted verbatim, nothing, or hits that can be Wanted. */
@Composable
fun DiscoveryResults(session: AppSession, query: String, result: McpResult<List<DiscoveryHit>>?, busy: Boolean, onWantTitle: WantByTitle) {
    when (result) {
        null -> if (busy) Text("Asking the provider for “$query”…", color = Tokens.textMuted, style = MaterialTheme.typography.bodyMedium)
        else Notice("discover_content did not answer", detail = "The node could not be reached for this call. The connection state at the foot of the sidebar has the details.")
        is McpResult.Refused -> Notice("discover_content: ${result.message}", detail = "Discovery needs a metadata provider configured on the node (ADR-0058). Wanting by title still works.")
        is McpResult.Ok -> if (result.value.isEmpty()) Text("The provider found nothing for “$query”.", color = Tokens.textMuted, style = MaterialTheme.typography.bodyMedium)
        else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${result.value.size} from the provider — Want one to have the node look for it.", style = MaterialTheme.typography.labelMedium, color = Tokens.textMuted)
            for (hit in result.value) {
                // The provider names the kind (heyarr-core ADR-0099): a tv_series/podcast/
                // youtube_channel/rss_feed hit is followable and carries a tvdb_id; a
                // movie/book/music hit has no calendar and is wanted by title instead —
                // MediaType.from maps either shape onto the row's theme and onWantTitle's
                // content_type. Defaulting to SERIES here (as this screen always did) would
                // silently mis-want every non-series hit now that the provider sends them.
                val type = MediaType.from(hit.type)
                val idLine = hit.tvdbId?.let { "tvdb $it" } ?: hit.source?.let { s -> hit.externalId?.let { id -> "$s $id" } }
                // hit.posterUrl is a plain remote URL the provider hosts (TMDB/Open
                // Library/MusicBrainz), never proxied through heyarr. ArtworkLoader
                // already special-cases an absolute http(s) URL as external art — no
                // credential attached — and caches it (memory + disk) exactly like a
                // library cover, so a repeat search or a relaunch does not re-fetch.
                // backdropUrl is TMDB-only and this is a compact row, not a hero — not
                // worth a second fetch here.
                val art by session.artwork.rememberArtwork(hit.posterUrl)
                MediaRow(
                    hit.title, type, onOpen = { onWantTitle(hit.title, hit.year, type) },
                    subtitle = hit.overview, meta = listOf(hit.year?.toString(), idLine), artwork = art, status = LibraryStatus.NOT_TRACKED,
                    trailing = { PrimaryButton("Want", { onWantTitle(hit.title, hit.year, type) }, icon = Icons.Rounded.Add, compact = true, contentDescription = "Want ${hit.title}") },
                )
            }
        }
    }
}
