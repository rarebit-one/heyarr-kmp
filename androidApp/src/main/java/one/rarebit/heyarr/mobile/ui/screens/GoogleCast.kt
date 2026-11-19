@file:Suppress("FunctionNaming") // Composables are PascalCase by Compose convention.

package one.rarebit.heyarr.mobile.ui.screens

import android.view.ContextThemeWrapper
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import one.rarebit.heyarr.mobile.HeyarrApp
import one.rarebit.heyarr.mobile.R
import one.rarebit.heyarr.mobile.cast.CastManager
import one.rarebit.heyarr.mobile.cast.CastManager.Control
import one.rarebit.heyarr.mobile.cast.CastRecovery
import one.rarebit.heyarr.mobile.cast.CastRoute
import one.rarebit.heyarr.mobile.cast.CastUiState
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.mobile.ui.components.clockShort
import one.rarebit.heyarr.ui.components.IconButtonRound
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.Panel
import one.rarebit.heyarr.ui.theme.LocalMediaTheme

// Google Cast's surfaces (heyarr-kmp#250), kept apart from the node-driven DLNA renderers
// ("TVs the server can reach"), which they do not share a model with: here this phone holds
// the session, plans the film and (today, always) relays it; there the node does everything.

/** The app's [CastManager]; null in previews and tests (no [HeyarrApp]), which show no Cast button. */
@OptIn(UnstableApi::class) // The app graph holds Media3's players; nothing here touches them.
@Composable
private fun castManager(): CastManager? = (LocalContext.current.applicationContext as? HeyarrApp)?.graph?.cast

/**
 * The Cast SDK's own button: the standard glyph, its device picker and, once connected, its
 * dialog with the receiver's volume and Stop casting. Nothing at all on a phone without the
 * Cast framework (no Google Play services).
 */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    val cast = castManager() ?: return
    val state by cast.state.collectAsStateWithLifecycle()
    if (!state.available) return
    AndroidView(
        factory = { context ->
            // The picker is an AppCompat dialog and the app's theme is a platform one, so the
            // button gets a dark AppCompat theme (res/values/themes.xml), and a light glyph.
            MediaRouteButton(ContextThemeWrapper(context, R.style.Theme_Heyarr_CastButton)).apply {
                CastButtonFactory.setUpMediaRouteButton(context.applicationContext, this)
                contentDescription = "Google Cast: play on a TV"
            }
        },
        modifier = modifier.size(40.dp),
    )
}

/**
 * The bar above the bottom navigation while this phone's film plays on a TV: what, where,
 * and how it is doing, with play/pause and Stop. Tapping it opens the Cast screen.
 */
@Composable
fun CastingBar(cast: CastManager, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val state by cast.state.collectAsStateWithLifecycle()
    val item = state.item ?: return
    val scope = rememberCoroutineScope()
    val playing = state.state == "PLAYING" || state.state == "BUFFERING"
    Row(
        modifier.fillMaxWidth().background(Tokens.surface1).border(Tokens.hairline, Tokens.border)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f).clickable(onClick = onOpen)) {
            Text(
                "GOOGLE CAST · ${state.receiver?.name?.uppercase() ?: "RECONNECTING"}",
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.accentGradEnd,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                castLine(state),
                style = MaterialTheme.typography.labelSmall,
                color = Tokens.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButtonRound(
            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            if (playing) "Pause on the TV" else "Play on the TV",
            { scope.launch { cast.control(if (playing) Control.PAUSE else Control.RESUME) } },
            size = 40.dp,
            filled = true,
        )
        IconButtonRound(Icons.Rounded.Close, "Stop casting this film", {
            scope.launch { cast.control(Control.STOP) }
        }, size = 36.dp)
    }
}

/**
 * The Cast screen's Google Cast panel: the receiver, our film on it with a film-absolute
 * scrubber (a seek restarts the node's stream there), how the film reaches the TV, what the
 * manager is doing about a stall or a drop, and the receiver's volume.
 */
