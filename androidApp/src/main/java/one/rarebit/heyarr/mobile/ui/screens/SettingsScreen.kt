package one.rarebit.heyarr.mobile.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.heyarr.QualityProfile
import one.rarebit.heyarr.core.mcp.PeerStatus
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.HeyarrConfig
import one.rarebit.heyarr.mobile.heyarr.McpResult
import one.rarebit.heyarr.mobile.search.FollowedSource
import one.rarebit.heyarr.mobile.search.SessionAuthority
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.mobile.state.Connection
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.ui.components.Field
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

class SettingsState {
    var followed by mutableStateOf<List<FollowedSource>?>(null)
    var peers by mutableStateOf<PeerStatus?>(null)
    var busy by mutableStateOf(false)
}

/**
 * Settings — the heyarr connection (node URL + default quality profile; a changed node
 * signs out), the connection telemetry sheet, this device's enrolment, followed
 * sources (`list_followed`, `follow_source`, `unfollow`), peers (`get_peer_status`,
 * `sync_peer`) and appearance (adaptive accents, reduce motion, public cover art).
 */
@Composable
fun SettingsScreen(
    session: AppSession,
    state: SettingsState,
    config: HeyarrConfig,
    authority: SessionAuthority?,
    onSaveConnection: (baseUrl: String, qualityProfile: String) -> Unit,
    onResetConnection: () -> Unit,
    onSignOut: () -> Unit,
    onTelemetry: () -> Unit,
    onDevice: () -> Unit,
    onSourcesChanged: () -> Unit,
    modifier: Modifier = Modifier,
    deviceSummary: String? = null,
    /** True while browsing anonymously — hides enrolled-only panels, shows "Sign in to save". */
    isGuest: Boolean = false,
    onSignInToSave: () -> Unit = {},
    /** Run the mDNS → DNS discovery chain and save the node it finds. */
    onDiscover: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    fun load() {
        scope.launch { session.io { session.api.followed() }.onSuccess { state.followed = it } }
        scope.launch { session.io { session.api.peers() }.onSuccess { state.peers = it } }
    }
    // Followed sources and peers are enrolled-only reads; a guest never loads them.
    LaunchedEffect(Unit) { if (!isGuest && state.followed == null) load() }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Tokens.screenPadding, vertical = Tokens.s4),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { SectionHeader("Settings") }
        item {
            Panel("heyarr connection") {
                ConnectionFields(config, onSaveConnection, onResetConnection)
                ConnectionStatus(session, authority, isGuest)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SecondaryButton("Discover on network", onDiscover, compact = true)
                    SecondaryButton("Connection details", onTelemetry, compact = true)
                    if (isGuest) {
                        SecondaryButton("Sign in to save", onSignInToSave, compact = true)
                    } else {
                        SecondaryButton("Sign out", onSignOut, compact = true, danger = true)
                    }
                }
            }
        }
        item {
            Panel("This device") {
                Text(
                    deviceSummary
                        ?: "Enrol this phone as a Voidbind device to sign in with its own key and to keep encrypted personal state here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.textMuted,
                )
                SecondaryButton(
                    if (isGuest) "Sign in to save" else "Device enrolment",
                    if (isGuest) onSignInToSave else onDevice,
                    compact = true,
                )
            }
        }
        // Followed sources and peers write/read owner state — hidden for a guest.
        if (!isGuest) {
            item {
                FollowedPanel(session, state, {
                    load()
                    onSourcesChanged()
                })
            }
        }
        if (!isGuest) item { PeersPanel(session, state) }
        item { AppearancePanel(session) }
    }
}

