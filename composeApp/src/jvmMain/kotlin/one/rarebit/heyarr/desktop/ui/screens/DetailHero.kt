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
import one.rarebit.heyarr.core.mcp.Renderer
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.heyarr.McpResult
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
internal fun DetailHero(
    session: AppSession,
    detail: WorkDetail,
    type: MediaType,
    wants: List<DesiredItem>,
    state: DetailState,
    seasons: List<Season>,
    onWant: (String, String, MediaType) -> Unit,
) {
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
    val work = detail.work
    val first = Series.firstPlayable(seasons)
    val actions = HeroActions(session, state, detail, type, scope)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IdentityBlock(
            title = work.title,
            type = type,
            meta = heroMeta(detail, type, seasons, state),
            artwork = art,
            status = status,
            synopsis = { SynopsisBlock(detail, type, seasons, state) },
            actions = {
                HeroPrimaryAction(actions, first, wants) { onWant(work.id, work.title, type) }
                HeroSecondaryActions(actions, first) { onWant(work.id, work.title, type) }
            },
        )
        if (asset == null && type != MediaType.SERIES && type != MediaType.FEED &&
            type != MediaType.PODCAST
        ) {
            val wantHint = if (wants.isEmpty()) {
                "not wanted, so nothing is looking for a copy."
            } else {
                "heyarr is looking. Releases shows what the indexers found."
            }
            Notice(
                "Nothing to play yet — $wantHint",
            )
        }
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
                "$held episodes held"
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

    fun lookForIt(wants: List<DesiredItem>) {
        val a = session.api ?: return
        val w = wants.first()
        scope.launch {
            state.busy = "search"
            session.io { a.searchReleases(w.id) }.onSuccess { r ->
                when (r) {
                    is McpResult.Ok -> session.toast(
                        Toast.Kind.INFO,
                        "Search queued",
                        "An indexer can take thirty seconds to answer; open Manage → Releases in a moment.",
                    )

                    is McpResult.Refused -> session.refused(r)
                }
            }
            state.busy = null
        }
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
private fun HeroPrimaryAction(actions: HeroActions, first: Episode?, wants: List<DesiredItem>, onWant: () -> Unit) {
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

        // Nothing held: look for a copy, want it, or (a guest) no primary CTA at all.
        asset == null -> NothingHeldAction(actions, wants, onWant)

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

/** The primary CTA when this work holds no file: look for a copy if it is wanted, otherwise offer Want. */
@Composable
private fun NothingHeldAction(actions: HeroActions, wants: List<DesiredItem>, onWant: () -> Unit) {
    val session = actions.session
    when {
        wants.isNotEmpty() -> PrimaryButton(
            "Look for it",
            { actions.lookForIt(wants) },
            icon = HeyarrIcons.Search,
            enabled = actions.state.busy == null,
        )

        // Wanting is an enrolled-only surface; a guest never sees the Want CTA (GuestGate is the
        // single source of truth) — they can still browse and play whatever holds a file.
        GuestGate.allows(session.mode, Surface.WANT) -> PrimaryButton(
            "Want",
            onWant,
            icon = HeyarrIcons.Add,
            enabled =
            session.index.statusOf(actions.detail.work.id) == LibraryStatus.NOT_TRACKED,
        )

        // Guest, nothing to play and no want affordance to offer: no primary CTA.
        else -> Unit
    }
}

/** The hero's secondary actions: cast it to a renderer, and Want it when nothing measures it yet. */
@Composable
private fun HeroSecondaryActions(actions: HeroActions, first: Episode?, onWant: () -> Unit) {
    val session = actions.session
    val type = actions.type
    val asset = actions.detail.primaryAsset
    val status = session.index.statusOf(actions.detail.work.id)
    val canWant = GuestGate.allows(session.mode, Surface.WANT)
    val castId = if (type == MediaType.SERIES) (first?.asset?.id) else asset?.assetId
    if (castId != null && type != MediaType.BOOK &&
        type != MediaType.FEED
    ) {
        SecondaryButton("Play on…", {
            toggleCast(session, actions.state, castId, actions.scope)
        }, icon = HeyarrIcons.Cast)
    }
    if (status == LibraryStatus.NOT_TRACKED && canWant &&
        (asset != null || type == MediaType.SERIES)
    ) {
        SecondaryButton("Want", onWant, icon = HeyarrIcons.Add)
    }
}

@Composable
private fun CastPicker(session: AppSession, state: DetailState) {
    val scope = rememberCoroutineScope()
    val assetId = state.castAssetId ?: return
    fun playOn(renderer: Renderer) {
        val api = session.api ?: return
        state.castAssetId = null
        // Session scope, not this picker's: clearing castAssetId (above) removes the picker
        // from composition, and a picker-scoped coroutine would be cancelled with it before the
        // cast — and its toast — completed. A codec refusal offers "Cast anyway" (force_direct);
        // forcing again is a plain refusal, so no loop.
        fun cast(force: Boolean) {
            session.launch {
                state.busy = "cast"
                session.io { api.playHere(assetId, renderer.name, renderer.udn, forceDirect = force) }
                    .onSuccess { r ->
                        when (r) {
                            is McpResult.Ok -> session.toast(Toast.Kind.SUCCESS, "Playing on ${renderer.name}")

                            is McpResult.Refused -> if (force) {
                                session.refused(
                                    r,
                                )
                            } else {
                                session.castRefused(r, renderer.name) {
                                    cast(true)
                                }
                            }
                        }
                    }
                state.busy = null
            }
        }
        cast(false)
    }
    Panel("Play on a renderer", trailing = { GhostButton("Close", { state.castAssetId = null }) }) {
        val r = state.renderers
        when {
            r == null -> Skeleton(Modifier.fillMaxWidth().height(36.dp))

            r.isEmpty() -> Text(
                "No renderers found. A device that is switched off will not be listed — that is not the same as it not existing.",
                style = MaterialTheme.typography.bodyMedium,
                color = Tokens.textMuted,
            )

            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (x in r) FilterChip(x.name, false, { playOn(x) }, icon = HeyarrIcons.Cast)
            }
        }
        GhostButton("Search the network again", {
            scope.launch {
                session.api?.let { a ->
                    session.io { a.renderers(refresh = true) }.onSuccess {
                        state.renderers =
                            it
                    }
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
    status: LibraryStatus?,
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
                HeroFacts(type, meta, status)
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
