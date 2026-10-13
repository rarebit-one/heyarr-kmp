package one.rarebit.heyarr.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * THE design-token layer. Every colour, radius, spacing step and type size the UI uses
 * is named here. The Archive palette uses forest surfaces and compact geometry. Media-keyed accents live in [MediaTheme];
 * these are the constant base the accents sit on so a mixed-media screen reads as one
 * app.
 */
@Suppress("MagicNumber") // Design tokens are intentionally declared as literal colour values.
object Tokens {
    // Archive palette, taken from the brand-guide mockup rather than the brief's hex values: the
    // brief's tones read washed-out on screen (heyarr-kmp UI audit, 2026-10). Deep forest
    // surfaces let real artwork carry the colour; mint marks action, ochre marks the archive.
    val bgBase = Color(0xFF0A1410) // Forest Black
    val surface1 = Color(0xFF111E18) // Raised Panel
    val surface2 = Color(0xFF172620)
    val surface3 = Color(0xFF1E2F27)
    val border = Color(0xFF2C3D35)

    // Text ramp. Muted clears WCAG AA (≥ 4.5:1) on every surface; disabled is for inert labels only.
    val textPrimary = Color(0xFFE6EBD7) // Chalk
    val textMuted = Color(0xFFA6B3A8)
    val textDisabled = Color(0xFF6E7D73)

    // The reader gets its own paper and ink rather than borrowing the chrome colours.
    val readingPaper = Color(0xFFEDE8D8)
    val readingInk = Color(0xFF1C211C)

    // Brand warmth: Ochre for section markers and the archive's accents, Cream for the mark.
    val ochre = Color(0xFFF4B860)
    val cream = Color(0xFFF2E3C4)

    // Semantic.
    val ratingGold = ochre
    val danger = Color(0xFFEE8A78)
    val warning = ochre
    val success = Color(0xFF7EE0B3)

    // Default accent = Pale Mint: the one colour of primary action across every media type.
    val accent = Color(0xFF7EE0B3) // Pale Mint
    val accentHover = Color(0xFF9BEAC6)
    val accentGradEnd = Color(0xFFBDF3DA)

    /** Fern: the deep green for filled tracks, active fields and selected rows. */
    val fern = Color(0xFF2E7D5B)

    /** Neutral slate accent for unknown / non-media types (documents, feeds). */
    val slate = Color(0xFF9CAAA0)
    val slateHover = Color(0xFFBCC8BE)
    val slateGradEnd = Color(0xFFDCE3D9)

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
