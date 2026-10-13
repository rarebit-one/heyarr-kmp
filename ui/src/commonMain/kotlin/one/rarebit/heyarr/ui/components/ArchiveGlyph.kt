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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.ui.theme.Tokens
import kotlin.math.floor

/**
 * The Archive H, on a 12 × 12 grid. Each grid unit is snapped to whole device pixels and the
 * blocks fill as one path with anti-aliasing off, so the mark stays a crisp pixel glyph at
 * every size: fractional units used to anti-alias each block's edges into a striped grid.
 * The mark is Cream; [PRIMARY][ArchiveMarkVariant.PRIMARY] carries one Ochre pixel at its foot,
 * as in the brand guide.
 */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
fun ArchiveMark(
    modifier: Modifier = Modifier,
    color: Color = Tokens.cream,
    variant: ArchiveMarkVariant = ArchiveMarkVariant.PRIMARY,
    accent: Color = Tokens.ochre,
) {
    Canvas(modifier.size(36.dp)) {
        val unit = floor(size.minDimension / MARK_GRID).coerceAtLeast(1f)
        val origin = Offset(floor((size.width - unit * MARK_GRID) / 2f), floor((size.height - unit * MARK_GRID) / 2f))
        val blocks = markBlocks(variant)
        val markColor = if (variant == ArchiveMarkVariant.OUTLINE) color.copy(alpha = 0.72f) else color
        fillCrisp(blocks.filterNot { it.accent }, unit, origin, markColor)
        fillCrisp(blocks.filter { it.accent }, unit, origin, accent)
    }
}

private const val MARK_GRID = 12f

private data class MarkBlock(
    val x: Int,
    val y: Int,
    val width: Int = 1,
    val height: Int = 1,
    val accent: Boolean = false,
)

private val LEFT_LEG = 2..4
private val RIGHT_LEG = 7..9
private val LEG_ROWS = 1..10

/** The blocks of one mark variant, in grid units. */
private fun markBlocks(variant: ArchiveMarkVariant): List<MarkBlock> = when (variant) {
    ArchiveMarkVariant.MICRO -> listOf(MarkBlock(2, 2, 2, 2), MarkBlock(5, 5, 2, 2), MarkBlock(8, 8, 2, 2))

    ArchiveMarkVariant.OUTLINE -> legCells().filter { (x, y) ->
        y == LEG_ROWS.first || y == LEG_ROWS.last || x == LEFT_LEG.first || x == LEFT_LEG.last ||
            x == RIGHT_LEG.first || x == RIGHT_LEG.last
    }.map { (x, y) -> MarkBlock(x, y) } + MarkBlock(5, 5, 2, 2)

    ArchiveMarkVariant.PIXEL_CUT -> legCells().filterNot { it == 4 to 1 || it == 7 to 10 }
        .map { (x, y) -> MarkBlock(x, y) } + MarkBlock(5, 5, 2, 2)

    ArchiveMarkVariant.PRIMARY -> solidH() + MarkBlock(1, 0) + MarkBlock(10, 11, accent = true)

    ArchiveMarkVariant.BRACKETED -> solidH() + listOf(
        MarkBlock(0, 1, 1, 3),
        MarkBlock(0, 1, 3, 1),
        MarkBlock(11, 8, 1, 3),
        MarkBlock(9, 10, 3, 1),
    )
}

private fun legCells(): List<Pair<Int, Int>> = (LEFT_LEG + RIGHT_LEG).flatMap { x -> LEG_ROWS.map { y -> x to y } }

private fun solidH(): List<MarkBlock> = listOf(
    MarkBlock(LEFT_LEG.first, LEG_ROWS.first, LEFT_LEG.count(), LEG_ROWS.count()),
    MarkBlock(RIGHT_LEG.first, LEG_ROWS.first, RIGHT_LEG.count(), LEG_ROWS.count()),
    MarkBlock(5, 5, 2, 2),
)

/** Fills [blocks] as one aliased path, so shared block edges never show a seam. */
private fun DrawScope.fillCrisp(blocks: List<MarkBlock>, unit: Float, origin: Offset, color: Color) {
    if (blocks.isEmpty()) return
    val path = Path()
    for (b in blocks) {
        path.addRect(
            Rect(
                origin.x + b.x * unit,
                origin.y + b.y * unit,
                origin.x + (b.x + b.width) * unit,
                origin.y + (b.y + b.height) * unit,
            ),
        )
    }
    val paint = Paint().apply {
        this.color = color
        isAntiAlias = false
    }
    drawIntoCanvas { it.drawPath(path, paint) }
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
