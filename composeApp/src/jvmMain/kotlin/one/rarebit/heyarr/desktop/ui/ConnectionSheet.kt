package one.rarebit.heyarr.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
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
import one.rarebit.heyarr.core.heyarr.Capabilities
import one.rarebit.heyarr.core.heyarr.JobInfo
import one.rarebit.heyarr.core.heyarr.LibraryInfo
import one.rarebit.heyarr.core.heyarr.ProviderInfo
import one.rarebit.heyarr.core.heyarr.SessionInfo
import one.rarebit.heyarr.core.mcp.PeerStatus
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.state.Connection
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.KeyValue
import one.rarebit.heyarr.ui.components.Panel
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.RuleCode
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.components.Skeleton
import one.rarebit.heyarr.ui.theme.Tokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the sheet loads, each piece on its own. */
class ConnectionState {
    var session by mutableStateOf<SessionInfo?>(null)
    var providers by mutableStateOf<List<ProviderInfo>?>(null)
    var capabilities by mutableStateOf<Capabilities?>(null)
    var peers by mutableStateOf<PeerStatus?>(null)
    var libraries by mutableStateOf<List<LibraryInfo>?>(null)
    var jobs by mutableStateOf<List<JobInfo>?>(null)
    var error by mutableStateOf<String?>(null)
}

/**
 * Connection telemetry, opened from the dot at the foot of the nav: the client side
 * (node URL, status, round-trip, last success, probe counts, token store, UI scale)
 * and the node's own account of itself — what this credential may do (`/session`),
 * the providers and whether each answered (`/providers`), proved worker capabilities
 * (`/capabilities`), peers, libraries and roots, and the last jobs.
 */
