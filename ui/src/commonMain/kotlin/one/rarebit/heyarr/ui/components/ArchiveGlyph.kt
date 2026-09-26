@file:Suppress("MagicNumber") // Integer literals in this file are coordinates on the original pixel glyphs.

package one.rarebit.heyarr.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.ui.theme.Tokens

/** A compact Archive H. The same geometry serves Android and desktop at every size. */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
fun ArchiveMark(
    modifier: Modifier = Modifier,
    color: Color = Tokens.textPrimary,
    variant: ArchiveMarkVariant = ArchiveMarkVariant.PRIMARY,
) {
    Canvas(modifier.size(32.dp)) {
        val u = size.minDimension / 12f
        val markColor = if (variant == ArchiveMarkVariant.OUTLINE) color.copy(alpha = 0.72f) else color
        when (variant) {
            ArchiveMarkVariant.MICRO -> drawMicroMark(u, markColor)

            ArchiveMarkVariant.OUTLINE -> drawOutlineMark(u, markColor)

            else -> {
                drawSolidMark(u, markColor, variant == ArchiveMarkVariant.PIXEL_CUT)
                drawMarkDecoration(variant, u, markColor)
            }
        }
    }
}

private fun DrawScope.drawMicroMark(unit: Float, color: Color) {
    drawPixelBlock(MarkBlock(2, 2, 2, 2), unit, color)
    drawPixelBlock(MarkBlock(5, 5, 2, 2), unit, color)
    drawPixelBlock(MarkBlock(8, 8, 2, 2), unit, color)
}

private fun DrawScope.drawOutlineMark(unit: Float, color: Color) {
    drawRect(color, Offset(2 * unit, unit), Size(3 * unit, 10 * unit), style = Stroke(width = unit))
    drawRect(color, Offset(7 * unit, unit), Size(3 * unit, 10 * unit), style = Stroke(width = unit))
    drawRect(color, Offset(5 * unit, 5 * unit), Size(2 * unit, 2 * unit), style = Stroke(width = unit))
}

private fun DrawScope.drawSolidMark(unit: Float, color: Color, pixelCut: Boolean) {
    val leftCut = if (pixelCut) Offset(4f, 1f) else null
    val rightCut = if (pixelCut) Offset(7f, 10f) else null
    drawMarkLeg(2..4, 1..10, leftCut, unit, color)
    drawMarkLeg(7..9, 1..10, rightCut, unit, color)
    drawPixelBlock(MarkBlock(5, 5, 2, 2), unit, color)
}

private fun DrawScope.drawMarkLeg(columns: IntRange, rows: IntRange, cut: Offset?, unit: Float, color: Color) {
    for (x in columns) {
        for (y in rows) {
            if (cut == null || x.toFloat() != cut.x || y.toFloat() != cut.y) {
                drawPixelBlock(MarkBlock(x, y), unit, color)
            }
        }
    }
}

private fun DrawScope.drawMarkDecoration(variant: ArchiveMarkVariant, unit: Float, color: Color) {
    when (variant) {
        ArchiveMarkVariant.PRIMARY -> {
            drawPixelBlock(MarkBlock(1, 0), unit, color)
            drawPixelBlock(MarkBlock(10, 11), unit, color)
        }

        ArchiveMarkVariant.BRACKETED -> {
            drawPixelBlock(MarkBlock(0, 1, 1, 3), unit, color)
            drawPixelBlock(MarkBlock(0, 1, 3, 1), unit, color)
            drawPixelBlock(MarkBlock(11, 8, 1, 3), unit, color)
            drawPixelBlock(MarkBlock(9, 10, 3, 1), unit, color)
        }

        ArchiveMarkVariant.PIXEL_CUT,
        ArchiveMarkVariant.MICRO,
        ArchiveMarkVariant.OUTLINE,
        -> Unit
    }
}

private data class MarkBlock(val x: Int, val y: Int, val width: Int = 1, val height: Int = 1)

private fun DrawScope.drawPixelBlock(block: MarkBlock, unit: Float, color: Color) {
    drawRect(
        color,
        Offset(block.x * unit, block.y * unit),
        Size(block.width * unit, block.height * unit),
    )
}

/** Indeterminate activity only: a changing pixel glyph never implies a measured percentage. */
@Composable
fun ArchiveActivity(reduceMotion: Boolean, modifier: Modifier = Modifier, color: Color = Tokens.textPrimary) {
    val phase = if (reduceMotion) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "archive activity")
        val value by transition.animateFloat(
            0f,
            4f,
            infiniteRepeatable(tween(3200, easing = LinearEasing)),
            label = "glyph phase",
        )
        value
    }
    val frames =
        listOf(
            "0010001110111110111000100",
            "1111110001101011000111111",
            "1000110001111111000110001",
            "0010000100111110010000100",
        )
    val mask = frames[phase.toInt().coerceIn(0, 3)]
    Canvas(modifier.size(24.dp)) {
        val step = size.minDimension / 5f
        mask.forEachIndexed { i, cell ->
            if (cell ==
                '1'
            ) {
                drawRect(
                    color.copy(alpha = 0.8f),
                    Offset((i % 5) * step, (i / 5) * step),
                    Size(
                        step * .7f,
                        step * .7f,
                    ),
                )
            }
        }
    }
}
