package one.rarebit.heyarr.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.auth.GuestGate
import one.rarebit.heyarr.core.auth.Surface
import one.rarebit.heyarr.core.heyarr.DesiredItem
import one.rarebit.heyarr.core.library.Series
import one.rarebit.heyarr.core.state.Availability
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.heyarr.PlayResult
import one.rarebit.heyarr.desktop.library.Episode
import one.rarebit.heyarr.desktop.library.PrimaryAsset
import one.rarebit.heyarr.desktop.library.Season
import one.rarebit.heyarr.desktop.library.WorkDetail
import one.rarebit.heyarr.desktop.music.Track
import one.rarebit.heyarr.desktop.state.AppSession
import one.rarebit.heyarr.desktop.ui.Route
import one.rarebit.heyarr.desktop.ui.components.Artwork
import one.rarebit.heyarr.desktop.ui.components.Hero
import one.rarebit.heyarr.desktop.ui.components.HeroFacts
import one.rarebit.heyarr.desktop.ui.components.rememberCover
import one.rarebit.heyarr.ui.components.FilterChip
import one.rarebit.heyarr.ui.components.GetItButton
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.HeyarrIcons
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.Panel
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.components.SectionLabel
import one.rarebit.heyarr.ui.components.Skeleton
import one.rarebit.heyarr.ui.theme.CardAspect
import one.rarebit.heyarr.ui.theme.MediaThemes
import one.rarebit.heyarr.ui.theme.Tokens

/** The detail screen's header: the art, the facts that matter for this kind of work, and what to do next. */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
internal fun DetailHero(m: DetailModel, detail: WorkDetail, onGetIt: (String, String, MediaType) -> Unit) {
    val session = m.session
    val type = m.type
    val state = m.state
    val seasons = m.seasons
    val scope = rememberCoroutineScope()
    val cover by rememberCover(
        session,
        type,
        detail.work.title,
        detail.artworkPath,
        detail.work.year,
        detail.work.artist ?: detail.work.author,
    )
    val art = cover.bitmap
    val status = session.index.statusOf(detail.work.id)
    val asset = detail.primaryAsset
    // A feed's archive is its own; anything else is ready once there is a file to play.
    val availability = when (type) {
        MediaType.FEED, MediaType.PODCAST -> Availability.ofCatalogued(status)
        else -> Availability.of(status, playable = asset != null || seasons.any { it.held > 0 })
    }
    val work = detail.work
    val first = Series.firstPlayable(seasons)
    val actions = HeroActions(session, state, detail, type, scope)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IdentityBlock(
            title = work.title,
            type = type,
            meta = heroMeta(detail, type, seasons, state),
            artwork = art,
            availability = availability,
            synopsis = { SynopsisBlock(detail, type, seasons, state) },
            actions = {
                HeroPrimaryAction(actions, first, availability) { onGetIt(work.id, work.title, type) }
                HeroSecondaryActions(actions, first)
            },
        )
        notHeldNote(availability)?.let { Notice(it, tone = Tokens.slate) }
        CastPicker(session, state)
    }
}

/** The hero's meta line: the facts that matter for this kind of work. */
private fun heroMeta(detail: WorkDetail, type: MediaType, seasons: List<Season>, state: DetailState): List<String?> {
    val asset = detail.primaryAsset
    val work = detail.work
    val held = seasons.sumOf { it.held }
    return when (type) {
        MediaType.SERIES -> listOf(
            work.year?.toString(),
            if (seasons.isNotEmpty()) {
                "${seasons.count {
                    it.number != null && it.number != 0
                }} seasons"
            } else {
                null
            },
            if (state.assets !=
                null
            ) {
                "$held episodes"
            } else {
                null
            },
        )

        MediaType.MOVIE -> listOf(
            work.year?.toString(),
            asset?.let {
                Series.qualityTags(
                    Track(
                        "x",
                        "x",
                        filename = state.assets?.firstOrNull { t ->
                            t.blobHash ==
                                it.blobHash
                        }?.filename,
                    ),
                ).joinToString(" · ").ifBlank { null }
            },
            asset?.sizeBytes?.let { PrimaryAsset.formatBytes(it) },
        )

        MediaType.BOOK -> listOf(work.author, work.year?.toString(), asset?.mime?.substringAfter('/')?.uppercase())

        MediaType.MUSIC -> listOf(
            work.artist,
            work.year?.toString(),
            "${state.assets.orEmpty().count {
                it.isAudio && it.isPlayable
            }} tracks",
        )

        else -> listOf(work.year?.toString(), work.kind)
    }
}

