package one.rarebit.heyarr.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MenuBook
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.heyarr.DesiredItem
import one.rarebit.heyarr.core.library.Series
import one.rarebit.heyarr.core.state.ExternalEpisode
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.heyarr.HeyarrApi
import one.rarebit.heyarr.mobile.heyarr.McpResult
import one.rarebit.heyarr.mobile.heyarr.seriesWantState
import one.rarebit.heyarr.mobile.library.Episode
import one.rarebit.heyarr.mobile.library.Season
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.mobile.music.Tracks
import one.rarebit.heyarr.mobile.music.trackTitle
import one.rarebit.heyarr.mobile.nav.Route
import one.rarebit.heyarr.mobile.nav.detailRoute
import one.rarebit.heyarr.mobile.reader.ReaderFormat
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.mobile.ui.components.Artwork
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
import one.rarebit.heyarr.core.library.Episode as CoreEpisode

/** The synopsis: the node's when it has one, else a public source's (labelled), else an honest line. */
@Composable
internal fun SynopsisBlock(work: Work, state: DetailState, coverIsExternal: Boolean) {
    val own = work.synopsis
    val ext = state.external
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            own != null -> Text(own, style = MaterialTheme.typography.bodyLarge, color = Tokens.textPrimary)

            ext?.synopsis != null -> {
                Text(
                    ext.synopsis.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tokens.textPrimary,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Synopsis${if (coverIsExternal) " and cover" else ""} via ${ext.source} — not from your library. The node has no metadata provider (TVDB, ADR-0058).",
                    style = MaterialTheme.typography.labelSmall,
                    color = Tokens.textDisabled,
                )
            }

            else -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Rounded.Info,
                    contentDescription = null,
                    tint = Tokens.textDisabled,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    "No synopsis — the node has no metadata provider and no public source knew this title. Titles, seasons and episodes below come from the files themselves.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.textMuted,
                )
            }
        }
    }
}

