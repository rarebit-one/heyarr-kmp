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
import kotlinx.coroutines.CoroutineScope
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

// The connection sheet's panels ([ConnectionSheet]), top to bottom.

/** The client's side of the connection: status, round-trip, probes, heartbeat, mode, token store and UI scale. */
@Composable
internal fun ConnectionPanel(
    session: AppSession,
    state: ConnectionState,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    onSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Panel("Connection", trailing = {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GhostButton("Refresh", onRefresh, icon = Icons.Rounded.Refresh)
            GhostButton("Close", onClose)
        }
    }) {
        ConnectionStatusLine(session)
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
}

/** The connection's state as a coloured dot and a word, with the last round-trip time. */
@Composable
private fun ConnectionStatusLine(session: AppSession) {
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
}

/** What this credential may do (`/session`). */
@Composable
internal fun CredentialPanel(info: SessionInfo?) {
    Panel("This credential") {
        when (val s = info) {
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
}

/** The node's providers (`/providers`) and whether each answered when last checked. */
@Composable
internal fun ProvidersPanel(providers: List<ProviderInfo>?) {
    Panel("Providers") {
        when (val p = providers) {
            null -> Skeleton(Modifier.fillMaxWidth().size(0.dp, 40.dp))

            else -> if (p.isEmpty()) {
                Text(
                    "No providers configured.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Tokens.textMuted,
                )
            } else {
                for (x in p) {
                    ProviderRow(x)
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(x: ProviderInfo) {
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

/** Proved worker capabilities (`/capabilities`), peers, and libraries with their roots. */
@Composable
internal fun NodePanel(state: ConnectionState) {
    Panel("Node") {
        CapabilityLines(state.capabilities)
        PeerAndLibraryLines(state.peers, state.libraries)
    }
}

/** The capabilities this node's workers have proved, and each holder's lease. */
@Composable
private fun CapabilityLines(capabilities: Capabilities?) {
    when (val c = capabilities) {
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
}

/** One line per peer, then per library with its roots. */
@Composable
private fun PeerAndLibraryLines(peers: PeerStatus?, libraries: List<LibraryInfo>?) {
    when (val p = peers) {
        null -> {}

        else -> for (peer in p.peers) {
            KeyValue(
                "peer",
                "${peer.name}${if (peer.isSelf) " (this node)" else ""} · ${peer.site ?: ""} · ${peer.mode ?: ""}",
            )
        }
    }
    when (val l = libraries) {
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

/** The node's last jobs, newest first, with any error as sent. */
@Composable
internal fun JobsPanel(jobs: List<JobInfo>?) {
    Panel("Recent jobs") {
        when (val j = jobs) {
            null -> Skeleton(Modifier.fillMaxWidth().size(0.dp, 30.dp))

            else -> if (j.isEmpty()) {
                Text("No jobs.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
            } else {
                for (job in j) {
                    JobRow(job)
                }
            }
        }
    }
}

@Composable
private fun JobRow(job: JobInfo) {
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
