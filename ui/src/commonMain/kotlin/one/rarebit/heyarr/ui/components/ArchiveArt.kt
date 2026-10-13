package one.rarebit.heyarr.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import one.rarebit.heyarr.ui.generated.resources.Res
import one.rarebit.heyarr.ui.generated.resources.archive_cover_poster_1
import one.rarebit.heyarr.ui.generated.resources.archive_cover_poster_2
import one.rarebit.heyarr.ui.generated.resources.archive_cover_poster_3
import one.rarebit.heyarr.ui.generated.resources.archive_cover_square_1
import one.rarebit.heyarr.ui.generated.resources.archive_cover_square_2
import one.rarebit.heyarr.ui.generated.resources.archive_cover_square_3
import one.rarebit.heyarr.ui.generated.resources.archive_cover_wide_1
import one.rarebit.heyarr.ui.generated.resources.archive_cover_wide_2
import one.rarebit.heyarr.ui.generated.resources.archive_cover_wide_3
import one.rarebit.heyarr.ui.generated.resources.archive_postcard_coast
import one.rarebit.heyarr.ui.generated.resources.archive_postcard_moon
import one.rarebit.heyarr.ui.generated.resources.archive_postcard_station
import one.rarebit.heyarr.ui.generated.resources.archive_postcard_tower
import one.rarebit.heyarr.ui.generated.resources.archive_vignette_empty
import one.rarebit.heyarr.ui.generated.resources.archive_vignette_search
import one.rarebit.heyarr.ui.generated.resources.archive_vignette_signal
import one.rarebit.heyarr.ui.theme.CardAspect
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.imageResource

/**
 * The Archive's generated pixel illustrations (`scripts/art/assets.toml` holds their prompts).
 * They are chrome, never content: a work's real artwork always wins, and this art is never
 * presented as belonging to a work. Every image is drawn with [FilterQuality.None] so its
 * pixels stay square at any size.
 */
internal object ArchiveArt {
    private val postcards = listOf(
        Res.drawable.archive_postcard_moon,
        Res.drawable.archive_postcard_station,
        Res.drawable.archive_postcard_tower,
        Res.drawable.archive_postcard_coast,
    )
    private val wide =
        listOf(Res.drawable.archive_cover_wide_1, Res.drawable.archive_cover_wide_2, Res.drawable.archive_cover_wide_3)
    private val poster = listOf(
        Res.drawable.archive_cover_poster_1,
        Res.drawable.archive_cover_poster_2,
        Res.drawable.archive_cover_poster_3,
    )
    private val square = listOf(
        Res.drawable.archive_cover_square_1,
        Res.drawable.archive_cover_square_2,
        Res.drawable.archive_cover_square_3,
    )

    /** An ambient postcard, the same one for the same [seed]. */
    fun postcard(seed: String): DrawableResource = postcards.pick(seed)

    /** A stand-in cover in the card's [aspect], the same one for the same work [seed]. */
    fun fallbackCover(aspect: CardAspect, seed: String): DrawableResource = when (aspect) {
        CardAspect.WIDE -> wide
        CardAspect.POSTER -> poster
        CardAspect.SQUARE -> square
    }.pick(seed)

    fun vignette(kind: ArchiveVignette): DrawableResource = when (kind) {
        ArchiveVignette.EMPTY -> Res.drawable.archive_vignette_empty
        ArchiveVignette.SIGNAL -> Res.drawable.archive_vignette_signal
        ArchiveVignette.SEARCH -> Res.drawable.archive_vignette_search
    }

    /** FNV-1a over the seed, so the choice is stable across runs, platforms and app versions. */
    private fun <T> List<T>.pick(seed: String): T {
        var hash = FNV_OFFSET
        for (c in seed) hash = (hash xor c.code) * FNV_PRIME
        return this[(hash.toUInt() % size.toUInt()).toInt()]
    }

    private const val FNV_OFFSET = -0x7ee3623b // 0x811C9DC5 as a signed Int
    private const val FNV_PRIME = 0x01000193
}

/** Which small scene an empty, failed or refused state shows. */
enum class ArchiveVignette { EMPTY, SIGNAL, SEARCH }

/** An ambient postcard (the same one for the same [seed]), cropped to fill. Decorative. */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
fun ArchivePostcard(seed: String, modifier: Modifier = Modifier) = ArchiveImage(ArchiveArt.postcard(seed), modifier)

/** A stand-in cover in [aspect] for a work with no artwork (the same one for the same [seed]). Decorative. */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
fun ArchiveFallbackCover(aspect: CardAspect, seed: String, modifier: Modifier = Modifier) =
    ArchiveImage(ArchiveArt.fallbackCover(aspect, seed), modifier)

/** The small scene for an empty, failed or refused state. Decorative. */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
fun ArchiveVignetteImage(kind: ArchiveVignette, modifier: Modifier = Modifier) =
    ArchiveImage(ArchiveArt.vignette(kind), modifier, ContentScale.Fit)

/** One Archive illustration, cropped to fill [modifier]'s bounds with crisp pixels. */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
internal fun ArchiveImage(
    art: DrawableResource,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    Image(
        imageResource(art),
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale,
        filterQuality = FilterQuality.None,
    )
}
