package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Sync
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
import one.rarebit.heyarr.core.feeds.FollowedSource
import one.rarebit.heyarr.core.mcp.PeerStatus
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.heyarr.McpResult
import one.rarebit.heyarr.desktop.settings.DesktopConfig
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.state.Connection
import one.rarebit.heyarr.desktop.state.SyncStatus
import one.rarebit.heyarr.desktop.state.VaultPhase
import one.rarebit.heyarr.ui.components.FilterChip
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.KeyValue
import one.rarebit.heyarr.ui.components.MediaBadge
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.Panel
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.components.SectionHeader
import one.rarebit.heyarr.ui.components.Skeleton
import one.rarebit.heyarr.ui.theme.MediaScope
import one.rarebit.heyarr.ui.theme.MediaThemes
import one.rarebit.heyarr.ui.theme.Tokens

/** Followed sources (`list_followed`, `follow_source`, `unfollow`): what the node polls, and a form to follow more. */
@Composable
internal fun FollowedPanel(session: AppSession, state: SettingsState, reload: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var tvdb by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var profile by remember(session.profiles) { mutableStateOf(session.profiles.firstOrNull()?.name ?: "") }
    var backfill by remember { mutableStateOf("from_now") }
    Panel("Followed sources", trailing = { GhostButton("Refresh", reload) }) {
        FollowedList(session, state.followed, reload)
        FollowHeading()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Feed / TVDB URL", url, Modifier.weight(2f), placeholder = "https://…/rss") { url = it }
            Field("TVDB id", tvdb, Modifier.weight(1f)) { tvdb = it.filter { c -> c.isDigit() } }
        }
        Field("Title (only for content the library has never seen)", title) { title = it }
        FollowChoices(
            session.profiles.map { it.name },
            profile,
            { profile = it },
            backfill,
            { backfill = it },
        )
        PrimaryButton(
            "Follow",
            {
                val a = session.api ?: return@PrimaryButton
                scope.launch {
                    state.busy = true
                    session.io { a.follow(url, tvdb, title, profile, backfill) }.onSuccess { r ->
                        when (r) {
                            is McpResult.Ok -> {
                                session.toast(
                                    Toast.Kind.SUCCESS,
                                    "Following",
                                    r.value?.title ?: url.ifBlank { tvdb },
                                )
                                url = ""
                                tvdb = ""
                                title = ""
                                reload()
                            }

                            is McpResult.Refused -> session.refused(r)
                        }
                    }
                    state.busy = false
                }
            },
            icon = Icons.Rounded.Add,
            compact = true,
            enabled =
            !state.busy && (url.isNotBlank() || tvdb.isNotBlank()) && profile.isNotBlank(),
        )
    }
}

/** "Follow something new", and how a URL is read: a TVDB id or URL is a series, any other feed a podcast. */
@Composable
private fun FollowHeading() {
    Text(
        "Follow something new",
        style = MaterialTheme.typography.titleSmall,
        color = Tokens.textPrimary,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        "A TVDB id or URL is a series; any other http(s) feed URL is a podcast (or an article feed).",
        style = MaterialTheme.typography.bodySmall,
        color = Tokens.textMuted,
    )
}

/** What the node follows, one row each with Unfollow; a skeleton until the list loads. */
@Composable
private fun FollowedList(session: AppSession, list: List<FollowedSource>?, reload: () -> Unit) {
    val scope = rememberCoroutineScope()
    when (list) {
        null -> Skeleton(Modifier.fillMaxWidth().height(40.dp))

        else -> if (list.isEmpty()) {
            Text("Nothing followed yet.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
        } else {
            for (s in list) {
                FollowedRow(s) { unfollow(session, s, reload, scope) }
            }
        }
    }
}

@Composable
private fun FollowedRow(s: FollowedSource, onUnfollow: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MediaBadge(MediaType.from(s.type))
        Column(Modifier.weight(1f)) {
            Text(s.title, style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary)
            Text(
                listOfNotNull(
                    s.feedRef,
                    "${s.itemsArchived}/${s.itemsKnown} archived",
                    s.health?.let {
                        "health $it"
                    },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.textMuted,
                maxLines = 1,
            )
        }
        SecondaryButton("Unfollow", onUnfollow, compact = true, danger = true)
    }
}

/** Unfollow a source; what it archived stays. */
private fun unfollow(session: AppSession, s: FollowedSource, reload: () -> Unit, scope: CoroutineScope) {
    val a = session.api ?: return
    scope.launch {
        session.io { a.unfollow(s.id) }.onSuccess { r ->
            when (r) {
                is McpResult.Ok -> {
                    session.toast(
                        Toast.Kind.SUCCESS,
                        "Unfollowed ${s.title}",
                        "Archived items are kept.",
                    )
                    reload()
                }

                is McpResult.Refused -> session.refused(r)
            }
        }
    }
}

/** The quality profile a new follow is measured against, and how far back it fills. */
@Composable
private fun FollowChoices(
    profiles: List<String>,
    profile: String,
    onProfile: (String) -> Unit,
    backfill: String,
    onBackfill: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Profile", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
        for (name in profiles) FilterChip(name, profile == name, { onProfile(name) })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Backfill", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
        FilterChip("from now", backfill == "from_now", { onBackfill("from_now") })
        FilterChip("full back-catalogue", backfill == "full", { onBackfill("full") })
    }
}
