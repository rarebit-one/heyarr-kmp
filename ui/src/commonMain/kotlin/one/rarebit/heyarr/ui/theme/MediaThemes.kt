package one.rarebit.heyarr.ui.theme

import androidx.compose.ui.graphics.Color
import one.rarebit.heyarr.core.theme.MediaType

/** Card art aspect — width:height. */
enum class CardAspect(val ratio: Float) { POSTER(2f / 3f), SQUARE(1f), WIDE(16f / 9f) }

/**
 * Everything that changes when the media type changes: accent, art aspect, the primary
 * verb, the metadata a card leads with, and the glyph the placeholder art shows. Base
 * surfaces and text never change with it — see [Tokens].
 */
data class MediaTheme(
    val type: MediaType,
    val accent: Color,
    val accentHover: Color,
    val accentGradientEnd: Color,
    val aspect: CardAspect,
    val ctaLabel: String,
    val ctaSecondaryLabel: String?,
    /** Signature metadata keys, in order, read off the work's attributes. */
    val metadataKeys: List<String>,
    /** Whether the cover gets the book "spine" shadow. */
    val spineShadow: Boolean = false,
    /** Text on the CTA control: the ground (Forest Black under Archive), which every accent clears AA against. */
    val onAccent: Color = Tokens.bgBase,
    /**
     * Where the CTA gradient starts. A light palette tone can improve label contrast;
     * focus rings use [accentHover] so they stay visible on raised surfaces.
     */
    val ctaStart: Color? = null,
) {
    val ctaGradientStart: Color get() = ctaStart ?: accent

    /** A translucent accent for tints (chips, focus glows, progress tracks). */
    fun tint(alpha: Float = 0.16f): Color = accent.copy(alpha = alpha)
}

/**
 * The media → theme table. Media accents tint badges, glyphs and focus rings so a mixed
 * shelf stays legible; primary actions stay the palette's one accent (Pale Mint under
 * Archive) for every type (see `PrimaryButton`). Artwork is never tinted.
 * Unknown / non-media kinds fall back to a neutral slate so they never look like a
 * movie.
 *
 * The accents come from the [Palette] in force ([Palette.media]); only the shape of each
 * type (aspect, verbs, metadata) is fixed here. The table is rebuilt when the palette is
 * swapped and reused otherwise, so [MediaScope]'s equality check stays cheap.
 */
object MediaThemes {
    private class Shape(
        val type: MediaType,
        val aspect: CardAspect,
        val ctaLabel: String,
        val ctaSecondaryLabel: String? = null,
        val metadataKeys: List<String>,
        val spineShadow: Boolean = false,
    )

    private val SHAPES = listOf(
        Shape(MediaType.MOVIE, CardAspect.WIDE, "Play", metadataKeys = listOf("runtime", "year", "rating", "genre")),
        Shape(
            MediaType.SERIES,
            CardAspect.WIDE,
            "Play",
            ctaSecondaryLabel = "Next episode",
            metadataKeys = listOf("season", "episode", "air_status", "year"),
        ),
        Shape(
            MediaType.BOOK,
            CardAspect.POSTER,
            "Read",
            metadataKeys = listOf("author", "pages", "series"),
            spineShadow = true,
        ),
        Shape(
            MediaType.AUDIOBOOK,
            CardAspect.SQUARE,
            "Listen",
            metadataKeys = listOf("narrator", "duration", "author"),
        ),
        Shape(
            MediaType.PODCAST,
            CardAspect.SQUARE,
            "Play episode",
            metadataKeys = listOf("show", "episode", "date", "duration"),
        ),
        Shape(
            MediaType.MUSIC,
            CardAspect.SQUARE,
            "Play",
            metadataKeys = listOf("artist", "album", "track_count", "year"),
        ),
        Shape(MediaType.FEED, CardAspect.SQUARE, "Open", metadataKeys = listOf("source", "date")),
        Shape(MediaType.UNKNOWN, CardAspect.POSTER, "Open", metadataKeys = listOf("year")),
    )

    private class Built(val palette: Palette, val themes: Map<MediaType, MediaTheme>)

    // Benign race: two threads may both build for a new palette; the results are equal.
    private var built: Built? = null

    private fun build(palette: Palette): Map<MediaType, MediaTheme> = SHAPES.associate { s ->
        val a = palette.mediaAccent(s.type)
        s.type to MediaTheme(
            s.type, a.accent, a.hover, a.gradientEnd, s.aspect,
            ctaLabel = s.ctaLabel,
            ctaSecondaryLabel = s.ctaSecondaryLabel,
            metadataKeys = s.metadataKeys,
            spineShadow = s.spineShadow,
            onAccent = palette.bgBase,
        )
    }

    val all: Map<MediaType, MediaTheme>
        get() {
            val palette = ActivePalette.current
            built?.takeIf { it.palette === palette }?.let { return it.themes }
            return build(palette).also { built = Built(palette, it) }
        }

    fun of(type: MediaType): MediaTheme = all[type] ?: all.getValue(MediaType.UNKNOWN)
    fun of(raw: String?): MediaTheme = of(MediaType.from(raw))

    /** The app's default (no media in focus): the palette's own accent. */
    val default: MediaTheme get() = all.getValue(MediaType.MOVIE)
}
