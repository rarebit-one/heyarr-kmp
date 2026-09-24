package one.rarebit.heyarr.mobile.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.rarebit.heyarr.ui.theme.MediaTheme

/**
 * THE design-token layer — ported verbatim from heyarr-desktop's `theme/Tokens.kt`.
 * Every colour, radius, spacing step and type size the UI uses is named here and
 * nowhere else. Media-keyed accents live in [MediaTheme]; these are the constant base
 * the accents sit on so a mixed-media screen reads as one app.
 */
object Tokens {
    // Surfaces — near-black, warm-neutral.
    val bgBase = one.rarebit.heyarr.ui.theme.Tokens.bgBase
    val surface1 = one.rarebit.heyarr.ui.theme.Tokens.surface1
    val surface2 = one.rarebit.heyarr.ui.theme.Tokens.surface2
    val surface3 = one.rarebit.heyarr.ui.theme.Tokens.surface3
    val border = one.rarebit.heyarr.ui.theme.Tokens.border

    // Text ramp.
    val textPrimary = one.rarebit.heyarr.ui.theme.Tokens.textPrimary
    val textMuted = one.rarebit.heyarr.ui.theme.Tokens.textMuted
    val textDisabled = one.rarebit.heyarr.ui.theme.Tokens.textDisabled

    // Semantic.
    val ratingGold = Color(0xFFF5C518)
    val danger = Color(0xFFE5484D)
    val warning = Color(0xFFF5A524)
    val success = Color(0xFF21C063)

    // Default accent = the Movie emerald; also the app's neutral CTA.
    val accent = Color(0xFF00935E)
    val accentHover = Color(0xFF12A96E)
    val accentGradEnd = Color(0xFF21C063)

    /** Neutral slate accent for unknown / non-media types (documents, feeds). */
    val slate = Color(0xFF7A8598)
    val slateHover = Color(0xFF8C97AA)
    val slateGradEnd = Color(0xFF9AA5B8)

    // Radii.
    val radiusCard: Dp = one.rarebit.heyarr.ui.theme.Tokens.radiusCard
    val radiusButton: Dp = one.rarebit.heyarr.ui.theme.Tokens.radiusButton
    val radiusInput: Dp = one.rarebit.heyarr.ui.theme.Tokens.radiusInput
    val radiusPill: Dp = one.rarebit.heyarr.ui.theme.Tokens.radiusPill
    val radiusChip: Dp = one.rarebit.heyarr.ui.theme.Tokens.radiusChip

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

    // Layout. The phone shows a bottom bar; from [railBreakpoint] up (a tablet, a
    // foldable open, a phone in landscape) the destinations move to a left rail.
    val navWidth: Dp = one.rarebit.heyarr.ui.theme.Tokens.navWidth
    val railBreakpoint: Dp = 600.dp
    val posterWidth: Dp = 132.dp
    val squareWidth: Dp = 148.dp
    val screenPadding: Dp = 16.dp

    // Type scale (Montserrat display / Inter body).
    object Type {
        val h1 = 26.sp
        val h2 = 22.sp
        val h3 = 20.sp
        val h4 = 18.sp
        val h5 = 16.sp
        val h6 = 14.sp
        val bodyXl = 18.sp
        val bodyL = 15.sp
        val bodyM = 14.sp
        val bodyS = 12.sp
        val bodyXs = 11.sp
    }
}
