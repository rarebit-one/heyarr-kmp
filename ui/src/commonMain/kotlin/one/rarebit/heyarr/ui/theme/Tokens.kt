package one.rarebit.heyarr.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * THE design-token layer. Every colour, radius, spacing step and type size the UI uses
 * is named here. Colours come from the [Palette] in force (Archive's forest surfaces by default);
 * geometry is constant. Media-keyed accents live in [MediaTheme];
 * these are the constant base the accents sit on so a mixed-media screen reads as one
 * app.
 */
@Suppress("MagicNumber") // Geometry and type tokens are intentionally literal values.
object Tokens {
    // Colours read the palette in force ([ActivePalette]): heyarr's own Archive palette by
    // default, or one derived from the desktop's colours. Getters, not vals, so a palette swap
    // reaches every reader; each read inside composition subscribes to the swap. The values
    // and the reasoning behind them live with [Palette.Archive].
    private val p: Palette get() = ActivePalette.current

    /** Whether the palette in force is dark (Archive, a dark desktop theme) or light. */
    val dark: Boolean get() = p.dark

    val bgBase: Color get() = p.bgBase // Forest Black
    val surface1: Color get() = p.surface1 // Raised Panel
    val surface2: Color get() = p.surface2
    val surface3: Color get() = p.surface3
    val border: Color get() = p.border

    // Text ramp. Muted clears WCAG AA (≥ 4.5:1) on every surface; disabled is for inert labels only.
    val textPrimary: Color get() = p.textPrimary // Chalk
    val textMuted: Color get() = p.textMuted
    val textDisabled: Color get() = p.textDisabled

    // The reader gets its own paper and ink rather than borrowing the chrome colours.
    val readingPaper: Color get() = p.readingPaper
    val readingInk: Color get() = p.readingInk

    // Brand warmth: Ochre for section markers and the archive's accents, Cream for the mark.
    val ochre: Color get() = p.ochre
    val cream: Color get() = p.cream

    // Semantic.
    val ratingGold: Color get() = ochre
    val danger: Color get() = p.danger
    val warning: Color get() = ochre
    val success: Color get() = p.success

    // Default accent = Pale Mint: the one colour of primary action across every media type.
    val accent: Color get() = p.accent // Pale Mint
    val accentHover: Color get() = p.accentHover
    val accentGradEnd: Color get() = p.accentGradEnd

    /** Fern: the deep green for filled tracks, active fields and selected rows. */
    val fern: Color get() = p.fern

    /** Neutral slate accent for unknown / non-media types (documents, feeds). */
    val slate: Color get() = p.slate
    val slateHover: Color get() = p.slateHover
    val slateGradEnd: Color get() = p.slateGradEnd

    // Surfaces stay square; controls take a slight radius, as the brand guide's buttons do.
    val radiusCard: Dp = 0.dp
    val radiusButton: Dp = 3.dp
    val radiusInput: Dp = 3.dp
    val radiusPill: Dp = 3.dp
    val radiusChip: Dp = 2.dp

    // Spacing (4px base).
    val s1: Dp = 4.dp
    val s2: Dp = 8.dp
    val s3: Dp = 12.dp
    val s4: Dp = 16.dp
    val s6: Dp = 24.dp
    val s8: Dp = 32.dp
    val s12: Dp = 48.dp
    val gridGap: Dp = 20.dp

    // Elevation hairline.
    val hairline: Dp = 1.dp

    // Layout.
    val navWidth: Dp = 76.dp
    val compactBreakpoint: Dp = 900.dp
    val posterWidth: Dp = 160.dp
    val squareWidth: Dp = 168.dp

    /** Compact glyph scale, drawn in dp so the same marks work on desktop and touch. */
    val pixel: Dp = 3.dp

    // Desktop type scale (Rubik). Display is for hero and detail titles only; the steps below
    // are far enough apart that type carries the hierarchy instead of boxes.
    object Type {
        val display = 48.sp
        val h1 = 32.sp
        val h2 = 24.sp
        val h3 = 20.sp
        val h4 = 17.sp
        val h5 = 15.sp
        val h6 = 14.sp
        val bodyXl = 18.sp
        val bodyL = 15.sp
        val bodyM = 14.sp
        val bodyS = 12.sp
        val bodyXs = 11.sp

        /** Tracking for uppercase eyebrows, section labels and rail captions. */
        val labelTracking = 1.8.sp
    }
}
