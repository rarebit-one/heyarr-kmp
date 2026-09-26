package one.rarebit.heyarr.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.rarebit.heyarr.core.heyarr.Candidate
import one.rarebit.heyarr.core.heyarr.DesiredItem
import one.rarebit.heyarr.core.library.Series
import one.rarebit.heyarr.core.library.Variants
import one.rarebit.heyarr.core.mcp.ExternalId
import one.rarebit.heyarr.core.mcp.Renderer
import one.rarebit.heyarr.core.mcp.Replica
import one.rarebit.heyarr.core.mcp.Satisfaction
import one.rarebit.heyarr.core.state.ExternalEpisode
import one.rarebit.heyarr.core.state.ExternalMeta
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.state.MetaKey
import one.rarebit.heyarr.core.state.Toast
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.heyarr.HeyarrApi
import one.rarebit.heyarr.mobile.heyarr.McpResult
import one.rarebit.heyarr.mobile.library.Season
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.mobile.music.Tracks
import one.rarebit.heyarr.mobile.nav.Route
import one.rarebit.heyarr.mobile.personalstate.ItemRef
import one.rarebit.heyarr.mobile.playback.QueueEntry
import one.rarebit.heyarr.mobile.reader.ReaderFormat
import one.rarebit.heyarr.mobile.search.FollowedItem
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.mobile.ui.components.Hero
import one.rarebit.heyarr.mobile.ui.components.HeroSkeleton
import one.rarebit.heyarr.mobile.ui.components.rememberCover
import one.rarebit.heyarr.ui.components.ErrorState
import one.rarebit.heyarr.ui.components.FilterChip
import one.rarebit.heyarr.ui.components.GhostButton
import one.rarebit.heyarr.ui.components.IconButtonRound
import one.rarebit.heyarr.ui.components.Notice
import one.rarebit.heyarr.ui.components.Panel
import one.rarebit.heyarr.ui.components.PrimaryButton
import one.rarebit.heyarr.ui.components.SecondaryButton
import one.rarebit.heyarr.ui.components.Skeleton
import one.rarebit.heyarr.ui.components.focusRing
import one.rarebit.heyarr.ui.theme.LocalMediaTheme
import one.rarebit.heyarr.ui.theme.MediaScope
import one.rarebit.heyarr.ui.theme.MediaThemes

/**
 * What the detail hero knows about its work, derived once per composition and read by its
 * buttons: the held bytes, the first playable episode, the audio
 * tracks and the readable file.
 */
private class HeroWork(
    val session: AppSession,
    val work: Work,
    val type: MediaType,
    val state: DetailState,
    val seasons: List<Season>,
    val art: String?,
) {
    val hash = work.blobHash
    val first = Series.firstPlayable(seasons)
    val status = session.index.statusOf(work.id)
    val readable = state.assets.orEmpty().firstOrNull {
        it.isPlayable &&
            ReaderFormat.of(it.mime, it.filename)?.let { f -> f != ReaderFormat.AUDIOBOOK } == true
    }
    val audioTracks = Tracks.playable(state.assets.orEmpty())

    /** Music or an audiobook with playable tracks: the hero queues them all. */
    val playsAlbum = (type == MediaType.MUSIC || type == MediaType.AUDIOBOOK) && audioTracks.isNotEmpty()

    /** A book with no readable file but an audiobook track: the hero listens instead. */
    val listensToBook = type == MediaType.BOOK && audioTracks.isNotEmpty()

    /** A feed or podcast episode — opened rather than played. */
    val isFeed = type == MediaType.FEED || type == MediaType.PODCAST

    /** The file "Play on…" would cast: a series' first playable episode, else the primary file. */
    val castId = if (type == MediaType.SERIES) first?.asset?.id else work.primaryAssetId

    /** A single-file work with no bytes held — the hero says why there is nothing to play. */
    val nothingToPlay = hash == null && type != MediaType.SERIES && type != MediaType.FEED &&
        type != MediaType.PODCAST && type != MediaType.MUSIC

    /** Play the series' first playable episode. */
    fun playFirst(play: DetailPlayback) {
        val first = first ?: return
        play.playVideo(
            work, first.asset.id, first.asset.blobHash!!,
            first.asset.mime ?: work.mime,
            Series.playTitle(
                work,
                first,
            ),
            null, queueOf(work, seasons, session), art, first.subtitles,
        )
    }

    /** Play the work's primary file from the start. */
    fun playPrimary(play: DetailPlayback) {
        play.playVideo(
            work, work.primaryAssetId ?: hash!!, hash!!, work.mime, work.title, null, emptyList(), art, emptyList(),
        )
    }
}

