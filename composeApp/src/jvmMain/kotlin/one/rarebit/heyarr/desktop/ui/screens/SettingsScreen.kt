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
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.feeds.FollowedSource
import one.rarebit.heyarr.core.mcp.PeerStatus
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.heyarr.McpResult
import one.rarebit.heyarr.desktop.settings.DesktopConfig
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.state.Connection
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

class SettingsState {
    var followed by mutableStateOf<List<FollowedSource>?>(null)
    var peers by mutableStateOf<PeerStatus?>(null)
    var busy by mutableStateOf(false)
}

/**
 * Settings — the heyarr connection, followed sources (`list_followed`, `follow_source`,
 * `unfollow`), peers (`get_peer_status`, `sync_peer`) and appearance.
 */
@Composable
fun SettingsScreen(
    session: AppSession,
    state: SettingsState,
    onSourcesChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    fun load() {
        val a = session.api ?: return
        // Followed sources and peers are enrolled surfaces; a guest cannot read them.
        if (session.isGuest) return
        scope.launch { session.io { a.followed() }.onSuccess { state.followed = it } }
        scope.launch { session.io { a.peers() }.onSuccess { state.peers = it } }
    }
    LaunchedEffect(session.config) { load() }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { SectionHeader("Settings") }
        item { ConnectionPanel(session) }
        item { one.rarebit.heyarr.desktop.ui.EnrolPanel(session) }
        if (session.isGuest) {
            item {
                Panel("Followed sources & peers") {
                    Notice(
                        "Sign in to follow sources and manage peers.",
                        detail = "Following, wants and peer sync are enrolled-only. Add a bearer token above to sign in.",
                        tone = Tokens.slate,
                    )
                }
            }
        } else {
            item {
                FollowedPanel(session, state, {
                    load()
                    onSourcesChanged()
                })
            }
            item { PeersPanel(session, state) }
        }
        item { AppearancePanel(session) }
    }
}

@Composable
private fun ConnectionPanel(session: AppSession) {
    val scope = rememberCoroutineScope()
    var baseUrl by remember(session.config) { mutableStateOf(session.config.baseUrl) }
    var token by remember(session.config) { mutableStateOf(session.config.bearerToken) }
    var testing by remember { mutableStateOf(false) }
    var discovering by remember { mutableStateOf(false) }
    Panel("heyarr connection") {
        Field("Base URL", baseUrl, placeholder = DesktopConfig.DEFAULT_BASE_URL) { baseUrl = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Auto-discovery: try mDNS on the LAN, else the split-horizon DNS name, and
            // default the field to whatever is found. The user can still edit it by hand.
            SecondaryButton("Discover", {
                discovering = true
                scope.launch {
                    val found = session.discoverServer()
                    baseUrl = found.baseUrl
                    discovering = false
                    session.toast(Toast.Kind.INFO, "Found via ${found.source.name.lowercase()}", found.baseUrl)
                }
            }, icon = Icons.Rounded.Sync, compact = true, enabled = !discovering)
            GhostButton("Reset URL", { baseUrl = DesktopConfig.DEFAULT_BASE_URL })
        }
        Field("Bearer token (heyarr_<id>_<secret>) — optional; blank browses as a guest", token, secret = true) {
            token =
                it
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("Save", {
                session.save(DesktopConfig(baseUrl.trim(), token.trim()))
            }, icon = Icons.Rounded.Save, compact = true)
            SecondaryButton("Test connection", {
                testing = true
                scope.launch {
                    session.probe()
                    testing = false
                    session.toast(
                        if (session.connection ==
                            Connection.ONLINE
                        ) {
                            Toast.Kind.SUCCESS
                        } else {
                            Toast.Kind.ERROR
                        },
                        "Connection: ${session.connection.name.lowercase()}",
                    )
                }
            }, compact = true, enabled = !testing && baseUrl.isNotBlank())
        }
        KeyValue("status", session.connection.name.lowercase().replace('_', ' '))
        KeyValue(
            "signed in as",
            if (session.isGuest) "guest — browse & play only" else "enrolled — token presented",
            valueColor = if (session.isGuest) Tokens.textMuted else Tokens.success,
        )
        Text(
            "On a trusted network you browse and play as a guest with no token. Add a bearer token to sign in and save wants, follows and your place. Saved to ~/.config/heyarr-desktop/config.json (0600); the token is a secret. Prefer device sign-in? Use “Sign in to save” below to pair this desktop with Voidbind — the primary, offline credential.",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textDisabled,
        )
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
                                val a = session.api ?: return@SecondaryButton
                                scope.launch {
                                    session.io { a.syncPeer(peer.peerId) }.onSuccess { r ->
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("Media-adaptive accents", ap.adaptiveAccents, {
                session.appearance =
                    ap.copy(adaptiveAccents = !ap.adaptiveAccents)
            })
            FilterChip("Reduce motion", ap.reduceMotion, {
                session.appearance = ap.copy(reduceMotion = !ap.reduceMotion)
            })
            FilterChip("Public cover art & synopses", session.config.externalMetadata, {
                session.save(session.config.copy(externalMetadata = !session.config.externalMetadata))
            })
        }
        Text(
            "Where the node holds no artwork, covers and synopses come from keyless public sources — TVmaze (series, with episode lists), Wikipedia (films), Open Library (books), Apple Podcasts, Cover Art Archive (music) and a feed's own image or site icon. Titles are sent to those services; each answer is cached for a week under ~/.cache/heyarr-desktop/meta. Everything external is labelled as such.",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )
        Text(
            "Adaptive accents use Fern, Pale Mint, Ochre and related muted Archive tones. " +
                "Artwork keeps its original colors; app surfaces and text stay consistent.",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )
        AccentPreview()
        Text(
            "UI scale",
            style = MaterialTheme.typography.titleSmall,
            color = Tokens.textPrimary,
            modifier = Modifier.padding(top = 8.dp),
        )
        val current = session.config.effectiveUiScale()
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip("Auto", session.config.uiScale == null, { session.save(session.config.copy(uiScale = null)) })
            for (sc in DesktopConfig.UI_SCALES) {
                FilterChip("$sc×", session.config.uiScale == sc, {
                    session.save(session.config.copy(uiScale = sc))
                })
            }
            Text("now $current×", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
        }
        Text(
            "A JVM under XWayland on a HiDPI Wayland desktop reports 1× and ignores the JVM scale flags, so pick the scale here (or set HEYARR_UI_SCALE / GDK_SCALE).",
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textMuted,
        )
    }
}

/** One call-to-action per media type, each under its own accent — a live key for the paragraph above. */
@Composable
private fun AccentPreview() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
