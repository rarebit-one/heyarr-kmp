package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.heyarr.DesiredItem
import one.rarebit.heyarr.core.library.Series
import one.rarebit.heyarr.core.state.ExternalEpisode
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.heyarr.McpResult
import one.rarebit.heyarr.desktop.library.Episode
import one.rarebit.heyarr.desktop.library.PrimaryAsset
import one.rarebit.heyarr.desktop.library.Season
import one.rarebit.heyarr.desktop.library.WorkDetail
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.ui.Route
import one.rarebit.heyarr.desktop.ui.components.Artwork
import one.rarebit.heyarr.desktop.ui.components.rememberCover
import one.rarebit.heyarr.ui.components.FilterChip
import one.rarebit.heyarr.ui.components.IconButtonRound
import one.rarebit.heyarr.ui.components.MediaRowSkeleton
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.Panel
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.RuleCode
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.components.SectionHeader
import one.rarebit.heyarr.ui.components.focusRing
import one.rarebit.heyarr.ui.theme.LocalMediaTheme
import one.rarebit.heyarr.ui.theme.Tokens
import one.rarebit.heyarr.core.library.Episode as CoreEpisode

// One row per episode of the selected season (see [SeasonsBlock]): a held episode with its
// thumbnail, facts and play/cast actions, or a numbered gap the indexers can be asked about.

@Composable
internal fun EpisodeRow(
    session: AppSession,
    detail: WorkDetail,
    ep: Episode,
    state: DetailState,
    ext: ExternalEpisode? = null,
) {
    val scope = rememberCoroutineScope()
    val art = rememberEpisodeThumb(session, ep, ext)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(Tokens.radiusInput)
    val play: () -> Unit = {
        ep.asset.blobHash?.let {
            playLocal(
                session,
                state,
                it,
                Series.playTitle(detail.work, ep),
                scope,
                assetId = ep.asset.id,
                type = MediaType.SERIES,
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().focusRing(interaction, shape).clip(shape)
            .background(
                if (hovered) Tokens.surface2 else Tokens.surface1,
                shape,
            ).border(Tokens.hairline, Tokens.border, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                enabled = ep.isPlayable,
                onClick = play,
            )
            .semantics { contentDescription = "${ep.label}${if (!ep.isPlayable) ", file missing" else ""}" }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        EpisodeThumb(art, playOverlay = hovered && ep.isPlayable)
        EpisodeInfo(
            ep,
            ext,
            Modifier.weight(1f),
        )
        if (ep.isPlayable) {
            EpisodeActions(ep.label, playEnabled = state.busy == null, onCast = {
                toggleCast(session, state, ep.asset.id, scope)
            }, onPlay = play)
        }
    }
}

/** Cast to a renderer, or play here. */
@Composable
private fun EpisodeActions(label: String, playEnabled: Boolean, onCast: () -> Unit, onPlay: () -> Unit) {
    IconButtonRound(Icons.Rounded.Cast, "Play $label on a renderer", onCast, size = 34.dp)
    IconButtonRound(
        Icons.Rounded.PlayArrow,
        "Play $label",
        onPlay,
        size = 34.dp,
        filled = true,
        enabled =
        playEnabled,
    )
}

/** The scan's thumbnail for an episode, else (external metadata on) the public source's still. */
@Composable
private fun rememberEpisodeThumb(session: AppSession, ep: Episode, ext: ExternalEpisode?): ImageBitmap? {
    val thumb by rememberCover(session, MediaType.SERIES, "", ep.thumbnailPath).let { c ->
        androidx.compose.runtime.derivedStateOf { c.value.bitmap }
    }
    val extThumb by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        null,
        ext?.imageUrl,
        ep.thumbnailPath,
    ) {
        if (ep.thumbnailPath == null && ext?.imageUrl != null &&
            session.config.externalMetadata
        ) {
            value = session.artwork.load(ext.imageUrl.orEmpty())
        }
    }
    return thumb ?: extThumb
}

/** An episode's 16:9 thumbnail and a play affordance while hovered. */
@Suppress("FunctionNaming")
@Composable
private fun EpisodeThumb(art: ImageBitmap?, playOverlay: Boolean) {
    val theme = LocalMediaTheme.current
    Box(Modifier.width(152.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(Tokens.radiusCard))) {
        Artwork(art, MediaType.SERIES, Modifier.fillMaxSize(), glyphSize = 22.dp)
        if (playOverlay) {
            Box(
                Modifier.fillMaxSize().background(Tokens.bgBase.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(40.dp).background(theme.ctaGradientStart, RectangleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = theme.onAccent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

/** An episode's code, title and air date, its file facts, and the public source's summary. */
@Suppress("FunctionNaming")
@Composable
private fun EpisodeInfo(ep: Episode, ext: ExternalEpisode?, modifier: Modifier) {
    val theme = LocalMediaTheme.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ep.code?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = theme.accentGradientEnd) }
            Text(
                ep.title ?: ext?.name ?: ep.asset.filename ?: ep.asset.id,
                style = MaterialTheme.typography.titleMedium,
                color = if (ep.isPlayable) Tokens.textPrimary else Tokens.textDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ext?.airdate?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Tokens.textDisabled) }
        }
        EpisodeFileFacts(ep)
        ext?.summary?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.textMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Quality tags, size, caption count, and — when the bytes are gone — since when. */
@Composable
private fun EpisodeFileFacts(ep: Episode) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (tag in Series.qualityTags(ep.asset)) RuleCode(tag, tone = Tokens.textMuted)
        ep.asset.sizeBytes?.let {
            Text(
                PrimaryAsset.formatBytes(it),
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.textMuted,
            )
        }
        if (ep.subtitles.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    Icons.Rounded.ClosedCaption,
                    contentDescription = null,
                    tint = Tokens.textMuted,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    ep.subtitles.size.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Tokens.textMuted,
                )
            }
        }
        if (!ep.isPlayable) {
            Text(
                "file missing since ${ep.asset.missingSince?.take(10)}",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.danger,
            )
        }
    }
}

