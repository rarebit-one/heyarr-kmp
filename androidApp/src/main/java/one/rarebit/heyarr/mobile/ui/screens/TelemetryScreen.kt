package one.rarebit.heyarr.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.rarebit.heyarr.mobile.heyarr.Capabilities
import one.rarebit.heyarr.mobile.heyarr.JobInfo
import one.rarebit.heyarr.mobile.heyarr.LibraryInfo
import one.rarebit.heyarr.mobile.heyarr.ProviderInfo
import one.rarebit.heyarr.mobile.heyarr.SessionInfo
import one.rarebit.heyarr.core.mcp.PeerStatus
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.mobile.state.Connection
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.mobile.ui.components.GhostButton
import one.rarebit.heyarr.mobile.ui.components.KeyValue
import one.rarebit.heyarr.mobile.ui.components.Panel
import one.rarebit.heyarr.mobile.ui.components.PrimaryButton
import one.rarebit.heyarr.mobile.ui.components.RuleCode
import one.rarebit.heyarr.mobile.ui.components.SectionHeader
import one.rarebit.heyarr.mobile.ui.components.Skeleton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the sheet loads, each piece on its own. */
class TelemetryState {
    var session by mutableStateOf<SessionInfo?>(null)
    var providers by mutableStateOf<List<ProviderInfo>?>(null)
    var capabilities by mutableStateOf<Capabilities?>(null)
    var peers by mutableStateOf<PeerStatus?>(null)
    var libraries by mutableStateOf<List<LibraryInfo>?>(null)
    var jobs by mutableStateOf<List<JobInfo>?>(null)
    var error by mutableStateOf<String?>(null)
}

/**
 * Connection telemetry, from Settings: the client side (node URL, status, round-trip,
 * last success, probe counts) and the node's own account of itself — what this
 * credential may do (`/session`), the providers and whether each answered
 * (`/providers`), proved worker capabilities (`/capabilities`), peers, libraries and
 * roots, and the last jobs. Ported from heyarr-desktop's `ConnectionSheet`.
 */
@Composable
fun TelemetryScreen(session: AppSession, state: TelemetryState, credentialSummary: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    fun load() {
        val a = session.api
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

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Tokens.screenPadding, vertical = Tokens.s3), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GhostButton("Settings", onBack, icon = Icons.Rounded.ArrowBack)
                Spacer(Modifier.weight(1f))
                GhostButton("Refresh", ::load, icon = Icons.Rounded.Refresh)
            }
        }
        item { SectionHeader("Connection") }
        item {
            Panel("This phone") {
                val (tone, label) = when (session.connection) {
                    Connection.ONLINE -> Tokens.success to "Connected"
                    Connection.OFFLINE -> Tokens.danger to "Offline"
                    Connection.UNAUTHORIZED -> Tokens.warning to "Credential refused"
                    Connection.UNKNOWN -> Tokens.textDisabled to "Connecting…"
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).background(tone, RectangleShape))
                    Text(label, style = MaterialTheme.typography.titleMedium, color = Tokens.textPrimary)
                    session.lastLatencyMs?.let { Text("$it ms round-trip", style = MaterialTheme.typography.labelMedium, color = Tokens.textMuted) }
                }
                KeyValue("node", session.baseUrl)
                KeyValue("last success", session.lastOkAt?.let { ago(it) } ?: "never")
                KeyValue("probes", "${session.probes} sent · ${session.failures} failed" + (session.lastFailure?.let { " · last: $it" } ?: ""))
                KeyValue("heartbeat", "every 30 s while online, every 8 s while not")
                KeyValue("credential", credentialSummary)
                PrimaryButton("Test now", { scope.launch { session.probe() } }, compact = true)
                state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tokens.danger) }
            }
        }
        item {
            Panel("This credential") {
                when (val s = state.session) {
                    null -> Skeleton(Modifier.fillMaxWidth().height(40.dp))
                    else -> {
                        KeyValue("kind", s.kind)
                        KeyValue("scopes", s.scopes.joinToString(", ").ifBlank { "none" })
                        KeyValue("can write", if (s.canWrite) "yes — want, follow, acquire will succeed" else "no — this credential is read-only; writes will be refused", valueColor = if (s.canWrite) Tokens.success else Tokens.warning)
                        s.principalId?.let { KeyValue("principal", it, valueColor = Tokens.textMuted) }
                        s.deviceKey?.takeIf { it.isNotBlank() }?.let { KeyValue("device key", it, valueColor = Tokens.textMuted) }
                    }
                }
            }
        }
        item {
            Panel("Providers") {
                when (val p = state.providers) {
                    null -> Skeleton(Modifier.fillMaxWidth().height(40.dp))
                    else -> if (p.isEmpty()) Text("No providers configured.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
                    else for (x in p) Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.padding(top = 5.dp).size(8.dp).background(if (x.healthy) Tokens.success else Tokens.danger, RectangleShape))
                        Column(Modifier.weight(1f)) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(x.name, style = MaterialTheme.typography.titleSmall, color = Tokens.textPrimary)
                                for (c in x.capabilities) RuleCode(c, tone = Tokens.textMuted)
                                x.version?.takeIf { it != "unreported" }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Tokens.textDisabled) }
                            }
                            Text(listOfNotNull(x.detail, x.checkedAt?.let { "checked ${it.take(19).replace('T', ' ')}" }).joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = if (x.healthy) Tokens.textMuted else Tokens.danger)
                        }
                    }
                }
            }
        }
        item {
            Panel("Node") {
                when (val c = state.capabilities) {
                    null -> Skeleton(Modifier.fillMaxWidth().height(30.dp))
                    else -> {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (cap in c.available) RuleCode(cap, tone = Tokens.textPrimary) }
                        for (h in c.holders) KeyValue(h.peerName, "worker ${h.workerId} · ${h.capabilities.size} capabilities proved" + (h.expiresAt?.let { " · lease to ${it.take(19).replace('T', ' ')}" } ?: ""), valueColor = Tokens.textMuted)
                    }
                }
                state.peers?.let { p -> for (peer in p.peers) KeyValue("peer", "${peer.name}${if (peer.isSelf) " (this node)" else ""} · ${peer.site ?: ""} · ${peer.mode ?: ""}") }
                state.libraries?.let { l -> for (lib in l) KeyValue("library", "${lib.name} (${lib.contentType ?: "?"})${if (!lib.enabled) " · disabled" else ""} — " + lib.roots.joinToString(", "), valueColor = Tokens.textMuted) }
            }
        }
        item {
            Panel("Recent jobs") {
                when (val j = state.jobs) {
                    null -> Skeleton(Modifier.fillMaxWidth().height(30.dp))
                    else -> if (j.isEmpty()) Text("No jobs.", style = MaterialTheme.typography.bodySmall, color = Tokens.textMuted)
                    else for (job in j) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RuleCode(job.state, tone = when (job.state) { "succeeded" -> Tokens.success; "failed", "dead" -> Tokens.danger; else -> Tokens.textMuted })
                        Column(Modifier.weight(1f)) {
                            Text(job.type, style = MaterialTheme.typography.bodySmall, color = Tokens.textPrimary)
                            job.lastError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Tokens.danger) }
                        }
                        Text(job.updatedAt?.take(16)?.replace('T', ' ') ?: "", style = MaterialTheme.typography.labelSmall, color = Tokens.textDisabled)
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
