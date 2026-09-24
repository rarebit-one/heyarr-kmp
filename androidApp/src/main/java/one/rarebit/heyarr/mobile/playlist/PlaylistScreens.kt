package one.rarebit.heyarr.mobile.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.personalstate.PersonalStateCoordinator
import one.rarebit.heyarr.mobile.theme.MediaScope
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.mobile.ui.components.EmptyState
import one.rarebit.heyarr.mobile.ui.components.Field
import one.rarebit.heyarr.mobile.ui.components.GhostButton
import one.rarebit.heyarr.mobile.ui.components.IconButtonRound
import one.rarebit.heyarr.mobile.ui.components.MediaBadge
import one.rarebit.heyarr.mobile.ui.components.MediaRowSkeleton
import one.rarebit.heyarr.mobile.ui.components.Notice
import one.rarebit.heyarr.mobile.ui.components.Panel
import one.rarebit.heyarr.mobile.ui.components.PrimaryButton
import one.rarebit.heyarr.mobile.ui.components.SecondaryButton
import one.rarebit.heyarr.mobile.ui.components.SectionHeader

/** The playlists list — device-side encrypted state, folded on this device, in the music accent. */
@Composable
internal fun PlaylistsScreen(
    state: PlaylistsViewModel.UiState,
    onOpen: (spaceId: String, name: String) -> Unit,
    onCreate: (name: String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var creating by remember { mutableStateOf(false) }
    MediaScope(MediaType.MUSIC) {
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Tokens.screenPadding, vertical = Tokens.s3), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GhostButton("Library", onBack, icon = Icons.Rounded.ArrowBack)
                    Spacer(Modifier.weight(1f))
                    if (!state.notEnrolled) PrimaryButton("New playlist", { creating = true }, icon = Icons.Rounded.Add, compact = true)
                }
            }
            item { SectionHeader("Playlists", subtitle = "Encrypted personal state, decrypted on this phone — the node never reads it") }
            when {
                state.notEnrolled -> item { Notice("Enrol this device to keep playlists — they are encrypted and only readable here.") }
                state.loading -> item { MediaRowSkeleton(3) }
                state.error != null -> item { Notice(state.error, tone = Tokens.danger) }
                state.playlists.isEmpty() -> item { EmptyState("No playlists yet", detail = "Make one here, or add from any card's long-press menu.", icon = Icons.Rounded.PlaylistPlay) }
                else -> items(state.playlists, key = { it.spaceId }) { pl ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(Tokens.radiusInput)).background(Tokens.surface1).clickable { onOpen(pl.spaceId, pl.name) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MediaBadge(MediaType.MUSIC)
                        Column(Modifier.weight(1f)) {
                            Text(pl.name, style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${pl.itemIds.size} item${if (pl.itemIds.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
                        }
                    }
                }
            }
            item { GatewaySyncFooter(state.starredSpaceId, state.historySpaceId) }
        }
    }
    if (creating) NameDialog(title = "New playlist", confirm = "Create", onConfirm = { creating = false; onCreate(it) }, onDismiss = { creating = false })
}