/** A numbered gap in a season: nothing held, and the one honest action — ask the indexers. */
@Composable
internal fun MissingEpisodeRow(
    session: AppSession,
    season: Season,
    number: Int,
    wants: List<DesiredItem>,
    state: DetailState,
    ext: ExternalEpisode? = null,
) {
    val scope = rememberCoroutineScope()
    val code = "S%02dE%02d".format(season.number ?: 0, number)
    val extThumb by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        null,
        ext?.imageUrl,
    ) {
        val extImage = ext?.imageUrl
        if (extImage != null && session.config.externalMetadata) value = session.artwork.load(extImage)
    }
    Row(
        Modifier.fillMaxWidth().clip(
            RoundedCornerShape(Tokens.radiusInput),
        ).border(
            Tokens.hairline,
            Tokens.border.copy(alpha = 0.6f),
            RoundedCornerShape(Tokens.radiusInput),
        ).padding(8.dp)
            .semantics { contentDescription = "$code not held" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MissingEpisodeThumb(extThumb)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(code, style = MaterialTheme.typography.labelMedium, color = Tokens.textDisabled)
                Text(ext?.name ?: "Not held", style = MaterialTheme.typography.titleMedium, color = Tokens.textDisabled)
                ext?.airdate?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Tokens.textDisabled) }
            }
            Text(
                if (wants.isEmpty()) {
                    "Want this series and heyarr will look for it."
                } else {
                    "Wanted — heyarr searches on its schedule; ask now to jump the queue."
                },
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.textMuted,
            )
        }
        if (wants.isNotEmpty()) LookForEpisodeButton(session, season, wants, scope)
    }
}

/** A gap's thumbnail: the public source's still, dimmed, under "not held". */
@Composable
private fun MissingEpisodeThumb(art: ImageBitmap?) {
    Box(
        Modifier.width(
            152.dp,
        ).aspectRatio(16f / 9f).clip(RoundedCornerShape(Tokens.radiusCard)).background(Tokens.surface1),
        contentAlignment = Alignment.Center,
    ) {
        if (art !=
            null
        ) {
            androidx.compose.foundation.Image(
                art,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(0.45f),
            )
        }
        Text("not held", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
    }
}

/** Ask the indexers now for a season's gap, rather than waiting for heyarr's schedule. */
@Composable
private fun LookForEpisodeButton(session: AppSession, season: Season, wants: List<DesiredItem>, scope: CoroutineScope) {
    SecondaryButton("Look for it", {
        val a = session.api ?: return@SecondaryButton
        scope.launch {
            session.io { a.searchReleases(wants.first().id) }.onSuccess { r ->
                when (r) {
                    is McpResult.Ok -> session.toast(
                        Toast.Kind.INFO,
                        "Search queued for ${season.label}",
                        "Results land under Manage → Releases.",
                    )

                    is McpResult.Refused -> session.refused(r)
                }
            }
        }
    }, icon = Icons.Rounded.Search, compact = true)
}
