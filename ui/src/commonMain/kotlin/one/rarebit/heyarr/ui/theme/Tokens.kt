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
object Tokens {
    // Surfaces — near-black, warm-neutral.
    val bgBase = Color(0xFF101815)
    val surface1 = Color(0xFF17221C)
    val surface2 = Color(0xFF1C2921)
    val surface3 = Color(0xFF25362B)
    val border = Color(0xFF3B5244)

    // Text ramp.
    val textPrimary = Color(0xFFE5E9DC)
    val textMuted = Color(0xFFADBDAF)
    val textDisabled = Color(0xFF829387)

    // Semantic.
    val ratingGold = Color(0xFFF5C518)
    val danger = Color(0xFFE5484D)
    val warning = Color(0xFFF5A524)
    val success = Color(0xFF21C063)

    // Default accent = SaintStream emerald (the Movie accent; also the app's neutral CTA).
    val accent = Color(0xFF00935E)
    val accentHover = Color(0xFF12A96E)
    val accentGradEnd = Color(0xFF21C063)

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
    val navWidthCompact: Dp = 84.dp
    val compactBreakpoint: Dp = 900.dp
    val posterWidth: Dp = 160.dp
    val posterWidthCompact: Dp = 132.dp
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
