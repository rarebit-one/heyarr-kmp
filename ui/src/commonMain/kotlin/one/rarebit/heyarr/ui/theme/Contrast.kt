package one.rarebit.heyarr.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * WCAG 2 contrast arithmetic, the yardstick every palette is held to (see [Palette]). Same
 * formula MediaThemeTest has always used, so a derived palette and the hand-tuned Archive one
 * are measured identically.
 */
@Suppress("MagicNumber") // The WCAG relative-luminance constants.
object Contrast {
    const val AA_TEXT = 4.5
    const val AA_MARK = 3.0

    fun luminance(c: Color): Double {
        fun channel(v: Float): Double = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** [from] moved [t] of the way to [to], per sRGB channel; opaque. */
    fun mix(from: Color, to: Color, t: Float): Color = Color(
        red = from.red + (to.red - from.red) * t,
        green = from.green + (to.green - from.green) * t,
        blue = from.blue + (to.blue - from.blue) * t,
        alpha = 1f,
    )

    /**
     * [color] unchanged if it already clears [min] against every one of [grounds]; otherwise
     * the smallest step toward [toward] that does, so a theme's hue survives and only its
     * lightness is nudged. Falls back to [toward] itself when no step is enough.
     */
    fun atLeast(color: Color, grounds: List<Color>, min: Double, toward: Color): Color {
        fun ok(c: Color) = grounds.all { ratio(c, it) >= min }
        return when {
            ok(color) -> color

            !ok(toward) -> toward

            else -> {
                var lo = 0f
                var hi = 1f
                repeat(SEARCH_STEPS) {
                    val mid = (lo + hi) / 2
                    if (ok(mix(color, toward, mid))) hi = mid else lo = mid
                }
                mix(color, toward, hi)
            }
        }
    }

    private const val SEARCH_STEPS = 16
}
