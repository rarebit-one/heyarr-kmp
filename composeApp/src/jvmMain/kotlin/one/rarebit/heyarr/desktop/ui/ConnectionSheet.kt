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
import one.rarebit.heyarr.desktop.ui.components.ModalScrim
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
    fun load() = loadConnection(session, state, scope)
    LaunchedEffect(Unit) { load() }

    ModalScrim(onClose, Modifier.width(720.dp).fillMaxHeight(0.92f)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectionPanel(session, state, ::load, onClose, onSettings)
            CredentialPanel(state.session)
            ProvidersPanel(state.providers)
            NodePanel(state)
            JobsPanel(state.jobs)
        }
    }
}

/** Probe the node, then load each of the sheet's pieces on its own. */
private fun loadConnection(session: AppSession, state: ConnectionState, scope: CoroutineScope) {
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

/** "just now", "N s ago", "N min ago", else the wall-clock time. */
internal fun ago(epochMs: Long): String {
    val s = (System.currentTimeMillis() - epochMs) / 1000
    return when {
        s < 5 -> "just now"
        s < 60 -> "$s s ago"
        s < 3600 -> "${s / 60} min ago"
        else -> SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(epochMs))
    }
}
