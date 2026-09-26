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
    // The forest palette keeps the chrome quiet so artwork and the reading surface lead.
    val bgBase = Color(0xFF0A1410)
    val surface1 = Color(0xFF111E18)
    val surface2 = Color(0xFF17271F)
    val surface3 = Color(0xFF20362B)
    val border = Color(0xFF2E493A)

    // Text ramp.
    val textPrimary = Color(0xFFE6EBD7)
    val textMuted = Color(0xFFB1C0B1)
    val textDisabled = Color(0xFF778A7C)

    // The reader gets its own paper and ink rather than borrowing the chrome colours.
    val readingPaper = Color(0xFFF0EBDD)
    val readingInk = Color(0xFF20231E)

    // Semantic.
    val ratingGold = Color(0xFFF4B860)
    val danger = Color(0xFFE56E66)
    val warning = Color(0xFFF4B860)
    val success = Color(0xFF7EE0B3)

    // Default accent = the forest fern (Movie accent; also the app's neutral CTA).
    val accent = Color(0xFF2E7D5B)
    val accentHover = Color(0xFF43A478)
    val accentGradEnd = Color(0xFF7EE0B3)

    /** Neutral slate accent for unknown / non-media types (documents, feeds). */
    val slate = Color(0xFF7A8598)
    val slateHover = Color(0xFF8C97AA)
    val slateGradEnd = Color(0xFF9AA5B8)

    // Archive surfaces and controls have square corners.
    val radiusCard: Dp = 0.dp
    val radiusButton: Dp = 0.dp
    val radiusInput: Dp = 0.dp
    val radiusPill: Dp = 0.dp
    val radiusChip: Dp = 0.dp

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
    val navWidth: Dp = 84.dp
    val compactBreakpoint: Dp = 900.dp
    val posterWidth: Dp = 160.dp
    val squareWidth: Dp = 168.dp

    // Compact desktop type scale (Rubik).
    object Type {
        val h1 = 28.sp
        val h2 = 22.sp
        val h3 = 22.sp
        val h4 = 18.sp
        val h5 = 16.sp
        val h6 = 14.sp
        val bodyXl = 18.sp
        val bodyL = 14.sp
        val bodyM = 14.sp
        val bodyS = 12.sp
        val bodyXs = 11.sp
    }
}
