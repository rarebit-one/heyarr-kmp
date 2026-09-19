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
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.ui.theme.Tokens

/** A compact Archive H. The same geometry serves Android and desktop at every size. */
@Composable
fun ArchiveMark(modifier: Modifier = Modifier, color: Color = Tokens.textPrimary) {
    Canvas(modifier.size(32.dp)) {
        val u = size.minDimension / 12f
        fun block(x: Int, y: Int, w: Int, h: Int) = drawRect(color, Offset(x * u, y * u), Size(w * u, h * u))
        block(2, 1, 3, 10); block(7, 1, 3, 10); block(5, 5, 2, 2)
        block(1, 0, 1, 1); block(10, 11, 1, 1)
    }
}

/** Indeterminate activity only: a changing pixel glyph never implies a measured percentage. */
@Composable
fun ArchiveActivity(reduceMotion: Boolean, modifier: Modifier = Modifier, color: Color = Tokens.textPrimary) {
    val phase = if (reduceMotion) 0f else {
        val transition = rememberInfiniteTransition(label = "archive activity")
        val value by transition.animateFloat(0f, 4f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "glyph phase")
        value
    }
    val frames = listOf("0010001110111110111000100", "1111110001101011000111111", "1000110001111111000110001", "0010000100111110010000100")
    val mask = frames[phase.toInt().coerceIn(0, 3)]
    Canvas(modifier.size(24.dp)) {
        val step = size.minDimension / 5f
        mask.forEachIndexed { i, cell ->
            if (cell == '1') drawRect(color.copy(alpha = 0.8f), Offset((i % 5) * step, (i / 5) * step), Size(step * .7f, step * .7f))
        }
    }
}
