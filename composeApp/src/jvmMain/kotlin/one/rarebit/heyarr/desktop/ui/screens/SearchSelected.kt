package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.core.auth.GuestGate
import one.rarebit.heyarr.core.auth.Surface
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.state.SearchRow
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.ui.components.Artwork
import one.rarebit.heyarr.desktop.ui.components.rememberCover
import one.rarebit.heyarr.ui.components.ArchiveVignette
import one.rarebit.heyarr.ui.components.DashedDivider
import one.rarebit.heyarr.ui.components.EmptyState
import one.rarebit.heyarr.ui.components.HeyarrIcons
import one.rarebit.heyarr.ui.components.MediaBadge
import one.rarebit.heyarr.ui.components.MetaLine
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.components.SectionLabel
import one.rarebit.heyarr.ui.components.StatusPill
import one.rarebit.heyarr.ui.theme.MediaScope
import one.rarebit.heyarr.ui.theme.MediaThemes
import one.rarebit.heyarr.ui.theme.Tokens

/**
 * Search's context column: the result the keyboard or a click has selected, large enough to
 * decide on without leaving the list. Everything shown comes from the hit itself and the want
 * index; Open goes to the work, Want writes desired state (hidden for a guest, as in the list).
 */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
internal fun SelectedResult(
    session: AppSession,
    selected: SearchRow?,
    top: SearchRow?,
    onOpen: (SearchRow) -> Unit,
    onWant: (String, String, MediaType) -> Unit,
) {
    // Until something is selected, the pane previews the top result rather than sitting empty.
    val row = selected ?: top
    SectionLabel(if (selected != null) "Selected result" else "Top result")
    if (row == null) {
        EmptyState(
            "Nothing selected",
            detail = "Use ↑ and ↓, or click a result, to see it here. Enter or a second click opens it.",
            vignette = ArchiveVignette.SEARCH,
        )
        return
    }
    MediaScope(row.type) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (row) {
                is SearchRow.WorkRow -> WorkSelection(session, row, onOpen, onWant)

                is SearchRow.EpisodeRow -> PlainSelection(
                    row,
                    listOfNotNull(row.hit.workTitle, row.hit.kind),
                    if (row.hit.blobHash != null) LibraryStatus.IN_LIBRARY else null,
                    onOpen,
                )

                is SearchRow.SourceRow -> PlainSelection(
                    row,
                    listOfNotNull(row.source.type?.replace('_', ' '), "${row.source.itemsArchived} archived"),
                    LibraryStatus.IN_LIBRARY,
                    onOpen,
                )
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun WorkSelection(
    session: AppSession,
    row: SearchRow.WorkRow,
    onOpen: (SearchRow) -> Unit,
    onWant: (String, String, MediaType) -> Unit,
) {
    val hit = row.hit
    val cover by rememberCover(session, row.type, hit.title, hit.artworkPath, hit.year, hit.creator)
    val status = session.index.statusOf(hit.workId)
    Artwork(
        cover.bitmap,
        row.type,
        Modifier.fillMaxWidth().aspectRatio(MediaThemes.of(row.type).aspect.ratio),
        contentDescription = "Artwork for ${hit.title}",
        seed = hit.title,
    )
    MediaBadge(row.type)
    Text(
        hit.title,
        style = MaterialTheme.typography.headlineLarge,
        color = Tokens.textPrimary,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    MetaLine(
        listOf(hit.year?.toString(), hit.creator, hit.attributes["runtime"], hit.attributes["genre"]),
        maxLines = 2,
    )
    cover.external?.synopsis?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = Tokens.textMuted,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
    }
    StatusPill(status)
    DashedDivider()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (routeFor(row) != null) {
            PrimaryButton("Open", { onOpen(row) }, icon = HeyarrIcons.Forward, contentDescription = "Open ${hit.title}")
        }
        if (status == LibraryStatus.NOT_TRACKED && GuestGate.allows(session.mode, Surface.WANT)) {
            SecondaryButton("Want", { onWant(hit.workId, hit.title, row.type) }, icon = HeyarrIcons.Add)
        }
    }
}

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun PlainSelection(row: SearchRow, meta: List<String>, status: LibraryStatus?, onOpen: (SearchRow) -> Unit) {
    MediaBadge(row.type)
    Text(
        row.title,
        style = MaterialTheme.typography.headlineLarge,
        color = Tokens.textPrimary,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    MetaLine(meta, maxLines = 2)
    status?.let { StatusPill(it) }
    DashedDivider()
    // An episode or source with no work behind it has no page to open; offer nothing rather than a dead button.
    if (routeFor(row) != null) {
        PrimaryButton("Open", { onOpen(row) }, icon = HeyarrIcons.Forward, contentDescription = "Open ${row.title}")
    }
}
