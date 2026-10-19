package one.rarebit.heyarr.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import one.rarebit.heyarr.core.theme.MediaType

/** One media type's accent ramp: the mark, its focus/hover step, and the CTA gradient's far end. */
@Immutable
data class MediaAccent(val accent: Color, val hover: Color, val gradientEnd: Color)

/**
 * Every colour [Tokens] names, as one swappable value. [Archive] is heyarr's own palette and
 * the default everywhere; the desktop may replace it with one derived from the desktop's
 * colours ([SystemColors], [Palette.derive]) so the app sits in an Omarchy or Plasma session
 * the way a native app does.
 *
 * The invariants every palette keeps, whichever source it came from and whether it is [dark]
 * or light (MediaThemeTest and PaletteTest pin them): text and muted text clear WCAG AA (4.5:1)
 * on every surface; the ground ([bgBase]) is the label colour on every accent fill and clears
 * 4.5:1 there too; media accents clear 3:1 as marks on the ground.
 */
@Immutable
data class Palette(
    val bgBase: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val border: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val textDisabled: Color,
    val readingPaper: Color,
    val readingInk: Color,
    val ochre: Color,
    val cream: Color,
    val danger: Color,
    val success: Color,
    val accent: Color,
    val accentHover: Color,
    val accentGradEnd: Color,
    val fern: Color,
    val slate: Color,
    val slateHover: Color,
    val slateGradEnd: Color,
    /** The media accents by type. Types missing here (feeds, unknown kinds) take the slate ramp. */
    val media: Map<MediaType, MediaAccent>,
    /** A dark ground under light text (Archive), rather than a light ground under dark text. */
    val dark: Boolean = true,
    /**
     * The dark sibling a light palette shows while media plays. Video is a dark stage whatever
     * the desktop's theme — the player draws its controls over black — so a light palette
     * carries the same theme's accents on a dark ground for it. Null means this palette is
     * already dark and is its own stage.
     */
    private val darkStage: Palette? = null,
) {
    /** The palette to show while media plays: this one if it is dark, else its dark sibling. */
    val stage: Palette get() = darkStage ?: this

    /** The accent ramp a [type] wears; non-media kinds fall back to slate so they never look like a movie. */
    fun mediaAccent(type: MediaType): MediaAccent = media[type] ?: MediaAccent(slate, slateHover, slateGradEnd)

    @Suppress("MagicNumber") // The palette is literal design values.
    companion object {
        /**
         * The Archive palette, taken from the brand-guide mockup rather than the brief's hex
         * values: the brief's tones read washed-out on screen (heyarr-kmp UI audit, 2026-10).
         * Deep forest surfaces let real artwork carry the colour; mint marks action, ochre
         * marks the archive.
         */
        val Archive = Palette(
            bgBase = Color(0xFF0A1410), // Forest Black
            surface1 = Color(0xFF111E18), // Raised Panel
            surface2 = Color(0xFF172620),
            surface3 = Color(0xFF1E2F27),
            border = Color(0xFF2C3D35),
            textPrimary = Color(0xFFE6EBD7), // Chalk
            textMuted = Color(0xFFA6B3A8),
            textDisabled = Color(0xFF6E7D73),
            readingPaper = Color(0xFFEDE8D8),
            readingInk = Color(0xFF1C211C),
            ochre = Color(0xFFF4B860),
            cream = Color(0xFFF2E3C4),
            danger = Color(0xFFEE8A78),
            success = Color(0xFF7EE0B3),
            accent = Color(0xFF7EE0B3), // Pale Mint
            accentHover = Color(0xFF9BEAC6),
            accentGradEnd = Color(0xFFBDF3DA),
            fern = Color(0xFF2E7D5B),
            slate = Color(0xFF9CAAA0),
            slateHover = Color(0xFFBCC8BE),
            slateGradEnd = Color(0xFFDCE3D9),
            media = mapOf(
                MediaType.MOVIE to MediaAccent(Color(0xFF7EE0B3), Color(0xFF9BEAC6), Color(0xFFBDF3DA)),
                MediaType.SERIES to MediaAccent(Color(0xFF8FD6C0), Color(0xFFAEE3D2), Color(0xFFCDEFE4)),
                MediaType.BOOK to MediaAccent(Color(0xFFF4B860), Color(0xFFF7CA85), Color(0xFFFADBA8)),
                MediaType.AUDIOBOOK to MediaAccent(Color(0xFF9CD9B0), Color(0xFFB6E6C6), Color(0xFFD0F0DB)),
                MediaType.PODCAST to MediaAccent(Color(0xFFC4D98C), Color(0xFFD3E4A6), Color(0xFFE2EEC2)),
                MediaType.MUSIC to MediaAccent(Color(0xFFA8DC8A), Color(0xFFBDE6A6), Color(0xFFD3EFC3)),
            ),
        )
    }
}

/**
 * The palette in force, app-wide. Snapshot state rather than a CompositionLocal on purpose:
 * [Tokens] is read in ~800 places, many of them plain (non-`@Composable`) code and default
 * arguments, and a CompositionLocal would make every one of those a composable read. A
 * snapshot read inside composition still subscribes, so swapping the palette recomposes
 * exactly the readers. Only the desktop's system-palette watcher writes it; the phone and
 * every preview stay on [Palette.Archive].
 */
object ActivePalette {
    var current: Palette by mutableStateOf(Palette.Archive)
}
