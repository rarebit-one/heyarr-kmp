package one.rarebit.heyarr.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.ui.theme.Tokens

/**
 * The shell's right-hand context column. A wide window has room beside the main task, and the
 * mockups use it for what sits next to a screen rather than inside it: collection insight on
 * Home, the selected result on Search, acquisition status on a work. The shell owns the column;
 * the screen on show fills it with [ContextPane].
 */
class ContextPaneState {
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
}

/** The column the current screen can fill, or null when the window is too narrow for one. */
val LocalContextPane = staticCompositionLocalOf<ContextPaneState?> { null }

/** The shell shows a context column from this width up. */
val CONTEXT_COLUMN_MIN_WINDOW: Dp = 1360.dp
private val CONTEXT_COLUMN_WIDTH: Dp = 320.dp

/**
 * Puts [content] in the context column while the calling screen is composed, and takes it out
 * when the screen leaves. A no-op when the window is too narrow for a column; a screen that
 * needs the content either way checks [hasContextColumn] and shows it inline instead.
 */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
fun ContextPane(content: @Composable () -> Unit) {
    val pane = LocalContextPane.current ?: return
    val latest by rememberUpdatedState(content)
    DisposableEffect(pane) {
        val slot: @Composable () -> Unit = { latest() }
        pane.content = slot
        onDispose { if (pane.content === slot) pane.content = null }
    }
}

/** Whether the shell has a context column to fill. */
val hasContextColumn: Boolean
    @Composable get() = LocalContextPane.current != null

/** The column itself: a quiet raised panel behind a dashed rule, scrolling its own content. */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
fun ContextColumn(content: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.width(CONTEXT_COLUMN_WIDTH).fillMaxHeight().background(Tokens.surface1)
            .drawBehind {
                drawLine(
                    Tokens.border,
                    Offset(0f, 0f),
                    Offset(0f, size.height),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                )
            }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(28.dp)) { content() }
    }
}