/** What the hero's buttons do, bound to one work on the detail screen and that screen's hero scope. */
private class HeroActions(
    val session: AppSession,
    val state: DetailState,
    val detail: WorkDetail,
    val type: MediaType,
    val scope: CoroutineScope,
) {
    fun play(blobHash: String, label: String, assetId: String?) {
        playLocal(session, state, blobHash, label, scope, assetId = assetId, type = type)
    }

    fun openLocal() {
        val a = detail.primaryAsset ?: return
        scope.launch {
            state.busy = "open"
            val msg = session.io {
                session.openExternally.open(
                    session.config.baseUrl,
                    a.blobHash,
                    session.config.bearerToken.trim(),
                    null,
                    a.mime,
                    detail.work.title,
                )
            }.getOrNull()
            state.busy = null
            if (msg != null) session.toast(Toast.Kind.INFO, msg)
        }
    }

    fun openBook() {
        val a = detail.primaryAsset ?: return
        val fn = state.assets?.firstOrNull { it.blobHash == a.blobHash }?.filename
        state.openReader(
            Route.Reader(
                state.workId,
                a.assetId ?: a.blobHash,
                a.blobHash,
                detail.work.title,
                a.mime,
                fn,
                from = "Back",
            ),
        )
    }
}

/** The hero's one primary call to action: play, look for a copy, want it — or nothing, for a guest. */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun HeroPrimaryAction(actions: HeroActions, first: Episode?, availability: Availability, onGetIt: () -> Unit) {
    val session = actions.session
    val state = actions.state
    val type = actions.type
    val work = actions.detail.work
    val asset = actions.detail.primaryAsset
    val theme = MediaThemes.of(type)
    val idle = state.busy == null
    when {
        type == MediaType.SERIES && first != null -> PrimaryButton(
            "Play ${first.code ?: ""}".trim(),
            { actions.play(first.asset.blobHash!!, Series.playTitle(work, first), first.asset.id) },
            icon = HeyarrIcons.Play,
            enabled = idle,
        )

        // Nothing held: get it, follow its progress in Manage, or (a guest) no primary CTA at all.
        asset == null -> NothingHeldAction(actions, availability, onGetIt)

        type == MediaType.BOOK -> PrimaryButton(
            theme.ctaLabel,
            actions::openBook,
            icon = HeyarrIcons.Read,
            enabled = idle,
        )

        type == MediaType.FEED -> PrimaryButton(
            theme.ctaLabel,
            actions::openLocal,
            icon = HeyarrIcons.OpenExternal,
            enabled = idle,
        )

        else -> PrimaryButton(
            theme.ctaLabel,
            { actions.play(asset.blobHash, work.title, asset.assetId) },
            icon = HeyarrIcons.Play,
            enabled = idle,
        )
    }
}

/** The primary CTA when this work holds no file: Get it, or — when heyarr is already on it — its progress in Manage. */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun NothingHeldAction(actions: HeroActions, availability: Availability, onGetIt: () -> Unit) {
    when {
        availability.offersGetIt(actions.session.mode) ->
            GetItButton(onGetIt, primary = true, title = actions.detail.work.title)

        availability == Availability.ON_ITS_WAY || availability == Availability.NOT_AVAILABLE ->
            SecondaryButton("See progress", { actions.state.managing = true }, icon = HeyarrIcons.Forward)

        // Guest, nothing to play and nothing to ask for: no primary CTA.
        else -> Unit
    }
}

/** A plain line under the hero for a work with nothing to play yet; null when it plays, or Get it says enough. */
private fun notHeldNote(availability: Availability): String? = when (availability) {
    Availability.ON_ITS_WAY -> "Nothing to play yet — heyarr is getting a copy."
    Availability.NOT_AVAILABLE -> "Nothing to play yet — no acceptable copy has turned up. See progress for why."
    else -> null
}

/** The hero's secondary action: cast it to a renderer. */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun HeroSecondaryActions(actions: HeroActions, first: Episode?) {
    val session = actions.session
    val type = actions.type
    val asset = actions.detail.primaryAsset
    val castId = if (type == MediaType.SERIES) (first?.asset?.id) else asset?.assetId
    if (castId != null && type != MediaType.BOOK &&
        type != MediaType.FEED
    ) {
        SecondaryButton("Play on…", {
            toggleCast(session, actions.state, castId, actions.scope)
        }, icon = HeyarrIcons.Cast)
    }
}

