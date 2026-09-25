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
import kotlinx.coroutines.CoroutineScope
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

// Pieces of the Watch tab ([SeasonsBlock], [EpisodeRow], [TracksBlock]) and its "Want more…"
// panel, split out so each reads as one section.

/** Held seasons plus the ones only the public calendar knows, specials last and unnumbered after them. */
internal fun allSeasons(seasons: List<Season>, ext: List<ExternalEpisode>): List<Season> {
    val extSeasons = ext.map { it.season }.distinct().filter { n -> seasons.none { it.number == n } }.sorted()
    return (seasons + extSeasons.map { Season(it, emptyList()) }).sortedWith(
        compareBy({
            it.number ==
                null
        }, { if (it.number == 0) Int.MAX_VALUE else it.number ?: 0 }),
    )
}

/** The season chips; each counts the episodes held or announced for it. */
@Composable
internal fun SeasonChips(all: List<Season>, selected: Season, ext: List<ExternalEpisode>, state: DetailState) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
}

/**
 * Wanting more of a series. One season is a genuine one-off (an edition-scope want —
 * heyarr's edition of an episodic work IS its season). Wanting the *whole* series is not
 * a one-off: per ADR-0089 a work-scoped want on a series establishes a standing follow
 * whose poll enumerates every episode, so we present it as following, and read the
 * "already following" signal the same way heyarr-desktop does — from the item-scoped
 * wants the follow projects ([seriesWantState]).
 */
@Composable
internal fun WantSeasonsPanel(
    session: AppSession,
    work: Work,
    seasons: List<Season>,
    wants: List<DesiredItem>,
    state: DetailState,
) {
    val profiles = session.profiles
    var profile by remember(profiles) {
        mutableStateOf(
            profiles.firstOrNull { it.name == session.defaultProfile }?.name ?: profiles.firstOrNull()?.name
                ?: session.defaultProfile,
        )
    }
    val tvdb = state.externalIds.firstOrNull { it.source.equals("tvdb", true) }?.value ?: work.externalIds["tvdb"]
    val followState = seriesWantState(wants)
    Panel("Want more of ${work.title}") {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Profile", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
            for (p in profiles) FilterChip(p.name, profile == p.name, { profile = p.name })
        }
        Text("Seasons the library knows", style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary)
        WantSeasonRow(session, work, seasons, wants, profile)
        Text(
            "Seasons the library has never seen have nothing to point a want at yet — they arrive through a follow.",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )
        Text("The whole series", style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary)
        FollowSeriesRow(session, work, profile, tvdb, followState.everythingCovered)
        if (!followState.everythingCovered) {
            Text(
                if (tvdb ==
                    null
                ) {
                    "Following the whole series lets the node find it by name. If it can't, add the TVDB URL in Settings → Followed sources."
                } else {
                    "Following the whole series resolves it by name; the TVDB-id button is the exact-match fallback."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.textMuted,
            )
        }
    }
}

/** One "Want Sn" button per season the library knows — an edition-scope want, measured against [profile]. */
@Composable
private fun WantSeasonRow(
    session: AppSession,
    work: Work,
    seasons: List<Season>,
    wants: List<DesiredItem>,
    profile: String,
) {
    val scope = rememberCoroutineScope()
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    if (editionId == null) return@SecondaryButton
                    scope.launch {
                        session.io { session.api.wantEdition(work.id, editionId, profile) }.onSuccess { r ->
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
                enabled =
                editionId != null && !already && profile.isNotBlank(),
            )
        }
    }
}