/** One playlist's items, resolved to browsable works. */
@Composable
internal fun PlaylistScreen(
    state: PlaylistViewModel.UiState,
    onBack: () -> Unit,
    onPlayAll: (List<Work>) -> Unit,
    onOpenWork: (Work) -> Unit,
    onRemove: (String) -> Unit,
    onRename: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var renaming by remember { mutableStateOf(false) }
    MediaScope(MediaType.MUSIC) {
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Tokens.screenPadding, vertical = Tokens.s3), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Playlists", onBack, icon = Icons.Rounded.ArrowBack)
                    Spacer(Modifier.weight(1f))
                    SecondaryButton("Rename", { renaming = true }, icon = Icons.Rounded.Edit, compact = true)
                    if (state.works.isNotEmpty()) PrimaryButton("Play all", { onPlayAll(state.works) }, icon = Icons.Rounded.PlayArrow, compact = true)
                }
            }
            item { SectionHeader(state.name.ifEmpty { "Playlist" }, subtitle = "${state.items.size} item${if (state.items.size == 1) "" else "s"}") }
            when {
                state.loading -> item { MediaRowSkeleton(3) }
                state.error != null -> item { Notice(state.error, tone = Tokens.danger) }
                state.items.isEmpty() -> item { EmptyState("This playlist is empty", detail = "Add from any card's long-press menu, or a track's ⋯ menu.", icon = Icons.Rounded.PlaylistPlay) }
                // Keyed by the stored entry id (not the work id) so per-track entries stay distinct,
                // and Remove observes exactly that entry — a track removes the track, not the album.
                else -> items(state.items, key = { it.itemId }) { row ->
                    val work = row.work
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(Tokens.radiusInput)).background(Tokens.surface1).clickable { onOpenWork(work) }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MediaBadge(MediaType.from(work.kind))
                        Column(Modifier.weight(1f)) {
                            Text(work.title, style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            listOfNotNull(work.artist ?: work.author, work.year?.toString()).joinToString("  ·  ").takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted) }
                        }
                        IconButtonRound(Icons.Rounded.Close, "Remove ${work.title} from the playlist", { onRemove(row.itemId) }, size = 36.dp)
                    }
                }
            }
        }
    }
    if (renaming) NameDialog(title = "Rename playlist", confirm = "Rename", initial = state.name, onConfirm = { renaming = false; it?.let(onRename) }, onDismiss = { renaming = false })
}

/** A dialog that picks a playlist to add an item to, or makes a new one. */
@Composable
internal fun AddToPlaylistDialog(
    playlists: List<PersonalStateCoordinator.PlaylistView>,
    onPick: (spaceId: String) -> Unit,
    onCreateNew: (name: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        NameDialog(title = "New playlist", confirm = "Create & add", onConfirm = { naming = false; onCreateNew(it) }, onDismiss = onDismiss)
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.surface2,
        confirmButton = { TextButton(onClick = { naming = true }) { Text("New playlist", color = Tokens.textPrimary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Tokens.textMuted) } },
        title = { Text("Add to playlist", color = Tokens.textPrimary) },
        text = {
            if (playlists.isEmpty()) {
                Text("No playlists yet — create one.", color = Tokens.textMuted)
            } else {
                LazyColumn {
                    items(playlists, key = { it.spaceId }) { pl ->
                        Text(pl.name, style = MaterialTheme.typography.bodyLarge, color = Tokens.textPrimary, modifier = Modifier.fillMaxWidth().clickable { onPick(pl.spaceId) }.padding(vertical = 12.dp))
                    }
                }
            }
        },
    )
}

@Composable
private fun NameDialog(title: String, confirm: String, initial: String = "", onConfirm: (String?) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tokens.surface2,
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(confirm, color = Tokens.textPrimary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Tokens.textMuted) } },
        title = { Text(title, color = Tokens.textPrimary) },
        text = { Field("Name (optional)", text) { text = it } },
    )
}

/**
 * Where the Mac gateway needs help: playlists sync to it automatically once the Mac is
 * an enrolled member (the space is wrapped for its key), but starred and history are
 * single spaces the gateway is told by id — so surface those ids here for the operator.
 * Hidden until a star / play has created the role spaces.
 */
@Composable
private fun GatewaySyncFooter(starredSpaceId: String?, historySpaceId: String?) {
    if (starredSpaceId == null && historySpaceId == null) return
    Panel("Serve on the Mac gateway") {
        Text("Playlists sync once the Mac is an enrolled member. For starred and history, run `heyarr device gateway` with:", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
        starredSpaceId?.let { Text("--starred-space=$it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Tokens.textPrimary) }
        historySpaceId?.let { Text("--history-space=$it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Tokens.textPrimary) }
    }
}
