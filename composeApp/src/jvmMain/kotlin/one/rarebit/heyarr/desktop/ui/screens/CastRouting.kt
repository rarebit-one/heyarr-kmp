package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.core.mcp.Renderer
import one.rarebit.heyarr.core.mcp.RendererListResult
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.core.state.ToastAction
import one.rarebit.heyarr.desktop.cast.CastDevice
import one.rarebit.heyarr.desktop.cast.CastRecovery
import one.rarebit.heyarr.desktop.heyarr.CastResult
import one.rarebit.heyarr.desktop.heyarr.HeyarrApi
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.ui.components.FilterChip
import one.rarebit.heyarr.ui.components.HeyarrIcons
import one.rarebit.heyarr.ui.theme.Tokens

/**
 * The renderer picker shared by the detail and player screens, grouped by site (heyarr-core
 * ADR-0111): the client's own site first, labelled "Here", then the others by name. A renderer
 * only the client can see (`r3_only`) is shown disabled — the server cannot drive it until the
 * client-relay route exists.
 */
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
@Composable
internal fun RendererGroups(
    list: RendererListResult,
    castDevices: List<CastDevice>,
    onPick: (Renderer) -> Unit,
    onCast: (CastDevice) -> Unit,
) {
    val clientSite = list.clientSite
    val bySite = list.renderers.groupBy { it.site.orEmpty() }
    val siteOrder = buildList {
        if (clientSite != null && clientSite in bySite) add(clientSite)
        bySite.keys.filter { it != clientSite }.sorted().forEach { add(it) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CastDeviceGroup(castDevices, onCast)
        for (site in siteOrder) {
            val group = bySite[site] ?: continue
            val label = if (site == clientSite) "Here" else site.ifBlank { "Other" }
            // One unlabelled group is the single-site case: nothing to tell apart.
            if (siteOrder.size > 1 || site == clientSite) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in group) {
                    FilterChip(
                        r.name,
                        false,
                        { if (!r.r3Only) onPick(r) },
                        icon = HeyarrIcons.Cast,
                        enabled = !r.r3Only,
                    )
                }
            }
            if (group.any { it.r3Only }) {
                Text(
                    "Not reachable from the server yet",
                    style = MaterialTheme.typography.labelSmall,
                    color = Tokens.textDisabled,
                )
            }
        }
    }
}

@Suppress("FunctionNaming") // Compose components use title case.
@Composable
private fun CastDeviceGroup(devices: List<CastDevice>, onCast: (CastDevice) -> Unit) {
    if (devices.isEmpty()) return
    Text("Google Cast", style = MaterialTheme.typography.labelSmall, color = Tokens.textMuted)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (device in devices) {
            FilterChip(device.name, false, { onCast(device) }, icon = HeyarrIcons.Cast)
        }
    }
}

/** What the Cast manager is doing for a receiver, in film time, for a toast or the Cast panel. */
internal fun castRecoveryText(r: CastRecovery): Pair<String, String> = when (r.kind) {
    CastRecovery.Kind.SEEKING ->
        "Seeking on ${r.device}" to
            "Restarting the stream at ${clock(r.position)}: the receiver can't skip ahead in it by itself."

    CastRecovery.Kind.RESTARTING ->
        "${r.device} stopped playing" to
            "Restarting its Cast session at ${clock(r.position)}."

    CastRecovery.Kind.GAVE_UP ->
        "${r.device} keeps failing" to
            "Stopped retrying at ${clock(r.position)}. Stop and cast again, or restart the receiver."

    CastRecovery.Kind.RECONNECTED ->
        "Reconnected to ${r.device}" to "Resuming at ${clock(r.position)}."

    CastRecovery.Kind.RESUMING ->
        "${r.device} lost the stream" to "Resuming at ${clock(r.position)}."
}

internal fun castLocally(
    session: AppSession,
    device: CastDevice,
    assetId: String,
    title: String = "Media",
    afterSuccess: () -> Unit = {},
) {
    session.launch {
        val api = session.api ?: return@launch
        session.io { session.cast.play(device, api, assetId, title) }.fold(
            onSuccess = {
                // The receiver owns the film now. A paused local player kept its own
                // stream open on the node, and resuming or reopening it started a second
                // transcode beside the receiver's (heyarr-kmp#248): stop it, don't pause.
                if (session.playback.current?.assetId == assetId) session.playback.stop()
                afterSuccess()
                session.toast(Toast.Kind.SUCCESS, "Playing on ${device.name}", "Keep this app open while it plays.")
            },
            onFailure = {
                session.toast(Toast.Kind.REFUSED, "Couldn't cast to ${device.name}", it.message ?: "Cast failed")
            },
        )
    }
}

/**
 * Casts to one [renderer] and says how it went. Runs on the SESSION scope, not the picker's:
 * closing the picker removes it from composition, which would cancel a picker-scoped
 * coroutine — and its toast — before the cast finished.
 *
 * A codec refusal offers "Cast anyway" (force_direct); forcing again is shown as a plain
 * refusal, so no loop. A route refusal (409 with alternatives) offers the smaller copy the
 * server named, cast through this same path so its outcome is reported too.
 */
internal class Caster(
    private val session: AppSession,
    private val api: HeyarrApi,
    private val renderer: Renderer,
    private val before: suspend () -> Unit = {},
    private val after: () -> Unit = {},
) {
    fun cast(assetId: String, force: Boolean = false) {
        session.launch {
            before()
            session.io { api.playHere(assetId, renderer.name, renderer.udn, forceDirect = force) }
                .onSuccess { report(it, assetId, force) }
            after()
        }
    }

    private fun report(res: CastResult, assetId: String, force: Boolean) {
        when (res) {
            is CastResult.Success -> session.toast(
                Toast.Kind.SUCCESS,
                "Playing on ${renderer.name}",
                if (res.clientMustStayConnected) "Keep this app open while it plays." else "You can close the app.",
            )

            is CastResult.Refused -> if (force) {
                session.refused(res)
            } else {
                session.castRefused(res, renderer.name) { cast(assetId, force = true) }
            }

            is CastResult.Conflict -> {
                val smaller = res.alternatives.firstOrNull { it.kind == "smaller_copy" && it.assetId != null }
                val elsewhere = res.alternatives.firstOrNull { it.kind == "copy_to_site_first" }?.site
                session.toast(
                    Toast.Kind.REFUSED,
                    "Couldn't cast to ${renderer.name}",
                    listOfNotNull(res.reasonHuman, elsewhere?.let { "A copy needs to reach $it first." })
                        .joinToString(" "),
                    action = smaller?.let { alt ->
                        val label =
                            alt.heightPx?.let { "Play the ${it}p copy instead" } ?: "Play a smaller copy instead"
                        ToastAction(label) { cast(alt.assetId!!, force) }
                    },
                )
            }
        }
    }
}
