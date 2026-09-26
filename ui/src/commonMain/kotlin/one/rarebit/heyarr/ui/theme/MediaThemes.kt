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
    /** Text on the CTA control: Forest Black, with contrast against the light palette accents. */
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
 * The media → theme table reuses the Archive palette. Media accents distinguish actions
 * without importing unrelated neon colors into the shared chrome. Artwork is never tinted.
 * Unknown / non-media kinds fall back to a neutral slate so they never look like a
 * movie.
 */
@Suppress("MagicNumber") // The media accent table documents exact design-system colour values.
object MediaThemes {
    private val MOVIE = MediaTheme(
        MediaType.MOVIE,
        Color(0xFF599C7B),
        Color(0xFF78B493),
        Color(0xFFB8D8C7),
        CardAspect.WIDE,
        ctaLabel = "Play",
        ctaSecondaryLabel = null,
        metadataKeys = listOf("runtime", "year", "rating", "genre"),
        ctaStart = Color(0xFF78B493),
    )
    private val SERIES = MediaTheme(
        MediaType.SERIES,
        Color(0xFF78A58B),
        Color(0xFF97BFA7),
        Color(0xFFB8D8C7),
        CardAspect.WIDE,
        ctaLabel = "Play",
        ctaSecondaryLabel = "Next episode",
        metadataKeys = listOf("season", "episode", "air_status", "year"),
    )
    private val BOOK = MediaTheme(
        MediaType.BOOK, Color(0xFFC7B66D), Color(0xFFD7C77E), Color(0xFFE6D99D), CardAspect.POSTER,
        ctaLabel = "Read", ctaSecondaryLabel = null,
        metadataKeys = listOf(
            "author",
            "pages",
            "series",
        ),
        spineShadow = true,
    )
    private val AUDIOBOOK = MediaTheme(
        MediaType.AUDIOBOOK,
        Color(0xFF6FA58C),
        Color(0xFF8FBEA6),
        Color(0xFFB8D8C7),
        CardAspect.SQUARE,
        ctaLabel = "Listen",
        ctaSecondaryLabel = null,
        metadataKeys = listOf("narrator", "duration", "author"),
    )
    private val PODCAST = MediaTheme(
        MediaType.PODCAST,
        Color(0xFF8DAE8D),
        Color(0xFFAFC9A4),
        Color(0xFFD1DDB5),
        CardAspect.SQUARE,
        ctaLabel = "Play episode",
        ctaSecondaryLabel = null,
        metadataKeys = listOf(
            "show",
            "episode",
            "date",
            "duration",
        ),
    )
    private val MUSIC = MediaTheme(
        MediaType.MUSIC,
        Color(0xFF83A876),
        Color(0xFFA1C18C),
        Color(0xFFC1D8A8),
        CardAspect.SQUARE,
        ctaLabel = "Play",
        ctaSecondaryLabel = null,
        metadataKeys = listOf("artist", "album", "track_count", "year"),
    )
    private val FEED = MediaTheme(
        MediaType.FEED,
        Tokens.slate,
        Tokens.slateHover,
        Tokens.slateGradEnd,
        CardAspect.SQUARE,
        ctaLabel = "Open",
        ctaSecondaryLabel = null,
        metadataKeys = listOf("source", "date"),
    )
    private val UNKNOWN = MediaTheme(
        MediaType.UNKNOWN,
        Tokens.slate,
        Tokens.slateHover,
        Tokens.slateGradEnd,
        CardAspect.POSTER,
        ctaLabel = "Open",
        ctaSecondaryLabel = null,
        metadataKeys = listOf("year"),
    )

    val all: Map<MediaType, MediaTheme> = mapOf(
        MediaType.MOVIE to MOVIE,
        MediaType.SERIES to SERIES,
        MediaType.BOOK to BOOK,
        MediaType.AUDIOBOOK to AUDIOBOOK,
        MediaType.PODCAST to PODCAST,
        MediaType.MUSIC to MUSIC,
        MediaType.FEED to FEED,
        MediaType.UNKNOWN to UNKNOWN,
    )

    fun of(type: MediaType): MediaTheme = all[type] ?: UNKNOWN
    fun of(raw: String?): MediaTheme = of(MediaType.from(raw))

    /** The app's default (no media in focus): the emerald. */
    val default: MediaTheme get() = MOVIE
}
