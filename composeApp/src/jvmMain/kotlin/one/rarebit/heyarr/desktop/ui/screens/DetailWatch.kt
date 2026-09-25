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
import androidx.compose.ui.graphics.RectangleShape
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

/** The synopsis: the node's when it has one, else a public source's (labelled), else an honest line. */
@Composable
internal fun SynopsisBlock(detail: WorkDetail, type: MediaType, seasons: List<Season>, state: DetailState) {
    val own = listOf("overview", "synopsis", "description", "summary").firstNotNullOfOrNull { k ->
        detail.attributes[k]?.takeIf { it.isNotBlank() }
    }
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
                    "Synopsis${if (ext.imageUrl != null && detail.artworkPath == null) " and cover" else ""} via ${ext.source} — not from your library. The node has no metadata provider (TVDB, ADR-0058).",
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
    detail: WorkDetail,
    seasons: List<Season>,
    state: DetailState,
    wants: List<DesiredItem>,
) {
    val scope = rememberCoroutineScope()
    if (state.assets == null) {
        MediaRowSkeleton(5)
        return
    }
    // The calendar: the library's seasons, plus any TVmaze knows that the library has never seen.
    val ext = state.externalEpisodes
    val extSeasons = ext.map { it.season }.distinct().filter { n -> seasons.none { it.number == n } }.sorted()
    val all: List<Season> = (seasons + extSeasons.map { Season(it, emptyList()) }).sortedWith(
        compareBy({
            it.number ==
                null
        }, { if (it.number == 0) Int.MAX_VALUE else it.number ?: 0 }),
    )
    if (all.isEmpty()) {
        Notice(
            "No episode files are held for this series yet.${if (wants.isNotEmpty()) " heyarr is looking — Curate → Releases shows what it found." else ""}",
        )
        return
    }
    val selected = all.firstOrNull { it.number == state.season } ?: seasons.firstOrNull() ?: all.first()
    val extForSeason = ext.filter { it.season == selected.number }.associateBy { it.number }
    val known = maxOf(selected.episodes.mapNotNull { it.number }.maxOrNull() ?: 0, extForSeason.keys.maxOrNull() ?: 0)
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in all) {
                FilterChip(
                    s.label,
                    s == selected,
                    {
                        state.season = s.number
                    },
                    count = maxOf(
                        s.episodes.size,
                        ext.count {
                            it.season ==
                                s.number
                        },
                    ).takeIf { it > 0 },
                )
            }
        }
        if (state.wantMenu) WantSeasonsPanel(session, detail, all, wants)
        val rows: List<Any> = buildList {
            val byNumber = selected.episodes.associateBy { it.number }
            for (n in 1..known) add(byNumber[n] ?: n)
            addAll(selected.episodes.filter { it.number.let { n -> n == null || n > known } })
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in rows) {
                when (row) {
                    is CoreEpisode<*> -> EpisodeRow(session, detail, row.desktop(), state, extForSeason[row.number])
                    is Int -> MissingEpisodeRow(session, selected, row, wants, state, extForSeason[row])
                }
            }
        }
    }
}

/**
 * Wanting more of a series, at the two scopes that are genuinely distinct (ADR-0089):
 * one held season (an edition-scope want — heyarr's edition of an episodic work IS its
 * season), or the whole series. The whole-series want is the unified door: it resolves
 * the series' metadata id and establishes a full-backfill follow, so wanting the series
 * IS following it — every aired episode and each new one becomes an item-scoped want.
 * That is how seasons the library has never seen arrive; there is no separate follow step.
 */
