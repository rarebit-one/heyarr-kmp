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
@Composable
internal fun RendererGroups(list: RendererListResult, onPick: (Renderer) -> Unit) {
    val clientSite = list.clientSite
    val bySite = list.renderers.groupBy { it.site.orEmpty() }
    val siteOrder = buildList {
        if (clientSite != null && clientSite in bySite) add(clientSite)
        bySite.keys.filter { it != clientSite }.sorted().forEach { add(it) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

/**
 * Cast [assetId] to [renderer] and say how it went. Runs on the SESSION scope, not the
 * picker's: closing the picker removes it from composition, which would cancel a
 * picker-scoped coroutine — and its toast — before the cast finished.
 *
 * A codec refusal offers "Cast anyway" (force_direct); forcing again is shown as a plain
 * refusal, so no loop. A route refusal (409 with alternatives) offers the smaller copy the
 * server named, cast through this same path so its outcome is reported too.
 */
internal fun castTo(
    session: AppSession,
    api: HeyarrApi,
    assetId: String,
    renderer: Renderer,
    force: Boolean = false,
    before: suspend () -> Unit = {},
    after: () -> Unit = {},
) {
    val recast = { id: String, f: Boolean -> castTo(session, api, id, renderer, f, before, after) }
    session.launch {
        before()
        session.io { api.playHere(assetId, renderer.name, renderer.udn, forceDirect = force) }
            .onSuccess { reportCast(session, renderer, it, assetId, force, recast) }
        after()
    }
}

private fun reportCast(
    session: AppSession,
    renderer: Renderer,
    res: CastResult,
    assetId: String,
    force: Boolean,
    recast: (assetId: String, force: Boolean) -> Unit,
) {
    when (res) {
        is CastResult.Success -> session.toast(
            Toast.Kind.SUCCESS,
            "Playing on ${renderer.name}",
            if (res.clientMustStayConnected) "Keep this app open while it plays." else "You can close the app.",
        )

        is CastResult.Refused -> if (force) {
            session.refused(res)
        } else {
            session.castRefused(res, renderer.name) { recast(assetId, true) }
        }

        is CastResult.Conflict -> {
            val smaller = res.alternatives.firstOrNull { it.kind == "smaller_copy" && it.assetId != null }
            val elsewhere = res.alternatives.firstOrNull { it.kind == "copy_to_site_first" }?.site
            session.toast(
                Toast.Kind.REFUSED,
                "Couldn't cast to ${renderer.name}",
                listOfNotNull(res.reasonHuman, elsewhere?.let { "A copy needs to reach $it first." }).joinToString(" "),
                action = smaller?.let { alt ->
                    val label = alt.heightPx?.let { "Play the ${it}p copy instead" } ?: "Play a smaller copy instead"
                    ToastAction(label) { recast(alt.assetId!!, force) }
                },
            )
        }
    }
}