/** Following the whole series: by name (the one door, ADR-0089), or by TVDB id as the exact-match fallback. */
@Composable
private fun FollowSeriesRow(session: AppSession, work: Work, profile: String, tvdb: String?, following: Boolean) {
    val scope = rememberCoroutineScope()
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // ADR-0089: wanting the whole series IS following it — the node resolves the
        // series and its poll enumerates every episode as a want. One door.
        SecondaryButton(
            if (following) "Following · every episode" else "Follow the whole series",
            {
                scope.launch {
                    session.io { session.api.wantWork(work.id, profile) }.onSuccess { r ->
                        when (r) {
                            is McpResult.Ok -> {
                                session.toast(
                                    Toast.Kind.SUCCESS,
                                    "Following ${work.title}",
                                    "Every episode, past and future, becomes a want. If this node can't identify the series yet, it stays a plain want until it can.",
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
            enabled =
            !following && profile.isNotBlank(),
        )
        // The explicit TVDB-id path is a fallback for a node whose metadata provider
        // can't resolve the series by title; only offered when we know the id and
        // aren't following yet.
        if (tvdb != null && !following) {
            SecondaryButton("Follow by TVDB id", {
                followByTvdb(session, scope, work.title, tvdb, profile)
            }, icon = Icons.Rounded.Search, compact = true, enabled = profile.isNotBlank())
        }
    }
}

/** Follow the series by its TVDB id, backfilling everything; the toast names [title]. */
private fun followByTvdb(session: AppSession, scope: CoroutineScope, title: String, tvdb: String, profile: String) {
    scope.launch {
        session.io {
            session.api.follow(
                null,
                tvdb,
                null,
                profile,
                backfill = "full",
                reason = "followed from the phone",
            )
        }.onSuccess { r ->
            when (r) {
                is McpResult.Ok -> {
                    session.toast(
                        Toast.Kind.SUCCESS,
                        "Following $title",
                        "Every episode, past and future, becomes a want.",
                    )
                    session.refreshIndex()
                }

                is McpResult.Refused -> session.refused(r)
            }
        }
    }
}

/** An episode's thumbnail, with the resume bar along its bottom edge when it is the one to continue. */
@Composable
internal fun EpisodeThumb(thumb: String?, fraction: Float?) {
    val theme = LocalMediaTheme.current
    Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(Tokens.radiusCard))) {
        Artwork(thumb, MediaType.SERIES, Modifier.fillMaxSize(), glyphSize = 22.dp)
        fraction?.let { f ->
            Box(
                Modifier.align(
                    Alignment.BottomStart,
                ).fillMaxWidth().height(4.dp).background(Tokens.bgBase.copy(alpha = 0.5f)),
            ) {
                Box(Modifier.fillMaxWidth(f).height(4.dp).background(theme.accent))
            }
        }
    }
}

/** An episode's code and title over its facts; [progressLabel] is set only on the one to continue. */
@Composable
internal fun EpisodeText(
    ep: Episode,
    ext: ExternalEpisode?,
    isContinue: Boolean,
    progressLabel: String?,
    modifier: Modifier,
) {
    val theme = LocalMediaTheme.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ep.code?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = theme.accentGradientEnd) }
            Text(
                ep.title ?: ext?.name ?: ep.asset.filename ?: ep.asset.id,
                style = MaterialTheme.typography.titleSmall,
                color = if (ep.isPlayable) Tokens.textPrimary else Tokens.textDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        EpisodeFacts(ep, ext, isContinue, progressLabel)
    }
}

/** The facts line under an episode: quality tags, size, captions, air date, resume point, missing file. */
@Composable
private fun EpisodeFacts(ep: Episode, ext: ExternalEpisode?, isContinue: Boolean, progressLabel: String?) {
    val theme = LocalMediaTheme.current
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (tag in Series.qualityTags(ep.asset)) RuleCode(tag, tone = Tokens.textMuted)
        ep.asset.sizeBytes?.let {
            Text(
                WorkAsset.formatBytes(it),
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
        ext?.airdate?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Tokens.textDisabled) }
        if (isContinue) {
            Text(
                "continue · $progressLabel",
                style = MaterialTheme.typography.labelSmall,
                color = theme.accentGradientEnd,
            )
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

/** One track of an album: its number, title, size, personal actions, and Play when it is playable ([onPlay]). */
@Composable
internal fun TrackRow(number: Int, t: WorkAsset, personal: DetailPersonal, onPlay: (() -> Unit)?) {
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "%02d".format(number),
            style = MaterialTheme.typography.labelMedium,
            color = theme.accentGradientEnd,
            modifier = Modifier.width(28.dp),
        )
        Text(
            trackTitle(t),
            style = MaterialTheme.typography.titleSmall,
            color = if (t.isPlayable) Tokens.textPrimary else Tokens.textDisabled,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        t.sizeBytes?.let {
            Text(
                WorkAsset.formatBytes(it),
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.textMuted,
            )
        }
        AssetPersonalActions(personal, t.id, trackTitle(t))
        if (onPlay !=
            null
        ) {
            IconButtonRound(Icons.Rounded.PlayArrow, "Play ${trackTitle(t)}", onPlay, size = 36.dp, filled = true)
        }
    }
}