/** The node + profile fields, shared with the pre-login settings. */
@Composable
fun ConnectionFields(config: HeyarrConfig, onSave: (String, String) -> Unit, onReset: () -> Unit) {
    var baseUrl by rememberSaveable(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var profile by rememberSaveable(config.defaultQualityProfile) { mutableStateOf(config.defaultQualityProfile) }
    val normalized = HeyarrConfig.normalizeBaseUrl(baseUrl)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Field("Node URL", baseUrl, placeholder = HeyarrConfig.DEFAULT_BASE_URL, keyboard = KeyboardType.Uri) {
            baseUrl =
                it
        }
        Text(
            when {
                normalized == null -> "Enter an absolute http:// or https:// URL."

                normalized.startsWith(
                    "http://",
                ) -> "Plain http: release builds only allow cleartext to the build-default node; debug builds allow it everywhere."

                else -> "Build default: ${HeyarrConfig.DEFAULT_BASE_URL}. Changing the node signs out — a session is only good for the node that minted it."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (normalized ==
                null
            ) {
                Tokens.danger
            } else {
                Tokens.textMuted
            },
        )
        Field("Default quality profile", profile, placeholder = HeyarrConfig.DEFAULT_QUALITY_PROFILE) { profile = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                "Save",
                {
                    normalized?.let { onSave(it, profile) }
                },
                icon = Icons.Rounded.Save,
                compact = true,
                enabled =
                normalized != null,
            )
            GhostButton("Reset to defaults", onReset)
        }
    }
}

@Composable
private fun FollowedPanel(session: AppSession, state: SettingsState, reload: () -> Unit) {
    val scope = rememberCoroutineScope()
    Panel("Followed sources", trailing = { GhostButton("Refresh", reload) }) {
        when (val list = state.followed) {
            null -> Skeleton(Modifier.fillMaxWidth().height(40.dp))

            else -> if (list.isEmpty()) {
                Text("Nothing followed yet.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
            } else {
                for (s in list) {
                    FollowedRow(session, s, scope, reload)
                }
            }
        }
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
        FollowForm(session, state, scope, reload)
    }
}

/** The connection's live status and what the credential is allowed to do. */
@Composable
private fun ConnectionStatus(session: AppSession, authority: SessionAuthority?, isGuest: Boolean) {
    val (tone, label) = when (session.connection) {
        Connection.ONLINE -> Tokens.success to "connected"
        Connection.OFFLINE -> Tokens.danger to "offline"
        Connection.UNAUTHORIZED -> Tokens.warning to "credential refused"
        Connection.UNKNOWN -> Tokens.textDisabled to "connecting…"
    }
    KeyValue("status", label + (session.lastLatencyMs?.let { " · $it ms" } ?: ""), valueColor = tone)
    KeyValue(
        "signed in",
        when {
            isGuest -> "browsing as guest — browse, play, subtitles"

            authority == null -> "session unverified"

            authority.isDevice ->
                "enrolled device · " +
                    (if (authority.canWrite) "can write" else "read-only until an admin authorises its key")

            authority.canWrite -> "${authority.kind} · can write"

            else -> "${authority.kind} · read-only"
        },
    )
}

/** One followed source: its type, ref, archive progress and health, with Unfollow. */
@Composable
private fun FollowedRow(session: AppSession, s: FollowedSource, scope: CoroutineScope, reload: () -> Unit) {
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
                    "${s.itemsArchived ?: 0}/${s.itemsKnown ?: 0} archived",
                    s.health?.let {
                        "health $it"
                    },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.textMuted,
                maxLines = 1,
            )
        }
        SecondaryButton("Unfollow", {
            scope.launch {
                session.io { session.api.unfollow(s.id) }.onSuccess { r ->
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
        }, compact = true, danger = true)
    }
}

