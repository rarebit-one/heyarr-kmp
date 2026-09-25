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

/**
 * Wanting more of a series, at the two scopes that are genuinely distinct (ADR-0089):
 * one held season (an edition-scope want — heyarr's edition of an episodic work IS its
 * season), or the whole series. The whole-series want is the unified door: it resolves
 * the series' metadata id and establishes a full-backfill follow, so wanting the series
 * IS following it — every aired episode and each new one becomes an item-scoped want.
 * That is how seasons the library has never seen arrive; there is no separate follow step.
 */
@Composable
internal fun WantSeasonsPanel(
    session: AppSession,
    detail: WorkDetail,
    seasons: List<Season>,
    wants: List<DesiredItem>,
) {
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
        WantSeasonButtons(session, detail, seasons, wants, profile)
        Text(
            "Seasons the library hasn't seen yet arrive when you want the whole series below — wanting a series follows it.",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )
        Text("The whole series", style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary)
        WantSeriesButton(session, detail, following, profile)
        Text(
            "Wanting the whole series follows it: heyarr resolves its metadata id automatically (no TVDB URL needed) and backfills every aired episode plus each new one. To follow a source that has no work here, use Settings → Followed sources.",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )
    }
}

/**
 * One button per held season: an edition-scope want for that season. Always composed with the
 * panel, so its scope lives exactly as long as the panel.
 */
@Composable
private fun WantSeasonButtons(
    session: AppSession,
    detail: WorkDetail,
    seasons: List<Season>,
    wants: List<DesiredItem>,
    profile: String,
) {
    val scope = rememberCoroutineScope()
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
}

/** The one door for a whole series: want it, and so follow it. */
@Composable
private fun WantSeriesButton(session: AppSession, detail: WorkDetail, following: Boolean, profile: String) {
    val scope = rememberCoroutineScope()
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
}