/** Seasons as chips, then the selected season's episodes with the thumbnails the scan already recorded. */
@Composable
internal fun SeasonsBlock(
    session: AppSession,
    work: Work,
    seasons: List<Season>,
    state: DetailState,
    wants: List<DesiredItem>,
    play: DetailPlayback,
    art: String?,
) {
    if (state.assets == null) {
        MediaRowSkeleton(5)
        return
    }
    val ext = state.externalEpisodes
    val all: List<Season> = allSeasons(seasons, ext)
    if (all.isEmpty()) {
        Notice(
            "No episode files are held for this series yet.${if (wants.isNotEmpty()) " heyarr is looking — Curate → Indexer candidates shows what it found." else ""}",
        )
        return
    }
    val selected = all.firstOrNull { it.number == state.season } ?: seasons.firstOrNull() ?: all.first()
    val extForSeason = ext.filter { it.season == selected.number }.associateBy { it.number }
    val known = maxOf(selected.episodes.mapNotNull { it.number }.maxOrNull() ?: 0, extForSeason.keys.maxOrNull() ?: 0)
    val queue = queueOf(work, seasons, session)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader(
            "Episodes",
            subtitle =
            "${selected.held} of $known held" + (if (ext.isNotEmpty()) "  ·  calendar via TVmaze" else ""),
            trailing = {
                SecondaryButton(if (state.wantMenu) "Close" else "Want more…", {
                    state.wantMenu = !state.wantMenu
                }, icon = Icons.Rounded.Add, compact = true)
            },
        )
        SeasonChips(all, selected, ext, state)
        if (state.wantMenu) WantSeasonsPanel(session, work, all, wants, state)
        val rows: List<Any> = buildList {
            val byNumber = selected.episodes.associateBy { it.number }
            for (n in 1..known) add(byNumber[n] ?: n)
            addAll(selected.episodes.filter { it.number.let { n -> n == null || n > known } })
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in rows) {
                when (row) {
                    is CoreEpisode<*> -> EpisodeRow(session, work, row.phone(), state, extForSeason[row.number], onPlay = { ep ->
                        play.playVideo(
                            work, ep.asset.id, ep.asset.blobHash!!,
                            ep.asset.mime ?: work.mime, Series.playTitle(work, ep), null, queue, art, ep.subtitles,
                        )
                    })

                    is Int -> MissingEpisodeRow(session, selected, row, wants, extForSeason[row])
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    session: AppSession,
    work: Work,
    ep: Episode,
    state: DetailState,
    ext: ExternalEpisode?,
    onPlay: (Episode) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val theme = LocalMediaTheme.current
    val thumb =
        ep.thumbnailPath?.let { HeyarrApi.blobUrlFromPath(session.baseUrl, it) }
            ?: ext?.imageUrl?.takeIf { session.externalMetadata }
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Tokens.radiusInput)
    val cont = state.continueEntry
    val isContinue =
        cont != null && (cont.assetId == ep.asset.id || (cont.blobHash != null && cont.blobHash == ep.asset.blobHash))
    Row(
        Modifier.fillMaxWidth().focusRing(interaction, shape).clip(shape)
            .background(
                Tokens.surface1,
                shape,
            ).border(Tokens.hairline, if (isContinue) theme.accent.copy(alpha = 0.6f) else Tokens.border, shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, enabled = ep.isPlayable, onClick = {
                onPlay(ep)
            })
            .semantics { contentDescription = "${ep.label}${if (!ep.isPlayable) ", file missing" else ""}" }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EpisodeThumb(thumb, state.continueEntry?.takeIf { isContinue }?.fraction)
        EpisodeText(ep, ext, isContinue, state.continueEntry?.progressLabel, Modifier.weight(1f))
        if (ep.isPlayable) {
            IconButtonRound(Icons.Rounded.Cast, "Play ${ep.label} on a renderer", {
                toggleCast(session, state, ep.asset.id, scope)
            }, size = 36.dp)
            IconButtonRound(
                Icons.Rounded.PlayArrow,
                "Play ${ep.label}",
                {
                    onPlay(ep)
                },
                size = 40.dp,
                filled = true,
                enabled =
                state.busy == null,
            )
        }
    }
}

/** A numbered gap in a season: nothing held, and the one honest action — ask the indexers. */
@Composable
private fun MissingEpisodeRow(
    session: AppSession,
    season: Season,
    number: Int,
    wants: List<DesiredItem>,
    ext: ExternalEpisode?,
) {
    val scope = rememberCoroutineScope()
    val code = "S%02dE%02d".format(season.number ?: 0, number)
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.width(
                112.dp,
            ).aspectRatio(16f / 9f).clip(RoundedCornerShape(Tokens.radiusCard)).background(Tokens.surface1),
            contentAlignment = Alignment.Center,
        ) {
            Text("not held", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(code, style = MaterialTheme.typography.labelMedium, color = Tokens.textDisabled)
                Text(
                    ext?.name ?: "Not held",
                    style = MaterialTheme.typography.titleSmall,
                    color = Tokens.textDisabled,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (wants.isEmpty()) "Want this series and heyarr will look for it." else "Wanted — heyarr searches on its schedule; ask now to jump the queue.",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.textMuted,
            )
        }
        if (wants.isNotEmpty()) {
            IconButtonRound(Icons.Rounded.Search, "Look for $code now", {
                scope.launch {
                    session.io { session.api.searchReleases(wants.first().id) }.onSuccess { r ->
                        when (r) {
                            is McpResult.Ok -> session.toast(
                                Toast.Kind.INFO,
                                "Search queued for ${season.label}",
                                "Results land under Curate → Indexer candidates.",
                            )

                            is McpResult.Refused -> session.refused(r)
                        }
                    }
                }
            }, size = 36.dp)
        }
    }
}

@Composable
internal fun TracksBlock(
    session: AppSession,
    work: Work,
    state: DetailState,
    play: DetailPlayback,
    personal: DetailPersonal,
) {
    val assets = state.assets
    if (assets == null) {
        MediaRowSkeleton(5)
        return
    }
    val tracks = Tracks.all(assets)
    val playable = Tracks.playable(assets)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader("Tracks", subtitle = "${playable.size} playable", trailing = {
            if (playable.isNotEmpty()) {
                PrimaryButton("Play all", {
                    play.playAudio(work, playable, 0)
                }, icon = Icons.Rounded.PlayArrow, compact = true)
            }
        })
        if (tracks.isEmpty()) Notice("No audio files held for this work yet.")
        for ((i, t) in tracks.withIndex()) {
            val idx = playable.indexOfFirst { it.id == t.id }
            TrackRow(i + 1, t, personal, if (idx >= 0) ({ play.playAudio(work, playable, idx) }) else null)
        }
    }
}