@Composable
private fun WantSeasonsPanel(session: AppSession, detail: WorkDetail, seasons: List<Season>, wants: List<DesiredItem>) {
    val scope = rememberCoroutineScope()
    val profiles = session.profiles
    var profile by remember(profiles) {
        mutableStateOf(profiles.firstOrNull { it.name == "living-room" }?.name ?: profiles.firstOrNull()?.name ?: "")
    }
    // Wanting a series IS following it (ADR-0089): a work-scoped want resolves the
    // series' metadata id and establishes a full-backfill follow, whose poll then
    // enumerates every episode as an item-scoped want. So "already following" is
    // read from those item wants, NOT from a work-scoped want row — that row no
    // longer lingers beside the follow (ADR-0089 §4/consequences).
    val following = wants.any { it.scope == "item" }
    Panel("Want more of ${detail.work.title}") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Profile", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
            for (p in profiles) FilterChip(p.name, profile == p.name, { profile = p.name })
        }
        Text("Seasons the library knows", style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in seasons) {
                val editionId = s.episodes.firstOrNull()?.asset?.editionId
                val already = editionId != null && wants.any { it.scope == "edition" && it.editionId == editionId }
                SecondaryButton(
                    if (already) {
                        "${s.label} · wanted"
                    } else {
                        "Want ${s.label}${s.gaps.takeIf {
                            it.isNotEmpty()
                        }?.let { " (${it.size} missing)" } ?: ""}"
                    },
                    {
                        val a = session.api ?: return@SecondaryButton
                        if (editionId == null) return@SecondaryButton
                        scope.launch {
                            session.io { a.wantEdition(detail.work.id, editionId, profile) }.onSuccess { r ->
                                when (r) {
                                    is McpResult.Ok -> {
                                        session.toast(
                                            Toast.Kind.SUCCESS,
                                            "Wanted ${s.label}",
                                            "Measured against $profile; heyarr will look for what this season is missing.",
                                        )
                                        session.refreshIndex()
                                    }

                                    is McpResult.Refused -> session.refused(r)
                                }
                            }
                        }
                    },
                    icon = Icons.Rounded.Add,
                    compact = true,
                    enabled = editionId != null && !already && profile.isNotBlank(),
                )
            }
        }
        Text(
            "Seasons the library hasn't seen yet arrive when you want the whole series below — wanting a series follows it.",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )
        Text("The whole series", style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // One door for a series (ADR-0089): want_content resolves the metadata id and
            // establishes a full-backfill follow, so this single button both wants and
            // follows. No separate "Follow on TVDB" step, and no TVDB id needed up front.
            SecondaryButton(if (following) "Following · every episode wanted" else "Want the whole series", {
                val a = session.api ?: return@SecondaryButton
                scope.launch {
                    session.io { a.wantWork(detail.work.id, profile) }.onSuccess { r ->
                        when (r) {
                            is McpResult.Ok -> {
                                session.toast(
                                    Toast.Kind.SUCCESS,
                                    "Wanting ${detail.work.title}",
                                    "Wanting a series follows it — every episode, past and future, becomes a want.",
                                )
                                session.refreshIndex()
                            }

                            is McpResult.Refused -> session.refused(r)
                        }
                    }
                }
            }, icon = Icons.Rounded.Add, compact = true, enabled = !following && profile.isNotBlank())
        }
        Text(
            "Wanting the whole series follows it: heyarr resolves its metadata id automatically (no TVDB URL needed) and backfills every aired episode plus each new one. To follow a source that has no work here, use Settings → Followed sources.",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )
    }
}