@Composable
fun GoogleCastPanel() {
    val cast = castManager() ?: return
    val state by cast.state.collectAsStateWithLifecycle()
    if (!state.available) return
    val scope = rememberCoroutineScope()
    // The watcher only runs while a film of ours plays; this keeps a bare session's report fresh.
    LaunchedEffect(cast) {
        while (isActive) {
            cast.refresh()
            delay(CastManager.WATCH_MS)
        }
    }
    Panel("Google Cast", trailing = { CastButton() }) {
        val receiver = state.receiver
        when {
            receiver == null && state.item == null -> Text(
                "Tap the Cast button to pick a Google Cast TV or speaker on this network. " +
                    "While connected, Play sends a film there.",
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.textMuted,
            )

            state.item == null -> Text(
                "Connected to ${receiver?.name}. Play a film and it plays there.",
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.textMuted,
            )

            else -> CastTransport(state, cast, scope = { scope.launch { it() } })
        }
        state.recovery?.let { Notice(recoveryText(it), icon = Icons.Rounded.CastConnected, tone = Tokens.slate) }
        state.error?.let { Notice(it, tone = Tokens.danger) }
        state.route?.let {
            Notice(
                it.why,
                icon = Icons.Rounded.Cast,
                tone = if (it is CastRoute.Relay) Tokens.warning else Tokens.slate,
            )
        }
        if (receiver != null) Volume(cast)
    }
}

@Composable
private fun CastTransport(state: CastUiState, cast: CastManager, scope: (suspend () -> Unit) -> Unit) {
    val theme = LocalMediaTheme.current
    val item = requireNotNull(state.item)
    val duration = state.duration
    val playing = state.state == "PLAYING" || state.state == "BUFFERING"
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(item.title, style = MaterialTheme.typography.titleMedium, color = Tokens.textPrimary)
        Text(castLine(state), style = MaterialTheme.typography.labelMedium, color = Tokens.textMuted)
        if (duration != null && duration > 0) {
            Slider(
                value = dragging ?: (state.position / duration).toFloat().coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    val to = dragging?.let { it * duration }
                    dragging = null
                    if (to != null) scope { cast.control(Control.SEEK, to) }
                },
                colors = SliderDefaults.colors(thumbColor = theme.accent, activeTrackColor = theme.accent),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButtonRound(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (playing) "Pause on the TV" else "Play on the TV",
                { scope { cast.control(if (playing) Control.PAUSE else Control.RESUME) } },
                size = 48.dp,
                filled = true,
            )
            IconButtonRound(Icons.Rounded.Stop, "Stop casting this film", {
                scope { cast.control(Control.STOP) }
            }, size = 48.dp)
        }
    }
}

@Composable
private fun Volume(cast: CastManager) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "TV volume",
            style = MaterialTheme.typography.labelMedium,
            color = Tokens.textMuted,
            modifier = Modifier.weight(1f),
        )
        IconButtonRound(Icons.AutoMirrored.Rounded.VolumeDown, "TV volume down", {
            cast.volume()?.let { cast.setVolume(it - VOLUME_STEP) }
        }, size = 40.dp)
        IconButtonRound(Icons.AutoMirrored.Rounded.VolumeUp, "TV volume up", {
            cast.volume()?.let { cast.setVolume(it + VOLUME_STEP) }
        }, size = 40.dp)
    }
}

private const val VOLUME_STEP = 0.05

/** `12:34 / 1:58:00 · playing` — film-absolute, and the receiver's state in words. */
internal fun castLine(state: CastUiState): String {
    val at = clockShort((state.position * MILLIS).toLong())
    val of = state.duration?.let { " / " + clockShort((it * MILLIS).toLong()) } ?: ""
    val doing = when (state.state) {
        "PLAYING" -> "playing"
        "PAUSED", "PAUSED_PLAYBACK" -> "paused"
        "BUFFERING" -> "buffering"
        "IDLE" -> "starting"
        else -> if (state.live) "waiting for the TV" else "reconnecting"
    }
    return "$at$of · $doing"
}

/** What the manager is doing about the receiver, for a person. */
internal fun recoveryText(recovery: CastRecovery): String {
    val at = clockShort((recovery.position * MILLIS).toLong())
    return when (recovery.kind) {
        CastRecovery.Kind.SEEKING ->
            "${recovery.device} jumped past what the server has made yet; restarting the film at $at."

        CastRecovery.Kind.RESTARTING -> "${recovery.device} stopped making progress; restarting it at $at."

        CastRecovery.Kind.GAVE_UP -> "${recovery.device} keeps stalling at $at."

        CastRecovery.Kind.RECONNECTED -> "Reconnected to ${recovery.device}; carrying on at $at."

        CastRecovery.Kind.RESUMING -> "The film stopped reaching ${recovery.device}; carrying on at $at."

        CastRecovery.Kind.WAITING_FOR_UNLOCK ->
            "Unlock this phone to carry on at $at: it relays the film, and a locked phone cannot sign for it."
    }
}

private const val MILLIS = 1_000.0