/** "Follow something new": a feed URL or TVDB id, an optional title, the profile and the backfill. */
@Composable
private fun FollowForm(session: AppSession, state: SettingsState, scope: CoroutineScope, reload: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var tvdb by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var profile by remember(session.profiles) {
        mutableStateOf(
            session.profiles.firstOrNull {
                it.name == session.defaultProfile
            }?.name ?: session.profiles.firstOrNull()?.name
                ?: session.defaultProfile,
        )
    }
    var backfill by remember { mutableStateOf("from_now") }
    Field("Feed / TVDB URL", url, placeholder = "https://…/rss", keyboard = KeyboardType.Uri) { url = it }
    Field("TVDB id", tvdb, keyboard = KeyboardType.Number) { tvdb = it.filter { c -> c.isDigit() } }
    Field("Title (only for content the library has never seen)", title) { title = it }
    FollowChoices(session.profiles, profile, { profile = it }, backfill) { backfill = it }
    PrimaryButton(
        "Follow",
        {
            scope.launch {
                state.busy = true
                session.io { session.api.follow(url, tvdb, title, profile, backfill) }.onSuccess { r ->
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

/** The profile a new follow is measured against, and how far back it fetches. */
@Composable
private fun FollowChoices(
    profiles: List<QualityProfile>,
    profile: String,
    onProfile: (String) -> Unit,
    backfill: String,
    onBackfill: (String) -> Unit,
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Profile", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
        for (p in profiles) FilterChip(p.name, profile == p.name, { onProfile(p.name) })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Backfill", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
        FilterChip("from now", backfill == "from_now", { onBackfill("from_now") })
        FilterChip("full back-catalogue", backfill == "full", { onBackfill("full") })
    }
}

@Composable
private fun PeersPanel(session: AppSession, state: SettingsState) {
    val scope = rememberCoroutineScope()
    Panel("Peers") {
        when (val p = state.peers) {
            null -> Skeleton(Modifier.fillMaxWidth().height(30.dp))

            else -> {
                for (peer in p.peers) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                peer.name + if (peer.isSelf) "  (this node)" else "",
                                style = MaterialTheme.typography.titleSmall,
                                color = Tokens.textPrimary,
                            )
                            Text(
                                listOfNotNull(
                                    peer.site,
                                    peer.mode?.let {
                                        "mode $it"
                                    },
                                ).joinToString("  ·  "),
                                style = MaterialTheme.typography.bodySmall,
                                color = Tokens.textMuted,
                            )
                        }
                        if (!peer.isSelf) {
                            SecondaryButton("Sync now", {
                                scope.launch {
                                    session.io { session.api.syncPeer(peer.peerId) }.onSuccess { r ->
                                        when (r) {
                                            is McpResult.Ok -> session.toast(
                                                Toast.Kind.INFO,
                                                "Reconciliation queued",
                                                "Transfers move afterwards; this reply only says the cycle was accepted.",
                                            )

                                            is McpResult.Refused -> session.refused(r)
                                        }
                                    }
                                }
                            }, icon = Icons.Rounded.Sync, compact = true)
                        }
                    }
                }
                p.note?.let { Notice(it, tone = Tokens.slate) }
            }
        }
    }
}

@Composable
private fun AppearancePanel(session: AppSession) {
    val ap = session.appearance
    Panel("Appearance") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("Media-adaptive accents", ap.adaptiveAccents, {
                session.updateAppearance(ap.copy(adaptiveAccents = !ap.adaptiveAccents))
            })
            FilterChip("Reduce motion", ap.reduceMotion, {
                session.updateAppearance(ap.copy(reduceMotion = !ap.reduceMotion))
            })
            FilterChip("Public cover art & synopses", session.externalMetadata, {
                session.updateExternalMetadata(!session.externalMetadata)
            })
        }
        Text(
            "Where the node holds no artwork, covers and synopses come from keyless public sources — TVmaze (series, with episode lists), Wikipedia (films), Open Library (books), Apple Podcasts, Cover Art Archive (music) and a feed's own image or site icon. Titles are sent to those services over a bare connection (never your credential); each answer is cached for a week in the app's cache. Everything external is labelled as such.",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )
        Text(
            "The accent follows the media in focus: emerald for film, violet for series, amber for books, teal for audiobooks, magenta for podcasts, rose for music. Surfaces and text never change.",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in listOf(
                MediaType.MOVIE,
                MediaType.SERIES,
                MediaType.BOOK,
                MediaType.AUDIOBOOK,
                MediaType.PODCAST,
                MediaType.MUSIC,
            )) {
                MediaScope(t) {
                    PrimaryButton(MediaThemes.of(t).ctaLabel, {}, compact = true)
                }
            }
        }
    }
}