@Composable
private fun EpisodeRow(
    session: AppSession,
    detail: WorkDetail,
    ep: Episode,
    state: DetailState,
    ext: ExternalEpisode? = null,
) {
    val scope = rememberCoroutineScope()
    val theme = LocalMediaTheme.current
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
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(Tokens.radiusInput)
    val cont = state.continueEntry
    val isContinue =
        cont != null &&
            (
                if (cont.assetId !=
                    null
                ) {
                    cont.assetId == ep.asset.id
                } else {
                    cont.blobHash != null && cont.blobHash == ep.asset.blobHash
                }
                )
    Row(
        Modifier.fillMaxWidth().focusRing(interaction, shape).clip(shape)
            .background(
                if (hovered) Tokens.surface2 else Tokens.surface1,
                shape,
            ).border(Tokens.hairline, if (isContinue) theme.accent.copy(alpha = 0.6f) else Tokens.border, shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, enabled = ep.isPlayable, onClick = {
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
            })
            .semantics { contentDescription = "${ep.label}${if (!ep.isPlayable) ", file missing" else ""}" }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.width(152.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(Tokens.radiusCard))) {
            Artwork(thumb ?: extThumb, MediaType.SERIES, Modifier.fillMaxSize(), glyphSize = 22.dp)
            if (hovered &&
                ep.isPlayable
            ) {
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
            state.continueEntry?.takeIf { isContinue }?.fraction?.let { f ->
                Box(
                    Modifier.align(
                        Alignment.BottomStart,
                    ).fillMaxWidth().height(4.dp).background(Tokens.bgBase.copy(alpha = 0.5f)),
                ) {
                    Box(Modifier.fillMaxWidth(f).height(4.dp).background(theme.accent))
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                if (isContinue) {
                    Text(
                        "continue · ${state.continueEntry?.progressLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.accentGradientEnd,
                    )
                }
            }
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
        if (ep.isPlayable) {
            IconButtonRound(Icons.Rounded.Cast, "Play ${ep.label} on a renderer", {
                toggleCast(session, state, ep.asset.id, scope)
            }, size = 34.dp)
            IconButtonRound(
                Icons.Rounded.PlayArrow,
                "Play ${ep.label}",
                {
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
                },
                size = 34.dp,
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
        Box(
            Modifier.width(
                152.dp,
            ).aspectRatio(16f / 9f).clip(RoundedCornerShape(Tokens.radiusCard)).background(Tokens.surface1),
            contentAlignment = Alignment.Center,
        ) {
            if (extThumb !=
                null
            ) {
                androidx.compose.foundation.Image(
                    extThumb!!,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(0.45f),
                )
            }
            Text("not held", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(code, style = MaterialTheme.typography.labelMedium, color = Tokens.textDisabled)
                Text(ext?.name ?: "Not held", style = MaterialTheme.typography.titleMedium, color = Tokens.textDisabled)
                ext?.airdate?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Tokens.textDisabled) }
            }
            Text(
                if (wants.isEmpty()) "Want this series and heyarr will look for it." else "Wanted — heyarr searches on its schedule; ask now to jump the queue.",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.textMuted,
            )
        }
        if (wants.isNotEmpty()) {
            SecondaryButton("Look for it", {
                val a = session.api ?: return@SecondaryButton
                scope.launch {
                    session.io { a.searchReleases(wants.first().id) }.onSuccess { r ->
                        when (r) {
                            is McpResult.Ok -> session.toast(
                                Toast.Kind.INFO,
                                "Search queued for ${season.label}",
                                "Results land under Curate → Releases.",
                            )

                            is McpResult.Refused -> session.refused(r)
                        }
                    }
                }
            }, icon = Icons.Rounded.Search, compact = true)
        }
    }
}

@Composable
internal fun TracksBlock(session: AppSession, detail: WorkDetail, state: DetailState) {
    val assets = state.assets
    if (assets == null) {
        MediaRowSkeleton(5)
        return
    }
    val tracks = assets.filter {
        it.isAudio && it.isPrimaryRole
    }.sortedWith(compareBy({ it.filename?.lowercase() ?: "" }, { it.id }))
    val playable = tracks.filter { it.isPlayable }.mapNotNull { track ->
        track.blobHash?.let {
            Route.Player(
                detail.work.id,
                track.id,
                it,
                detail.work.title,
                track.title,
                MediaType.from(detail.work.kind),
                "Listen",
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionHeader("Tracks", subtitle = "${playable.size} playable", trailing = {
            if (playable.isNotEmpty()) {
                PrimaryButton("Play all", {
                    session.playback.queueAudio(playable)
                }, icon = Icons.Rounded.PlayArrow, compact = true)
            }
        })
        if (tracks.isEmpty()) Notice("No audio files held for this work yet.")
        for ((i, t) in tracks.withIndex()) {
            val theme = LocalMediaTheme.current
            Row(
                Modifier.fillMaxWidth().background(
                    Tokens.surface1,
                    RoundedCornerShape(Tokens.radiusInput),
                ).border(
                    Tokens.hairline,
                    Tokens.border,
                    RoundedCornerShape(Tokens.radiusInput),
                ).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "%02d".format(i + 1),
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.accentGradientEnd,
                    modifier = Modifier.width(28.dp),
                )
                Text(
                    t.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (t.isPlayable) Tokens.textPrimary else Tokens.textDisabled,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                t.sizeBytes?.let {
                    Text(
                        PrimaryAsset.formatBytes(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = Tokens.textMuted,
                    )
                }
                if (t.isPlayable) {
                    IconButtonRound(Icons.Rounded.PlayArrow, "Play ${t.title}", {
                        session.playback.queueAudio(
                            playable,
                            playable.indexOfFirst {
                                it.assetId ==
                                    t.id
                            },
                        )
                    }, size = 32.dp, filled = true)
                }
            }
        }
    }
}

@Composable
internal fun ArchiveBlock(session: AppSession, state: DetailState) {
    val scope = rememberCoroutineScope()
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
                            ).joinToString("  ·  "),
                            style = MaterialTheme.typography.labelSmall,
                            color = Tokens.textMuted,
                        )
                    }
                    if (item.archived && item.workId != null) {
                        SecondaryButton("Open", {
                            val a = session.api ?: return@SecondaryButton
                            val wid = item.workId ?: return@SecondaryButton
                            scope.launch {
                                val d = session.io { a.work(wid) }.getOrNull()
                                val asset = d?.primaryAsset
                                if (asset == null) {
                                    session.toast(Toast.Kind.INFO, "No archived bytes held for this item yet.")
                                } else {
                                    session.io {
                                        session.openExternally.open(
                                            session.config.baseUrl,
                                            asset.blobHash,
                                            session.config.bearerToken.trim(),
                                            null,
                                            asset.mime ?: "text/html",
                                            item.title,
                                        )
                                    }.getOrNull()?.let { session.toast(Toast.Kind.INFO, it) }
                                }
                            }
                        }, icon = Icons.Rounded.OpenInNew, compact = true)
                    }
                }
            }
        }
    }
}

