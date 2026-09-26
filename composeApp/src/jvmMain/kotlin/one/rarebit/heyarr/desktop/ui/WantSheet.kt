package one.rarebit.heyarr.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.discovery.MdnsResolver
import one.rarebit.heyarr.core.discovery.NoMdnsResolver
import one.rarebit.heyarr.core.heyarr.QualityProfile
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.heyarr.HeyarrApi
import one.rarebit.heyarr.desktop.heyarr.McpResult
import one.rarebit.heyarr.desktop.heyarr.PlaybackTarget
import one.rarebit.heyarr.desktop.open.BlobDownloader
import one.rarebit.heyarr.desktop.open.ExternalOpener
import one.rarebit.heyarr.desktop.open.JdkBlobDownloader
import one.rarebit.heyarr.desktop.open.OpenExternally
import one.rarebit.heyarr.desktop.open.XdgOpen
import one.rarebit.heyarr.desktop.playback.MpvPlayer
import one.rarebit.heyarr.desktop.playback.Player
import one.rarebit.heyarr.desktop.settings.SettingsStore
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.state.ArtworkLoader
import one.rarebit.heyarr.desktop.state.Connection
import one.rarebit.heyarr.desktop.state.SearchController
import one.rarebit.heyarr.desktop.ui.components.AudioDock
import one.rarebit.heyarr.desktop.ui.components.ModalScrim
import one.rarebit.heyarr.desktop.ui.components.NowPlayingBar
import one.rarebit.heyarr.desktop.ui.components.PlaybackHost
import one.rarebit.heyarr.desktop.ui.components.SideNav
import one.rarebit.heyarr.desktop.ui.screens.DetailScreen
import one.rarebit.heyarr.desktop.ui.screens.DetailState
import one.rarebit.heyarr.desktop.ui.screens.Field
import one.rarebit.heyarr.desktop.ui.screens.HomeScreen
import one.rarebit.heyarr.desktop.ui.screens.HomeState
import one.rarebit.heyarr.desktop.ui.screens.LibraryScreen
import one.rarebit.heyarr.desktop.ui.screens.LibraryState
import one.rarebit.heyarr.desktop.ui.screens.MissingScreen
import one.rarebit.heyarr.desktop.ui.screens.MissingState
import one.rarebit.heyarr.desktop.ui.screens.NowPlayingScreen
import one.rarebit.heyarr.desktop.ui.screens.NowPlayingState
import one.rarebit.heyarr.desktop.ui.screens.PlayerKeys
import one.rarebit.heyarr.desktop.ui.screens.PlayerScreen
import one.rarebit.heyarr.desktop.ui.screens.PlayerScreenState
import one.rarebit.heyarr.desktop.ui.screens.ReaderScreen
import one.rarebit.heyarr.desktop.ui.screens.SearchScreen
import one.rarebit.heyarr.desktop.ui.screens.SettingsScreen
import one.rarebit.heyarr.desktop.ui.screens.SettingsState
import one.rarebit.heyarr.desktop.ui.screens.WantByTitle
import one.rarebit.heyarr.desktop.ui.screens.accentHex
import one.rarebit.heyarr.ui.components.FilterChip
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.OfflineBanner
import one.rarebit.heyarr.ui.components.Panel
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.ToastCard
import one.rarebit.heyarr.ui.theme.HeyarrTheme
import one.rarebit.heyarr.ui.theme.LocalAppearance
import one.rarebit.heyarr.ui.theme.MediaThemes
import one.rarebit.heyarr.ui.theme.Tokens

/**
 * The profiles worth OFFERING for [type]: every profile whose content_types either
 * names [type] or is empty (heyarr-core: unrestricted — every profile that predates
 * the field, and any an operator deliberately leaves general). This replaced a
 * client-side name-guess ("ebook" must mean books) with the node's own answer, so a
 * node with different profile names still filters correctly and a book want is never
 * offered a video-only profile like "living-room" whose accept gate it can never pass.
 */
private fun profilesFor(type: MediaType, profiles: List<QualityProfile>): List<QualityProfile> {
    val api = type.apiName
    return profiles.filter { it.contentTypes.isEmpty() || (api != null && api in it.contentTypes) }
}

/**
 * The quality profile the Want sheet pre-selects for [type], from what [profilesFor]
 * offers. "everyday" is preferred when it's in that set (the common video case);
 * otherwise the first offered profile, so the picker is never blank on a node with an
 * unconventional profile set.
 */
private fun defaultProfileFor(type: MediaType, profiles: List<QualityProfile>): String {
    val candidates = profilesFor(type, profiles)
    return candidates.firstOrNull { it.name == "everyday" }?.name
        ?: candidates.firstOrNull()?.name ?: ""
}

/**
 * The Want sheet: pick a quality profile (required — "this should exist" with no
 * standard cannot be evaluated), optionally a note, and go. Work-by-id when opened from
 * a card; title + type when opened from Missing for something the library has never seen.
 */
