package one.rarebit.heyarr.desktop.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Image
import java.awt.EventQueue

/**
 * An off-screen [ImageComposeScene] whose every call — creation, content, frames and close —
 * runs on the AWT event thread, so the screenshot harnesses and the tests that render the app
 * can drive it from any thread.
 *
 * Why the event thread, and why this is a fix rather than a retry (heyarr-kmp#247): Compose
 * Multiplatform's `RectManager` (the per-owner index of every layout node's bounds) defers part
 * of its work with `postDelayed`, which on desktop is `GlobalScope.launch(MainUIDispatcher)` —
 * Swing's event thread (Compose UI 1.12.2 `Actuals.skiko.kt` / `Actuals.desktop.kt`, tracked
 * upstream as https://youtrack.jetbrains.com/issue/CMP-7153). That deferred
 * `dispatchCallbacks()` defragments the owner's `RectList`, swapping its backing arrays. A scene
 * rendered and closed on `main` therefore had two unsynchronised threads mutating one
 * `RectList`: when the posted dispatch landed mid-frame (a slow frame, or a busy CI runner
 * descheduling `main` past the 16 ms debounce), the defragment raced an insert or removal, a
 * node's entry was lost, and the crash surfaced later — `LayoutNode N not found in RectList` (or
 * an `ArrayIndexOutOfBoundsException`), typically when [close] detached every node, after
 * whichever screen happened to lose the race. `ImageComposeScene`'s own contract is "the main UI
 * thread only"; confining the scene to the thread Compose posts to serialises that deferred
 * dispatch with our frames, which removes the race instead of quietening it.
 *
 * The harness still sleeps on its own thread between frames, so posted work (and the IO the
 * fixtures complete) runs between frames as it would in the app.
 */
internal class UiThreadScene(
    width: Int,
    height: Int,
    density: Density = Density(1f),
    content: @Composable () -> Unit,
) : AutoCloseable {
    private val scene = onUiThread {
        ImageComposeScene(width = width, height = height, density = density).apply { setContent(content) }
    }

    /** Renders one frame stamped [nanoTime]; the returned image is a snapshot, safe off the UI thread. */
    fun render(nanoTime: Long): Image = onUiThread { scene.render(nanoTime) }

    fun hasInvalidations(): Boolean = onUiThread { scene.hasInvalidations() }

    override fun close() = onUiThread { scene.close() }
}

/**
 * Runs [block] on the AWT event thread and returns its result, rethrowing what it threw (not
 * wrapped in `InvocationTargetException`, so a failure still points at the scene).
 */
internal fun <T> onUiThread(block: () -> T): T {
    if (EventQueue.isDispatchThread()) return block()
    var result: Result<T>? = null
    EventQueue.invokeAndWait { result = runCatching(block) }
    return checkNotNull(result) { "the event thread did not run the block" }.getOrThrow()
}