@Composable
internal fun ArchiveBlock(state: DetailState, onOpen: (Route) -> Unit) {
    val items = state.feedItems
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader("Archive", subtitle = items?.let { "${it.count { i -> i.archived }} of ${it.size} archived" })
        when {
            items == null -> MediaRowSkeleton(4)

            items.isEmpty() -> Notice("Nothing archived yet — the node polls this source on its schedule.")

            else -> for (item in items) {
                Row(
                    Modifier.fillMaxWidth().background(
                        Tokens.surface1,
                        RoundedCornerShape(Tokens.radiusInput),
                    ).border(Tokens.hairline, Tokens.border, RoundedCornerShape(Tokens.radiusInput)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (item.archived) Tokens.textPrimary else Tokens.textDisabled,
                        )
                        Text(
                            listOfNotNull(
                                item.publishedAt?.take(10),
                                if (item.archived) "archived" else "not archived yet",
                                item.want?.summary?.takeIf {
                                    it.isNotBlank()
                                },
                            ).joinToString("  ·  "),
                            style = MaterialTheme.typography.labelSmall,
                            color = Tokens.textMuted,
                        )
                    }
                    val wid = item.workId
                    if (item.archived &&
                        wid != null
                    ) {
                        SecondaryButton("Open", {
                            onOpen(detailRoute(wid, MediaType.UNKNOWN, item.title, from = "Archive"))
                        }, icon = Icons.Rounded.OpenInNew, compact = true)
                    }
                }
            }
        }
    }
}

/** A film / single-file work: the one file, its quality, and what plays it. */
@Composable
internal fun FileBlock(work: Work, state: DetailState) {
    val hash = work.blobHash ?: return
    val file = state.assets?.firstOrNull { it.blobHash == hash }
    Panel("This copy") {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (tag in file?.let { Series.qualityTags(it) }.orEmpty()) RuleCode(tag, tone = Tokens.textMuted)
            work.mime?.let { RuleCode(it, tone = Tokens.textMuted) }
            file?.sizeBytes?.let {
                Text(WorkAsset.formatBytes(it), style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
            }
        }
        val subs = state.assets.orEmpty().filter { Series.isSubtitle(it) }
        Text(
            if (subs.isEmpty()) {
                "No caption files held — captions inside the container still show in the player's CC menu."
            } else {
                "Caption files: " +
                    subs.joinToString(", ") { it.filename?.substringBeforeLast('.')?.substringAfterLast('.') ?: "?" }
            },
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )
    }
}

/** A book: each readable file with its format — Read opens the reader, an audiobook file listens through the queue. */
@Composable
internal fun BookFilesBlock(work: Work, state: DetailState, play: DetailPlayback, personal: DetailPersonal) {
    val assets = state.assets
    if (assets == null) {
        MediaRowSkeleton(3)
        return
    }
    val readable = assets.filter { it.isPlayable }.map { it to ReaderFormat.of(it.mime, it.filename) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader("Files", subtitle = "${readable.size} held")
        if (readable.isEmpty()) Notice("No readable file in the catalog yet.")
        for ((asset, format) in readable) {
            Row(
                Modifier.fillMaxWidth().background(
                    Tokens.surface1,
                    RoundedCornerShape(Tokens.radiusInput),
                ).border(Tokens.hairline, Tokens.border, RoundedCornerShape(Tokens.radiusInput)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        asset.filename ?: asset.id,
                        style = MaterialTheme.typography.titleSmall,
                        color = Tokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            format?.label,
                            asset.sizeBytes?.let {
                                WorkAsset.formatBytes(it)
                            },
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = Tokens.textMuted,
                    )
                }
                AssetPersonalActions(personal, asset.id, asset.filename ?: asset.id)
                when (format) {
                    ReaderFormat.AUDIOBOOK -> PrimaryButton("Listen", {
                        play.playAudio(work, listOf(asset), 0)
                    }, icon = Icons.Rounded.Headphones, compact = true)

                    null -> SecondaryButton("Unsupported", {}, enabled = false, compact = true)

                    else -> PrimaryButton("Read", {
                        play.read(work, asset)
                    }, icon = Icons.Rounded.MenuBook, compact = true)
                }
            }
        }
    }
}

// The episode rows mix episodes with missing numbers; an episode among them is always the
// phone's own (`:core`'s `Episode` of a `WorkAsset`), which the type test cannot see.
@Suppress("UNCHECKED_CAST")
internal fun CoreEpisode<*>.phone(): Episode = this as Episode