/** A film / single-file work: the one file, its quality, and what plays it. */
@Composable
internal fun FileBlock(session: AppSession, detail: WorkDetail, state: DetailState) {
    val asset = detail.primaryAsset ?: return
    val file = state.assets?.firstOrNull { it.blobHash == asset.blobHash }
    Panel("This copy") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (tag in file?.let { Series.qualityTags(it) }.orEmpty()) RuleCode(tag, tone = Tokens.textMuted)
            asset.mime?.let { RuleCode(it, tone = Tokens.textMuted) }
            asset.sizeBytes?.let {
                Text(
                    PrimaryAsset.formatBytes(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = Tokens.textMuted,
                )
            }
        }
        val subs = state.assets.orEmpty().filter { Series.isSubtitle(it) }
        Text(
            if (subs.isEmpty()) {
                "No captions held."
            } else {
                "Captions: " +
                    subs.joinToString(", ") {
                        it.filename?.substringAfterLast('.', "")?.let { ext ->
                            it.filename!!.removeSuffix(".$ext").substringAfterLast('.')
                        }
                            ?: "?"
                    }
            },
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )
    }
}

// The episode rows mix episodes with missing numbers; an episode among them is always the
// desktop's own (`:core`'s `Episode` of a `Track`), which the type test cannot see.
@Suppress("UNCHECKED_CAST")
private fun CoreEpisode<*>.desktop(): Episode = this as Episode
