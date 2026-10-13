@file:Suppress("FunctionNaming")

package one.rarebit.heyarr.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.core.heyarr.ProviderInfo
import one.rarebit.heyarr.core.state.DiscoveryAnswer
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.ui.components.ArchiveLoading
import one.rarebit.heyarr.ui.components.EmptyState
import one.rarebit.heyarr.ui.components.Field
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.SectionHeader
import one.rarebit.heyarr.ui.components.TechnicalDisclosure

/**
 * Find something new: titles the node's metadata providers know that the library does not hold,
 * each with Get it. Only provider capabilities and hits the node actually returned are shown.
 */
@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod") // This route owns progressive discovery states and grouped results.
fun DiscoverScreen(
    session: AppSession,
    onWantTitle: (title: String, year: Int?, type: MediaType?) -> Unit,
    onGetItTitle: GetItByTitle,
    modifier: Modifier = Modifier,
) {
    val discovery by session.discoveryController.state.collectAsState()
    LaunchedEffect(session) { session.discoveryController.loadForGeneration(0) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Tokens.screenPadding, vertical = Tokens.s4),
        verticalArrangement = Arrangement.spacedBy(Tokens.s3),
    ) {
        item {
            SectionHeader("Find something new", subtitle = "Films, series, books and music you don't have yet")
        }
        item {
            ProviderSummary(discovery.providers, discovery.providerError)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.s2)) {
                Field(
                    "Title to look for",
                    discovery.query,
                    Modifier.fillMaxWidth(),
                    placeholder = "A title",
                    onChange = session.discoveryController::updateQuery,
                )
                PrimaryButton(
                    "Look",
                    session.discoveryController::submit,
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Rounded.TravelExplore,
                    enabled = !discovery.busy && discovery.query.isNotBlank(),
                )
            }
        }
        if (discovery.busy) {
            item { ArchiveLoading("Looking…") }
        }
        discovery.error?.let { error ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Discovery could not complete.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tokens.danger,
                    )
                    TechnicalDisclosure(error)
                    PrimaryButton(
                        "Try again",
                        session.discoveryController::submit,
                        enabled = !discovery.busy && discovery.query.isNotBlank(),
                    )
                }
            }
        }
        when (val result = discovery.result) {
            is DiscoveryAnswer.Refused -> item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Discovery refused", style = MaterialTheme.typography.titleSmall, color = Tokens.warning)
                    Text(
                        "The node declined this discovery request.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tokens.textMuted,
                    )
                    TechnicalDisclosure(
                        "${result.error.tool} (code ${result.error.code}): ${result.error.message}",
                    )
                    discovery.asked?.let { title ->
                        PrimaryButton("Want by title", { onWantTitle(title, null, null) })
                    }
                }
            }

            is DiscoveryAnswer.Hits -> {
                val hits = result.values
                if (hits.isEmpty()) {
                    item {
                        EmptyState(
                            "No provider results",
                            detail = discovery.asked?.let { "The node returned no matches for “$it”." },
                            icon = Icons.Rounded.TravelExplore,
                            action = {
                                discovery.asked?.let { title ->
                                    PrimaryButton("Want by title", { onWantTitle(title, null, null) })
                                }
                            },
                        )
                    }
                } else {
                    item {
                        Text(
                            "Get one and heyarr looks for a copy — finding it here doesn't mean a copy exists yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Tokens.textMuted,
                        )
                    }
                    val groups = hits.groupBy { MediaType.from(it.type) }
                    for ((type, group) in groups) {
                        item(key = "discover-heading:${type.name}") {
                            SectionHeader(type.plural, Modifier.padding(top = Tokens.s2))
                        }
                        items(group.size, key = { index ->
                            val hit = group[index]
                            "discover:${type.name}:${hit.externalId ?: "${hit.title}-$index"}"
                        }) { index ->
                            DiscoveryHitRow(session, group[index], onWantTitle, onGetItTitle)
                        }
                    }
                }
            }

            null -> Unit
        }
    }
}

@Composable
private fun ProviderSummary(providers: List<ProviderInfo>?, error: String?) {
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

        providers == null -> Text(
            "Checking provider capability…",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )

        metadata.isNullOrEmpty() -> Text(
            "This node reports no metadata provider.",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )

        else -> Text(
            "Metadata: " + metadata.joinToString { provider ->
                provider.name + if (provider.healthy) "" else " · unavailable"
            },
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )
    }
}
