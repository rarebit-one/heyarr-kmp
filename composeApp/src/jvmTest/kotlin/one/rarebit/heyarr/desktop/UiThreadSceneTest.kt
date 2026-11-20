package one.rarebit.heyarr.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.desktop.preview.UiThreadScene
import java.awt.EventQueue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The screenshot harness's scene wrapper (#247). Compose desktop posts part of each owner's rect
 * bookkeeping (RectManager.dispatchCallbacks, which defragments its RectList) to the AWT event
 * thread. Driving an ImageComposeScene from any other thread let that posted dispatch run in the
 * middle of a frame and corrupt the list: `LayoutNode N not found in RectList`, or an
 * ArrayIndexOutOfBoundsException, usually only noticed when close() detached every node.
 */
class UiThreadSceneTest {
    @Test
    fun theSceneComposesOnTheEventThread() {
        var composedOnEventThread = false
        UiThreadScene(width = 40, height = 40) {
            SideEffect { composedOnEventThread = EventQueue.isDispatchThread() }
            Box(Modifier.size(4.dp))
        }.use { it.render(0L) }
        assertTrue(composedOnEventThread, "composition runs on the AWT event thread, beside Compose's posted work")
    }

    /**
     * Churn nodes every frame while the event thread gets a window to run its posted rect
     * dispatch: a placement that pauses past Compose's 16 ms debounce (a slow frame, or a busy
     * runner descheduling the harness), and a rect listener that keeps that dispatch busy. With
     * [UiThreadScene]'s hop to the event thread removed, this failed 6 of 6 runs locally (27 of
     * 60 scenes, with both of #247's exceptions); with it, 0 of 60.
     */
    @Test
    fun churningScenesSurviveTheEventThreadsRectDispatch() {
        val failures = mutableListOf<String>()
        val random = Random(SEED)
        repeat(SCENES) { s ->
            val tick = mutableIntStateOf(0)
            runCatching {
                UiThreadScene(width = 400, height = 400) {
                    val t = tick.intValue
                    Column {
                        Box(
                            Modifier.offset { IntOffset(tick.intValue % 7, 0) }.size(4.dp)
                                .onLayoutRectChanged(throttleMillis = 0, debounceMillis = 0) {
                                    Thread.sleep(random.nextLong(LISTENER_PAUSE_MS + 1))
                                },
                        )
                        Grid(t, salt = 0)
                        PausedPlacement({ tick.intValue }, random) { Grid(t, salt = 10_000_000) }
                    }
                }.use { scene ->
                    repeat(FRAMES) { f ->
                        tick.intValue++
                        scene.render(f * FRAME_NANOS)
                    }
                }
            }.onFailure { failures += "scene $s: $it" }
        }
        assertEquals(emptyList(), failures, "no scene corrupts its RectList")
    }

    /** Re-places its children every frame, after a pause long enough for a posted dispatch to fire. */
    @Composable
    private fun PausedPlacement(tick: () -> Int, random: Random, content: @Composable () -> Unit) {
        Layout(content) { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
            layout(constraints.maxWidth, placeables.sumOf { it.height }) {
                tick() // read in placement, so every frame places again
                Thread.sleep(PAUSE_MIN_MS + random.nextLong(PAUSE_SPREAD_MS + 1))
                var y = 0
                placeables.forEach {
                    it.place(0, y)
                    y += it.height
                }
            }
        }
    }

    /** Rows of tiny boxes, half of them re-keyed every frame: removals and inserts in one pass. */
    @Composable
    private fun Grid(t: Int, salt: Int) {
        Column {
            repeat(ROWS) { r ->
                Row {
                    repeat(COLUMNS) { c ->
                        val i = r * COLUMNS + c
                        key(if ((i + t) % 2 == 0) i + salt + 1_000_000 * (t % 2) else i + salt) {
                            Box(Modifier.size(1.dp))
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val SEED = 247
        const val SCENES = 10
        const val FRAMES = 40
        const val ROWS = 20
        const val COLUMNS = 50
        const val PAUSE_MIN_MS = 10L
        const val PAUSE_SPREAD_MS = 15L
        const val LISTENER_PAUSE_MS = 6L
        const val FRAME_NANOS = 16_000_000L
    }
}