@Composable
private fun CastPicker(session: AppSession, state: DetailState) {
    val scope = rememberCoroutineScope()
    val assetId = state.castAssetId ?: return
    Panel("Play on a renderer", trailing = { GhostButton("Close", { state.castAssetId = null }) }) {
        val rl = state.rendererList
        when {
            rl == null -> Skeleton(Modifier.fillMaxWidth().height(36.dp))

            rl.renderers.isEmpty() -> Text(
                "No renderers found. A device that is switched off will not be listed — that is not the same as it not existing.",
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.textMuted,
            )

            else -> {
                // Group renderers: client's site first (labelled "Here"), then others by site name.
                val clientSite = rl.clientSite
                val bySite = rl.renderers.groupBy { it.site ?: "" }
                val siteOrder = buildList {
                    if (clientSite != null && bySite.containsKey(clientSite)) add(clientSite)
                    bySite.keys.filter { it != clientSite }.sorted().forEach { add(it) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (site in siteOrder) {
                        val group = bySite[site] ?: continue
                        val siteLabel = if (site == clientSite) "Here" else site.ifBlank { "Other" }
                        if (siteOrder.size > 1 || siteLabel == "Here") {
                            Text(
                                siteLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = Tokens.textMuted,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (x in group) {
                                FilterChip(
                                    x.name,
                                    false,
                                    {
                                        if (x.r3Only) return@FilterChip
                                        val a = session.api ?: return@FilterChip
                                        state.castAssetId = null
                                        // On the SESSION scope, not this picker's: clearing castAssetId
                                        // removes CastPicker from composition, cancelling a picker-scoped
                                        // coroutine before the cast or toast completes. A codec refusal
                                        // offers "Cast anyway" (force_direct); forcing again is a plain
                                        // refusal, so no loop.
                                        fun cast(force: Boolean) {
                                            session.launch {
                                                state.busy = "cast"
                                                session.io {
                                                    a.playHere(assetId, x.name, x.udn, forceDirect = force)
                                                }.onSuccess { res ->
                                                    when (res) {
                                                        is PlayResult.Success -> session.toast(
                                                            Toast.Kind.SUCCESS,
                                                            "Playing on ${x.name}",
                                                            if (res.clientMustStayConnected) {
                                                                "Keep this app open while it plays."
                                                            } else {
                                                                "You can close the app."
                                                            },
                                                        )
                                                        is PlayResult.Refused -> if (force) {
                                                            session.refused(res)
                                                        } else {
                                                            session.castRefused(res, x.name) { cast(true) }
                                                        }
                                                        is PlayResult.Conflict -> {
                                                            val altAction = res.alternatives
                                                                .firstOrNull { it.kind == "smaller_copy" && it.assetId != null }
                                                                ?.let { alt ->
                                                                    one.rarebit.heyarr.core.state.ToastAction(
                                                                        "Play the ${alt.heightPx}p copy instead",
                                                                    ) {
                                                                        session.launch {
                                                                            session.io {
                                                                                a.playHere(
                                                                                    alt.assetId!!,
                                                                                    x.name,
                                                                                    x.udn,
                                                                                    forceDirect = force,
                                                                                )
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            session.toast(
                                                                Toast.Kind.REFUSED,
                                                                res.reasonHuman,
                                                                res.alternatives
                                                                    .firstOrNull { it.kind == "copy_to_site_first" }
                                                                    ?.let { "A copy needs to reach ${it.site} first." },
                                                                action = altAction,
                                                            )
                                                        }
                                                    }
                                                }
                                                state.busy = null
                                            }
                                        }
                                        cast(false)
                                    },
                                    icon = HeyarrIcons.Cast,
                                    enabled = !x.r3Only,
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
        }
        GhostButton("Search the network again", {
            scope.launch {
                session.api?.let { a ->
                    session.io { a.renderers(refresh = true) }.onSuccess { state.rendererList = it }
                }
            }
        })
    }
}

/**
 * A work's identity, first: its art at the art's own shape (a poster stays a poster), then the
 * eyebrow, the title at display size, the facts, the synopsis and what to do next. On a narrow
 * window the art stacks above the text instead of squeezing it.
 */
@Composable
@Suppress("FunctionNaming", "LongParameterList") // Compose naming; one slot per part of the block.
private fun IdentityBlock(
    title: String,
    type: MediaType,
    meta: List<String?>,
    artwork: ImageBitmap?,
    availability: Availability?,
    synopsis: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val aspect = MediaThemes.of(type).aspect
    val artWidth = when (aspect) {
        CardAspect.WIDE -> 420.dp
        CardAspect.POSTER -> 240.dp
        CardAspect.SQUARE -> 280.dp
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < artWidth + IDENTITY_TEXT_MIN
        val art: @Composable () -> Unit = {
            Artwork(
                artwork,
                type,
                Modifier.width(artWidth).aspectRatio(aspect.ratio).clip(RoundedCornerShape(Tokens.radiusCard)),
                contentDescription = "Artwork for $title",
                seed = title,
                contentScale = ContentScale.Crop,
            )
        }
        val text: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionLabel(type.label)
                Text(
                    title,
                    style = MaterialTheme.typography.displayLarge,
                    color = Tokens.textPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                HeroFacts(type, meta, availability)
                synopsis()
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    actions()
                }
            }
        }
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                art()
                text(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                art()
                text(Modifier.weight(1f))
            }
        }
    }
}

/** Below this width beside the art, the identity block stacks. */
private val IDENTITY_TEXT_MIN = 360.dp