@Composable
fun ConnectionSheet(session: AppSession, state: ConnectionState, onClose: () -> Unit, onSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    fun load() {
        val a = session.api ?: return
        state.error = null
        scope.launch { session.probe() }
        scope.launch { session.io { a.sessionInfo() }.fold({ state.session = it }, { state.error = it.message }) }
        scope.launch { session.io { a.providers() }.onSuccess { state.providers = it } }
        scope.launch { session.io { a.capabilities() }.onSuccess { state.capabilities = it } }
        scope.launch { session.io { a.peers() }.onSuccess { state.peers = it } }
        scope.launch { session.io { a.libraries() }.onSuccess { state.libraries = it } }
        scope.launch { session.io { a.jobs() }.onSuccess { state.jobs = it } }
    }
    LaunchedEffect(Unit) { load() }

    Box(
        Modifier.fillMaxSize().background(Tokens.bgBase.copy(alpha = 0.7f)).clickable(
            interactionSource = remember {
                MutableInteractionSource()
            },
            indication = null,
            onClick = onClose,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.width(720.dp).fillMaxHeight(0.92f).clickable(
                interactionSource = remember {
                    MutableInteractionSource()
                },
                indication = null,
                onClick = {},
            ),
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Panel("Connection", trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GhostButton("Refresh", ::load, icon = Icons.Rounded.Refresh)
                        GhostButton("Close", onClose)
                    }
                }) {
                    val (tone, label) = when (session.connection) {
                        Connection.ONLINE -> Tokens.success to "Connected"
                        Connection.OFFLINE -> Tokens.danger to "Offline"
                        Connection.UNAUTHORIZED -> Tokens.warning to "Token refused"
                        Connection.UNCONFIGURED -> Tokens.textDisabled to "Not configured"
                        Connection.UNKNOWN -> Tokens.textDisabled to "Connecting…"
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(10.dp).background(tone, CircleShape))
                        Text(label, style = MaterialTheme.typography.titleMedium, color = Tokens.textPrimary)
                        session.lastLatencyMs?.let {
                            Text(
                                "$it ms round-trip",
                                style = MaterialTheme.typography.labelMedium,
                                color = Tokens.textMuted,
                            )
                        }
                    }
                    KeyValue("node", session.config.baseUrl)
                    KeyValue("last success", session.lastOkAt?.let { ago(it) } ?: "never")
                    KeyValue(
                        "probes",
                        "${session.probes} sent · ${session.failures} failed" +
                            (session.lastFailure?.let { " · last: $it" } ?: ""),
                    )
                    KeyValue("heartbeat", "every 30 s while online, every 8 s while not")
                    KeyValue(
                        "mode",
                        if (session.isGuest) "guest — browsing without a login (browse & play)" else "signed in",
                        valueColor = if (session.isGuest) Tokens.textMuted else Tokens.success,
                    )
                    KeyValue(
                        "token",
                        if (session.config.bearerToken.isBlank()) "none (guest)" else "bearer, ${session.config.bearerToken.trim().length} chars, in ~/.config/heyarr-desktop/config.json (0600)",
                    )
                    KeyValue("ui scale", "${session.config.effectiveUiScale()}×")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("Test now", { scope.launch { session.probe() } }, compact = true)
                        SecondaryButton("Open Settings", {
                            onClose()
                            onSettings()
                        }, compact = true)
                    }
                    state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tokens.danger) }
                }
                Panel("This credential") {
                    when (val s = state.session) {
                        null -> Skeleton(Modifier.fillMaxWidth().padding(vertical = 4.dp).size(0.dp, 40.dp))

                        else -> {
                            KeyValue("kind", s.kind)
                            KeyValue("scopes", s.scopes.joinToString(", ").ifBlank { "none" })
                            KeyValue(
                                "can write",
                                if (s.canWrite) "yes — want, follow, acquire will succeed" else "no — this token is read-only; writes will be refused",
                                valueColor = if (s.canWrite) Tokens.success else Tokens.warning,
                            )
                            s.principalId?.let { KeyValue("principal", it, valueColor = Tokens.textMuted) }
                        }
                    }
                }
                Panel("Providers") {
                    when (val p = state.providers) {
                        null -> Skeleton(Modifier.fillMaxWidth().size(0.dp, 40.dp))

                        else -> if (p.isEmpty()) {
                            Text(
                                "No providers configured.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Tokens.textMuted,
                            )
                        } else {
                            for (x in p) {
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(
                                        Modifier.padding(
                                            top = 5.dp,
                                        ).size(
                                            8.dp,
                                        ).background(if (x.healthy) Tokens.success else Tokens.danger, CircleShape),
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                x.name,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = Tokens.textPrimary,
                                            )
                                            for (c in x.capabilities) RuleCode(c, tone = Tokens.textMuted)
                                            x.version?.takeIf {
                                                it != "unreported"
                                            }?.let {
                                                Text(
                                                    it,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Tokens.textDisabled,
                                                )
                                            }
                                        }
                                        Text(
                                            listOfNotNull(
                                                x.detail,
                                                x.checkedAt?.let {
                                                    "checked ${it.take(19).replace('T', ' ')}"
                                                },
                                            ).joinToString("  ·  "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (x.healthy) Tokens.textMuted else Tokens.danger,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Panel("Node") {
                    when (val c = state.capabilities) {
                        null -> Skeleton(Modifier.fillMaxWidth().size(0.dp, 30.dp))

                        else -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (cap in c.available) RuleCode(cap, tone = Tokens.textPrimary)
                            }
                            for (h in c.holders) {
                                KeyValue(
                                    h.peerName,
                                    "worker ${h.workerId} · ${h.capabilities.size} capabilities proved" +
                                        (
                                            h.expiresAt?.let {
                                                " · lease to ${it.take(19).replace('T', ' ')}"
                                            } ?: ""
                                            ),
                                    valueColor = Tokens.textMuted,
                                )
                            }
                        }
                    }
                    when (val p = state.peers) {
                        null -> {}

                        else -> for (peer in p.peers) {
                            KeyValue(
                                "peer",
                                "${peer.name}${if (peer.isSelf) " (this node)" else ""} · ${peer.site ?: ""} · ${peer.mode ?: ""}",
                            )
                        }
                    }
                    when (val l = state.libraries) {
                        null -> {}

                        else -> for (lib in l) {
                            KeyValue(
                                "library",
                                "${lib.name} (${lib.contentType ?: "?"})${if (!lib.enabled) " · disabled" else ""} — " +
                                    lib.roots.joinToString(", "),
                                valueColor = Tokens.textMuted,
                            )
                        }
                    }
                }
                Panel("Recent jobs") {
                    when (val j = state.jobs) {
                        null -> Skeleton(Modifier.fillMaxWidth().size(0.dp, 30.dp))

                        else -> if (j.isEmpty()) {
                            Text("No jobs.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
                        } else {
                            for (job in j) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RuleCode(
                                        job.state,
                                        tone = when (job.state) {
                                            "succeeded" -> Tokens.success
                                            "failed" -> Tokens.danger
                                            else -> Tokens.textMuted
                                        },
                                    )
                                    Text(
                                        job.type,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Tokens.textPrimary,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        job.updatedAt?.take(16)?.replace('T', ' ') ?: "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Tokens.textDisabled,
                                    )
                                    job.lastError?.let {
                                        Text(it, style = MaterialTheme.typography.labelSmall, color = Tokens.danger)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ago(epochMs: Long): String {
    val s = (System.currentTimeMillis() - epochMs) / 1000
    return when {
        s < 5 -> "just now"
        s < 60 -> "$s s ago"
        s < 3600 -> "${s / 60} min ago"
        else -> SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(epochMs))
    }
}
