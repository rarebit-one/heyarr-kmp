package one.rarebit.heyarr.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.mcp.PlaybackStatus
import one.rarebit.heyarr.core.mcp.Renderer
import one.rarebit.heyarr.mobile.heyarr.McpResult
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.ui.components.EmptyState
import one.rarebit.heyarr.ui.components.ErrorState
import one.rarebit.heyarr.ui.components.FilterChip
import one.rarebit.heyarr.ui.components.IconButtonRound
import one.rarebit.heyarr.ui.components.KeyValue
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.Panel
import one.rarebit.heyarr.ui.components.SectionHeader
import one.rarebit.heyarr.ui.components.Skeleton
import one.rarebit.heyarr.ui.theme.LocalMediaTheme

class CastState {
    var renderers by mutableStateOf<List<Renderer>?>(null)
    var error by mutableStateOf<String?>(null)
    var selected by mutableStateOf<Renderer?>(null)
    var status by mutableStateOf<McpResult<PlaybackStatus?>?>(null)
    var busy by mutableStateOf(false)
}

/**
 * Cast — pick a renderer (`list_renderers`), watch its live `playback_status` (polled
 * every 2 s while the screen is open), and drive it with `control_playback`.
 * Everything shown here is what the device reports right now; there is no history and
 * no resume position, because heyarr cannot see either.
 */
@Composable
fun CastScreen(session: AppSession, state: CastState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    fun load(refresh: Boolean = false) {
        state.error = null
        scope.launch {
            session.io { session.api.renderers(refresh) }.fold(
                onSuccess = { list ->
                    state.renderers = list
                    if (state.selected == null) state.selected = list.firstOrNull()
                },
                onFailure = { state.error = it.message },
            )
        }
    }
    LaunchedEffect(Unit) { if (state.renderers == null) load() }
    LaunchedEffect(state.selected?.udn) {
        val r = state.selected ?: return@LaunchedEffect
        while (isActive) {
            session.io { session.api.playbackStatus(r.name) }.onSuccess { state.status = it }
            delay(2000)
        }
    }
    fun control(action: String) {
        val r = state.selected ?: return
        scope.launch {
            state.busy = true
            session.io {
                session.api.control(r.name, action)
            }.onSuccess { res -> if (res is McpResult.Refused) session.refused(res) }
            session.io { session.api.playbackStatus(r.name) }.onSuccess { state.status = it }
            state.busy = false
        }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(
            rememberScrollState(),
        ).padding(horizontal = Tokens.screenPadding).padding(top = Tokens.s4, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SectionHeader("Cast", subtitle = "Renderers on the network and what each is doing", trailing = {
            IconButtonRound(Icons.Rounded.Refresh, "Search the network again", { load(refresh = true) }, size = 36.dp)
        })
        RendererList(state) { load() }
        state.selected?.let { r -> RendererPanel(r, state, ::control) }
        Notice(
            "Playback position is the device's own report, live. heyarr keeps no play history this client can read; to send something here, use Play on… from a work.",
            icon = Icons.Rounded.Cast,
            tone = Tokens.slate,
        )
    }
}

/** The renderers on the network as chips (or why there are none); picking one selects it. */
@Composable
private fun RendererList(state: CastState, onRetry: () -> Unit) {
    when {
        state.error != null && state.renderers == null -> ErrorState("Couldn't list renderers", state.error, onRetry)

        state.renderers == null -> Skeleton(Modifier.fillMaxWidth().height(40.dp))

        state.renderers!!.isEmpty() -> EmptyState(
            "No renderers found",
            detail = "A device that is switched off is not listed — that is not the same as it not existing. Switch it on and search again.",
            icon = Icons.Rounded.Cast,
        )

        else -> Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (r in state.renderers!!) {
                FilterChip(r.name, state.selected?.udn == r.udn, {
                    state.selected = r
                    state.status = null
                }, icon = Icons.Rounded.Cast)
            }
        }
    }
}

/** The selected renderer: what it says about itself and, live, what it is playing. */
@Composable
private fun RendererPanel(r: Renderer, state: CastState, control: (String) -> Unit) {
    Panel(r.name) {
        if (r.subtitle.isNotBlank()) {
            Text(
                r.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.textMuted,
            )
        }
        when (val s = state.status) {
            null -> Skeleton(Modifier.fillMaxWidth().height(60.dp))

            is McpResult.Refused -> Notice("playback_status: ${s.message}", tone = Tokens.danger)

            is McpResult.Ok -> {
                val st = s.value
                if (st ==
                    null
                ) {
                    Text("No status reported.", color = Tokens.textMuted)
                } else {
                    Transport(st, state.busy, control)
                }
            }
        }
    }
}

@Composable
private fun Transport(st: PlaybackStatus, busy: Boolean, control: (String) -> Unit) {
    val theme = LocalMediaTheme.current
    val playing = st.playing || st.state.equals("PLAYING", ignoreCase = true)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(10.dp).background(if (playing) theme.accent else Tokens.textDisabled, RectangleShape))
            Text(
                st.state.lowercase().replace('_', ' '),
                style = MaterialTheme.typography.titleMedium,
                color = Tokens.textPrimary,
            )
        }
        st.title?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tokens.textMuted) }
        val dur = st.durationSeconds
        val frac = if (dur != null && dur > 0) (st.elapsedSeconds.toFloat() / dur).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(6.dp).background(Tokens.surface3, RectangleShape)) {
            Box(Modifier.fillMaxWidth(frac).height(6.dp).background(theme.accent, RectangleShape))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                clock(st.elapsedSeconds) + (
                    dur?.let {
                        " / " + clock(it)
                    } ?: ""
                    ),
                style = MaterialTheme.typography.labelMedium,
                color = Tokens.textMuted,
                modifier = Modifier.weight(1f),
            )
            if (playing) {
                IconButtonRound(Icons.Rounded.Pause, "Pause", {
                    control("pause")
                }, enabled = !busy, size = 48.dp, filled = true)
            } else {
                IconButtonRound(Icons.Rounded.PlayArrow, "Resume", {
                    control("resume")
                }, enabled = !busy, size = 48.dp, filled = true)
            }
            IconButtonRound(Icons.Rounded.Stop, "Stop", { control("stop") }, enabled = !busy, size = 48.dp)
        }
        if (st.elapsedSeconds == 0L &&
            playing
        ) {
            KeyValue(
                "position",
                "not reported — some devices report none until they have parsed enough of the stream",
                valueColor = Tokens.textMuted,
            )
        }
    }
}

private fun clock(s: Long): String {
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
