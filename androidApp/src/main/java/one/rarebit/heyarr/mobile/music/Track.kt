package one.rarebit.heyarr.mobile.music

import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.mobile.playback.MediaMime

/**
 * The music reading of a work's files (heyarr-core `WorkAsset` off
 * `GET /api/v1/works/{id}/assets`, #429). heyarr sends NO track- or disc-number field,
 * so ordering and titles are derived from the filename — the same derivation the
 * desktop's `music/Track.kt` makes.
 */

/** A `primary`-role (or unroled) asset — the album track shape, not artwork/subtitles. */
val WorkAsset.isPrimaryRole: Boolean get() = role.isNullOrBlank() || role == "primary"

/** True when the asset looks like audio (by MIME, else filename extension). */
val WorkAsset.isAudio: Boolean get() = MediaMime.isAudio(mime, filename)

/** Display title: filename minus extension and a leading `NN - ` track prefix. Pure. */
fun trackTitle(track: WorkAsset): String {
    val name = track.filename?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: return track.id
    return name.replace(TRACK_PREFIX, "").ifBlank { name }
}

private val TRACK_PREFIX = Regex("^\\s*\\d{1,3}\\s*[-._ ]+\\s*")

object Tracks {
    /** The playable audio files of an album, in filename order (track numbers lead most filenames). Pure. */
    fun playable(assets: List<WorkAsset>): List<WorkAsset> =
        assets.filter { it.isPlayable && it.isPrimaryRole && it.isAudio }
            .sortedWith(compareBy({ it.filename?.lowercase() ?: "" }, { it.id }))

    /** Every audio track, playable or not, in the same order — what the track list shows. */
    fun all(assets: List<WorkAsset>): List<WorkAsset> = assets.filter { it.isPrimaryRole && it.isAudio }
        .sortedWith(compareBy({ it.filename?.lowercase() ?: "" }, { it.id }))
}
