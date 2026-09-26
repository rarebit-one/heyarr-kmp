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
    /** Text on the CTA pill: near-black, which meets AA against every CTA gradient. */
    val onAccent: Color = Color(0xFF080709),
    /**
     * Where the CTA gradient starts. Defaults to the accent; magenta is the one accent
     * whose raw value (`#C13BAD`) misses 4.5:1 with either black or white text, so its
     * pill starts from the hover tone; the raw accent remains for underlines and active-nav
     * marks, while focus rings use the brighter hover tone.
     */
    val ctaStart: Color? = null,
) {
    val ctaGradientStart: Color get() = ctaStart ?: accent

    /** A translucent accent for tints (chips, focus glows, progress tracks). */
    fun tint(alpha: Float = 0.16f): Color = accent.copy(alpha = alpha)
}

/**
 * The media → theme table. Exactly the brief's values: each accent swaps the CTA
 * gradient, active-nav highlight, progress bars and section underline. Focus rings use
 * the brighter hover tone so they stay visible on raised surfaces.
 * Unknown / non-media kinds fall back to a neutral slate so they never look like a
 * movie.
 */
object MediaThemes {
    @Suppress("MagicNumber") // The media accent table documents exact design-system colour values.
    private val MOVIE = MediaTheme(
        MediaType.MOVIE,
        Color(0xFF2E7D5B),
        Color(0xFF43A478),
        Color(0xFF7EE0B3),
        CardAspect.WIDE,
        ctaLabel = "Play",
        ctaSecondaryLabel = null,
        metadataKeys = listOf("runtime", "year", "rating", "genre"),
        ctaStart = Color(0xFF43A478),
    )
    private val SERIES = MediaTheme(
        MediaType.SERIES,
        Color(0xFF7C5CFF),
        Color(0xFF8F73FF),
        Color(0xFFA48BFF),
        CardAspect.WIDE,
        ctaLabel = "Play",
        ctaSecondaryLabel = "Next episode",
        metadataKeys = listOf("season", "episode", "air_status", "year"),
    )
    private val BOOK = MediaTheme(
        MediaType.BOOK, Color(0xFFE0A458), Color(0xFFE8B672), Color(0xFFF0C88C), CardAspect.POSTER,
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
        Color(0xFF2DB3A6),
        Color(0xFF43C3B7),
        Color(0xFF5CD3C8),
        CardAspect.SQUARE,
        ctaLabel = "Listen",
        ctaSecondaryLabel = null,
        metadataKeys = listOf("narrator", "duration", "author"),
    )
    private val PODCAST = MediaTheme(
        MediaType.PODCAST, Color(0xFFC13BAD), Color(0xFFD052BD), Color(0xFFDE6ACD), CardAspect.SQUARE,
        ctaLabel = "Play episode", ctaSecondaryLabel = null,
        metadataKeys = listOf(
            "show",
            "episode",
            "date",
            "duration",
        ),
        ctaStart = Color(0xFFD052BD),
    )
    private val MUSIC = MediaTheme(
        MediaType.MUSIC,
        Color(0xFFFF4D6D),
        Color(0xFFFF6682),
        Color(0xFFFF7F98),
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
