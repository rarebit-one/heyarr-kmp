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
    // Archive palette: warm forest surfaces and chalk text keep real artwork prominent.
    val bgBase = Color(0xFF101815) // Forest Black
    val surface1 = Color(0xFF1C2520) // Raised Panel
    val surface2 = Color(0xFF242F29)
    val surface3 = Color(0xFF2C3932)
    val border = Color(0xFF46554C)

    // Text ramp.
    val textPrimary = Color(0xFFE5E9DC) // Chalk
    val textMuted = Color(0xFFB8C1B5)
    val textDisabled = Color(0xFF849188)

    // The reader gets its own paper and ink rather than borrowing the chrome colours.
    val readingPaper = Color(0xFFE5E9DC)
    val readingInk = Color(0xFF202720)

    // Semantic.
    val ratingGold = Color(0xFFC7B66D) // Ochre
    val danger = Color(0xFFE58B7F)
    val warning = Color(0xFFC7B66D)
    val success = Color(0xFF8DBD9E)

    // Default accent = the forest fern (Movie accent; also the app's neutral CTA).
    val accent = Color(0xFF599C7B) // Fern
    val accentHover = Color(0xFF78B493)
    val accentGradEnd = Color(0xFFB8D8C7) // Pale Mint

    /** Neutral slate accent for unknown / non-media types (documents, feeds). */
    val slate = Color(0xFF9CAAA0)
    val slateHover = Color(0xFFBCC8BE)
    val slateGradEnd = Color(0xFFDCE3D9)

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
    val navWidth: Dp = 76.dp
    val compactBreakpoint: Dp = 900.dp
    val posterWidth: Dp = 160.dp
    val squareWidth: Dp = 168.dp

    /** Compact glyph scale, drawn in dp so the same marks work on desktop and touch. */
    val pixel: Dp = 3.dp

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