@Composable
internal fun WantSheet(session: AppSession, req: WantRequest, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(req.title) }
    var year by remember { mutableStateOf(req.year?.toString() ?: "") }
    var type by remember { mutableStateOf(req.type) }
    // "everyday" is a VIDEO profile (resolution/codec gates) — the right default for a
    // movie or series, but silently wrong for a book or an album (heyarr-core ADR-0082:
    // "inventing an accept-anything profile... would claim a judgement the system cannot
    // make", so book/music have no default of their own server-side; this is the one place
    // client-side that must not paper over that with a video profile's default). Keyed on
    // `type`, not just session.profiles, so picking a different chip in the by-title flow
    // re-picks the right default rather than leaving a stale video profile selected.
    var profile by remember(session.profiles, type) { mutableStateOf(defaultProfileFor(type, session.profiles)) }
    var monitor by remember { mutableStateOf(true) }
    var reason by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val byTitle = req.workId == null
    ModalScrim(onClose, Modifier.width(520.dp)) {
        Panel(if (byTitle) "Want by title" else "Want “${req.title}”", trailing = {
            GhostButton("Close", onClose)
        }) {
            if (byTitle) {
                TitleAndYear(title, { title = it }, year, { year = it })
                MediaTypeChoice(type) { type = it }
            }
            ProfileChoice(type, session.profiles, profile) { profile = it }
            WantOptions(monitor, { monitor = !monitor }, reason, { reason = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    "Want",
                    {
                        val a = session.api ?: return@PrimaryButton
                        if (!byTitle) {
                            session.wantWork(req, profile, monitor, reason, onClose)
                            return@PrimaryButton
                        }
                        busy = true
                        scope.launch {
                            session.io {
                                a.wantTitle(
                                    title.trim(),
                                    type,
                                    profile,
                                    year.toIntOrNull(),
                                    monitor,
                                    reason.ifBlank {
                                        null
                                    },
                                )
                            }.onSuccess { r -> onTitleWanted(session, r, title.trim(), profile, onClose) }
                            busy = false
                        }
                    },
                    icon = Icons.Rounded.Add,
                    enabled =
                    !busy && profile.isNotBlank() && (!byTitle || title.isNotBlank()),
                )
                GhostButton("Cancel", onClose)
            }
        }
    }
}

/** A want on a work the library already has, by id; the session reports the outcome. */
private fun AppSession.wantWork(
    req: WantRequest,
    profile: String,
    monitor: Boolean,
    reason: String,
    onClose: () -> Unit,
) {
    want(
        req.workId!!,
        req.title,
        profile,
        monitor,
        reason.ifBlank {
            null
        },
    ) { onClose() }
}

/** Keep looking for upgrades, and a note for whoever reads this in six months. */
@Composable
private fun WantOptions(monitor: Boolean, onToggleMonitor: () -> Unit, reason: String, onReason: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip("Keep looking for something better", monitor, onToggleMonitor)
    }
    Field("Reason (a note for whoever reads this in six months)", reason) { onReason(it) }
}

/** By title: the title as the library will normalise it, and an optional year. */
@Composable
private fun TitleAndYear(title: String, onTitle: (String) -> Unit, year: String, onYear: (String) -> Unit) {
    Field("Title", title) { onTitle(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field("Year (optional)", year, Modifier.width(140.dp)) {
            onYear(it.filter { c -> c.isDigit() }.take(4))
        }
    }
}

/** By title: which kind of work this is, which decides the profiles on offer. */
@Composable
private fun MediaTypeChoice(type: MediaType, onType: (MediaType) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (t in MediaType.SEARCHABLE) {
            FilterChip(
                t.label,
                type == t,
                { onType(t) },
            )
        }
    }
    Text(
        "Created from the title with the same normalisation a scan uses, so wanting it now and scanning it later converge on one work.",
        style = MaterialTheme.typography.bodySmall,
        color = Tokens.textMuted,
    )
}

/** The quality profile this want is measured against — asked only when there is a real choice. */
@Composable
private fun ProfileChoice(
    type: MediaType,
    profiles: List<QualityProfile>,
    profile: String,
    onProfile: (String) -> Unit,
) {
    val offered = profilesFor(type, profiles)
    when {
        // Nothing to choose: either the node has no profiles yet, or exactly
        // one is offered for this type (ADR-0082's book/music case — there is
        // no real quality axis to pick a standard against, so asking is
        // friction with no decision behind it). The picker only earns its
        // place when there is an actual choice.
        profiles.isEmpty() -> Text(
            "No profiles loaded yet.",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )

        offered.isEmpty() -> Text(
            "No quality profile is configured for ${type.label.lowercase()} yet — add one with `heyarr quality-profile create` and tag it --content-types ${type.apiName}.",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )

        offered.size == 1 -> Text(
            "Measured against ${offered[0].name}" + (
                offered[0].description?.let {
                    " — $it"
                } ?: ""
                ) + ".",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )

        else -> {
            Text(
                "Quality profile — the standard this want is measured against",
                style = MaterialTheme.typography.labelMedium,
                color = Tokens.textMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in offered) {
                    FilterChip(
                        p.name,
                        profile == p.name,
                        { onProfile(p.name) },
                    )
                }
            }
            offered.firstOrNull {
                it.name == profile
            }?.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
            }
        }
    }
}

/** What the node said to a want by title: toast and close on success, or show the refusal verbatim. */
private fun onTitleWanted(session: AppSession, r: McpResult<*>, title: String, profile: String, onClose: () -> Unit) {
    when (r) {
        is McpResult.Ok -> {
            session.toast(
                Toast.Kind.SUCCESS,
                "Wanted “$title”",
                "Measured against the $profile profile.",
            )
            session.refreshIndex()
            onClose()
        }

        is McpResult.Refused -> session.refused(r)
    }
}