/** The hero's meta line: what matters at a glance for this [type] of work. */
private fun heroMeta(work: Work, type: MediaType, seasons: List<Season>, state: DetailState): List<String?> {
    val held = seasons.sumOf { it.held }
    val primaryFile = state.assets?.firstOrNull { it.blobHash == work.blobHash }
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
            primaryFile?.let {
                Series.qualityTags(it).joinToString(" · ").ifBlank { null }
            },
            primaryFile?.sizeBytes?.let { WorkAsset.formatBytes(it) },
        )

        MediaType.BOOK -> listOf(work.author, work.year?.toString(), work.mime?.substringAfter('/')?.uppercase())

        MediaType.MUSIC, MediaType.AUDIOBOOK -> listOf(
            work.artist ?: work.author,
            work.year?.toString(),
            "${Tracks.playable(state.assets.orEmpty()).size} tracks",
        )

        else -> listOf(work.year?.toString(), work.kind)
    }
}

@Composable
internal fun DetailHero(
    session: AppSession,
    work: Work,
    type: MediaType,
    wants: List<DesiredItem>,
    state: DetailState,
    seasons: List<Season>,
    art: String?,
    play: DetailPlayback,
    onWant: (String, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val h = HeroWork(session, work, type, state, seasons, art)
    val hash = h.hash

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Hero(
            title = work.title,
            type = type,
            meta = heroMeta(work, type, seasons, state),
            artwork = art,
            status = h.status,
            height = 300.dp,
            primary = { HeroPrimary(h, wants, play, onWant, scope) },
            secondary = {
                val castId = h.castId
                if (castId != null && type != MediaType.BOOK &&
                    type != MediaType.FEED
                ) {
                    SecondaryButton("Play on…", {
                        toggleCast(session, state, castId, scope)
                    }, icon = Icons.Rounded.Cast)
                }
                if (h.status == LibraryStatus.NOT_TRACKED &&
                    (hash != null || type == MediaType.SERIES)
                ) {
                    SecondaryButton("Want", { onWant(work.id, work.title) }, icon = Icons.Rounded.Add)
                }
            },
        )
        if (h.nothingToPlay) {
            val explanation = if (wants.isEmpty()) {
                "not wanted, so nothing is looking for a copy."
            } else {
                "heyarr is looking. Manage → Releases shows what the indexers found."
            }
            Notice(
                "Nothing to play yet — $explanation",
            )
        }
        CastPicker(session, state)
    }
}

/** The hero's one primary action: play, listen or read what is held — else look for it, or want it. */
@Composable
private fun HeroPrimary(
    h: HeroWork,
    wants: List<DesiredItem>,
    play: DetailPlayback,
    onWant: (String, String) -> Unit,
    scope: CoroutineScope,
) {
    val type = h.type
    val work = h.work
    val theme = MediaThemes.of(type)
    val idle = h.state.busy == null
    when {
        type == MediaType.SERIES && h.first != null ->
            PrimaryButton(
                "Play ${h.first.code ?: ""}".trim(),
                { h.playFirst(play) },
                icon = Icons.Rounded.PlayArrow,
                enabled = idle,
            )

        h.playsAlbum ->
            PrimaryButton(
                theme.ctaLabel,
                { play.playAudio(work, h.audioTracks, 0) },
                icon = albumIcon(type),
            )

        type == MediaType.BOOK && h.readable != null ->
            PrimaryButton("Read", { play.read(work, h.readable) }, icon = Icons.Rounded.MenuBook)

        h.listensToBook ->
            PrimaryButton("Listen", {
                play.playAudio(work, h.audioTracks, 0)
            }, icon = Icons.Rounded.Headphones)

        h.hash == null && wants.isNotEmpty() -> PrimaryButton("Look for it", {
            lookForIt(h.session, h.state, wants.first(), scope)
        }, icon = Icons.Rounded.Search, enabled = idle)

        h.hash == null -> PrimaryButton(
            "Want",
            {
                onWant(work.id, work.title)
            },
            icon = Icons.Rounded.Add,
            enabled =
            h.status == LibraryStatus.NOT_TRACKED,
        )

        // Reached only past the `hash == null` branches above.
        h.isFeed ->
            PrimaryButton(theme.ctaLabel, { h.playPrimary(play) }, icon = Icons.Rounded.OpenInNew, enabled = idle)

        else -> PrimaryButton(theme.ctaLabel, { h.playPrimary(play) }, icon = Icons.Rounded.PlayArrow, enabled = idle)
    }
}

/** An audiobook listens; music plays. */
private fun albumIcon(type: MediaType): ImageVector = if (type ==
    MediaType.AUDIOBOOK
) {
    Icons.Rounded.Headphones
} else {
    Icons.Rounded.PlayArrow
}

/** Nothing held but a want exists: ask the indexers now rather than on the schedule. */
private fun lookForIt(session: AppSession, state: DetailState, w: DesiredItem, scope: CoroutineScope) {
    scope.launch {
        state.busy = "search"
        session.io { session.api.searchReleases(w.id) }.onSuccess { r ->
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
