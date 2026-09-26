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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.core.heyarr.ProviderInfo
import one.rarebit.heyarr.core.mcp.DiscoveryHit
import one.rarebit.heyarr.core.state.DiscoveryAnswer
import one.rarebit.heyarr.core.state.DiscoveryState
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.state.rememberArtwork
import one.rarebit.heyarr.desktop.ui.components.MediaRow
import one.rarebit.heyarr.ui.components.ArchiveLoading
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.SectionHeader
import one.rarebit.heyarr.ui.components.TechnicalDisclosure
import one.rarebit.heyarr.ui.theme.Tokens

/** A discovery hit the user chose to want: the sheet opens by title with the year and type filled in. */
typealias WantByTitle = (title: String, year: Int?, type: MediaType) -> Unit

internal data class DiscoveryResultModel(
    val session: AppSession,
    val query: String,
    val result: DiscoveryAnswer?,
    val busy: Boolean,
    val error: String?,
)

/**
 * Discover — `discover_content` against the configured node: content the library does
 * not hold, as the node's metadata provider knows it. Nothing here is assumed about the
 * node: the providers it reports are listed, and when discovery is refused the refusal
 * is quoted with its rule text. Every hit can be Wanted by title, year and type filled in.
 */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
fun DiscoverScreen(session: AppSession, onWantTitle: WantByTitle, modifier: Modifier = Modifier) {
    val discovery by session.discoveryController.state.collectAsState()
    LaunchedEffect(session.generation) {
        session.discoveryController.loadForGeneration(session.generation)
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionHeader("Discover", subtitle = "Ask the node's metadata provider for content the library does not hold")
        DiscoveryProviderSummary(discovery.providers, discovery.providerError)
        DiscoveryQuery(
            discovery,
            onChange = session.discoveryController::updateQuery,
            onSubmit = session.discoveryController::submit,
        )
        discovery.asked?.let { query ->
            DiscoveryResults(
                DiscoveryResultModel(session, query, discovery.result, discovery.busy, discovery.error),
                onWantTitle,
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun DiscoveryProviderSummary(providers: List<ProviderInfo>?, error: String?) {
    val metadata = providers?.filter { "metadata" in it.capabilities || "discovery" in it.capabilities }
    when {
        error != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Provider capability is unknown.",
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.textMuted,
            )
            TechnicalDisclosure(error)
        }

        providers == null -> Unit

        metadata.isNullOrEmpty() -> Text(
            "This node reports no metadata provider — ${providers.size} provider(s), none able to discover. " +
                "Discovery will answer with a refusal; Search still finds everything catalogued, " +
                "and Missing lets you Want a title by name.",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )

        else -> Text(
            "Metadata via " + metadata.joinToString { provider ->
                provider.name + if (provider.healthy) "" else " (unhealthy)"
            },
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )
    }
}

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun DiscoveryQuery(state: DiscoveryState, onChange: (String) -> Unit, onSubmit: () -> Unit) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(
            "Title to look for",
            state.query,
            Modifier.width(420.dp),
            placeholder = "e.g. Severance",
            onChange = onChange,
        )
        PrimaryButton(
            "Ask the provider",
            onSubmit,
            icon = Icons.Rounded.TravelExplore,
            enabled = !state.busy && state.query.isNotBlank(),
        )
    }
}

/** The answer to one `discover_content` call: a refusal quoted verbatim, nothing, or hits that can be Wanted. */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
internal fun DiscoveryResults(model: DiscoveryResultModel, onWantTitle: WantByTitle) {
    val session = model.session
    val query = model.query
    val result = model.result
    val busy = model.busy
    val error = model.error
    when (result) {
        null -> if (busy) {
            ArchiveLoading("Asking the metadata provider…")
        } else if (error != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Notice("Discovery could not complete.", tone = Tokens.danger)
                TechnicalDisclosure(error)
            }
        } else {
            Notice(
                "discover_content did not answer",
                detail = "The node could not be reached for this call. " +
                    "The connection state at the foot of the sidebar has the details.",
            )
        }

        is DiscoveryAnswer.Refused -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Notice("Discovery refused", detail = "The node declined this discovery request.", tone = Tokens.warning)
            TechnicalDisclosure("${result.error.tool} (code ${result.error.code}): ${result.error.message}")
        }

        is DiscoveryAnswer.Hits -> DiscoverySuccess(session, query, result.values, onWantTitle)
    }
}

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun DiscoverySuccess(
    session: AppSession,
    query: String,
    hits: List<DiscoveryHit>,
    onWantTitle: WantByTitle,
) {
    if (hits.isEmpty()) {
        Text(
            "The provider found nothing for “$query”.",
            color = Tokens.textMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "${hits.size} from the provider — Want one to have the node look for it.",
            style = MaterialTheme.typography.labelMedium,
            color = Tokens.textMuted,
        )
        for (hit in hits) DiscoveryHitRow(session, hit, onWantTitle)
    }
}

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun DiscoveryHitRow(session: AppSession, hit: DiscoveryHit, onWantTitle: WantByTitle) {
    // Provider kinds map each result to the right media type and Want request payload.
    val type = MediaType.from(hit.type)
    val idLine = hit.tvdbId?.let { "tvdb $it" }
        ?: hit.source?.let { source -> hit.externalId?.let { id -> "$source $id" } }
    // The poster URL is hosted by the metadata provider and fetched as external artwork,
    // without attaching the node's credential; the artwork loader caches it for repeat searches.
    val art by session.artwork.rememberArtwork(hit.posterUrl)
    MediaRow(
        hit.title,
        type,
        onOpen = { onWantTitle(hit.title, hit.year, type) },
        subtitle = hit.overview,
        meta = listOf(hit.year?.toString(), idLine),
        artwork = art,
        status = LibraryStatus.NOT_TRACKED,
        trailing = {
            PrimaryButton(
                "Want",
                { onWantTitle(hit.title, hit.year, type) },
                icon = Icons.Rounded.Add,
                compact = true,
                contentDescription = "Want ${hit.title}",
            )
        },
    )
}
