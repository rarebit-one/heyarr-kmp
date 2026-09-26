@file:Suppress("FunctionNaming")

package one.rarebit.heyarr.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.mobile.nav.Route
import one.rarebit.heyarr.mobile.theme.HeyarrTheme
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.ui.components.DashedDivider

private data class ManageDestination(
    val route: Route,
    val title: String,
    val detail: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

/** Task-oriented mobile entry point for infrequent management destinations. */
@Composable
fun ManageScreen(onOpen: (Route) -> Unit, modifier: Modifier = Modifier) {
    val destinations = listOf(
        ManageDestination(
            Route.Missing,
            "Missing / Wanted",
            "Triage missing titles and upgrades",
            Icons.Rounded.ReportProblem,
        ),
        ManageDestination(Route.Discover, "Discover", "Ask the node's configured providers", Icons.Rounded.Explore),
        ManageDestination(
            Route.Cast,
            "Cast / renderer",
            "Choose an available playback destination",
            Icons.Rounded.Cast,
        ),
        ManageDestination(
            Route.Settings,
            "Settings & sources",
            "Connection, followed sources, and device setup",
            Icons.Rounded.Settings,
        ),
    )
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Tokens.screenPadding, vertical = Tokens.s6),
        verticalArrangement = Arrangement.spacedBy(Tokens.s3),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.s2)) {
                Text("Manage", style = MaterialTheme.typography.headlineMedium, color = Tokens.textPrimary)
                Text(
                    "Library care and node settings",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tokens.textMuted,
                )
            }
        }
        items(destinations) { destination ->
            ManageRow(destination, onOpen)
        }
    }
}

@Composable
private fun ManageRow(destination: ManageDestination, onOpen: (Route) -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button) { onOpen(destination.route) }
                .padding(vertical = Tokens.s3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.s3),
        ) {
            Icon(
                destination.icon,
                contentDescription = null,
                tint = Tokens.accentGradEnd,
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(destination.title, style = MaterialTheme.typography.titleMedium, color = Tokens.textPrimary)
                Text(destination.detail, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
            }
            Text("→", style = MaterialTheme.typography.titleMedium, color = Tokens.textMuted)
        }
        DashedDivider()
    }
}

@Preview(name = "Manage · phone", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
@Suppress("UnusedPrivateMember")
private fun ManagePhonePreview() {
    HeyarrTheme {
        Box(Modifier.fillMaxSize().background(Tokens.bgBase)) {
            ManageScreen(onOpen = {})
        }
    }
}

@Preview(name = "Manage · small phone", widthDp = 320, heightDp = 640, showBackground = true)
@Composable
@Suppress("UnusedPrivateMember")
private fun ManageSmallPhonePreview() {
    HeyarrTheme {
        Box(Modifier.fillMaxSize().background(Tokens.bgBase)) {
            ManageScreen(onOpen = {})
        }
    }
}
