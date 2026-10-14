package one.rarebit.heyarr.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import one.rarebit.heyarr.mobile.playback.VideoSession

/** How far one double tap jumps, matching the transport's ±10 buttons. */
internal const val DOUBLE_TAP_SEEK_SECONDS = 10

/** The picture splits into thirds: the outer two seek, the middle one doesn't. */
private const val ZONES = 3f

/** How long the "+10 s" badge stays; another double tap on the same side inside it adds up. */
private const val SEEK_BADGE_MS = 700L

/**
 * Which way a double tap at [x] on a picture [width] wide seeks: -1 on the left third, +1 on
 * the right third, 0 in the middle third, which is left alone so a tap aimed at the centre
 * never jumps.
 */
internal fun seekSide(x: Float, width: Int): Int = when {
    width <= 0 -> 0
    x < width / ZONES -> -1
    x > width - width / ZONES -> 1
    else -> 0
}

/** A jump the viewer just made, on [side], [seconds] in total so far; [stamp] restarts the badge's timer. */
private data class SeekBadgeState(val side: Int, val seconds: Int, val stamp: Long)

/**
 * The picture's touch layer. A double tap on the left or right third jumps back or ahead
 * [DOUBLE_TAP_SEEK_SECONDS], with a badge on that side; repeated double taps on the same side
 * add up while it shows. A single tap does [onTap]. Double taps are only listened for when
 * the stream can seek ([seekable], the transport's rule), so an unseekable stream's single
 * taps are not held back waiting for a second one.
 */
@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
internal fun SeekGestureLayer(
    video: VideoSession,
    seekable: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var badge by remember { mutableStateOf<SeekBadgeState?>(null) }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentVideo by rememberUpdatedState(video)
    LaunchedEffect(badge) {
        if (badge != null) {
            delay(SEEK_BADGE_MS)
            badge = null
        }
    }
    Box(
        modifier.pointerInput(seekable) {
            detectTapGestures(
                onTap = { currentOnTap() },
                onDoubleTap = if (seekable) {
                    { at ->
                        val side = seekSide(at.x, size.width)
                        if (side != 0) {
                            currentVideo.seekBy(side * DOUBLE_TAP_SEEK_SECONDS.toDouble())
                            val previous = badge
                            val total = if (previous?.side ==
                                side
                            ) {
                                previous.seconds + DOUBLE_TAP_SEEK_SECONDS
                            } else {
                                DOUBLE_TAP_SEEK_SECONDS
                            }
                            badge = SeekBadgeState(side, total, System.nanoTime())
                        }
                    }
                } else {
                    null
                },
            )
        },
    ) {
        badge?.let { b ->
            SeekBadge(b, Modifier.align(if (b.side < 0) Alignment.CenterStart else Alignment.CenterEnd))
        }
    }
}

@Composable
@Suppress("FunctionNaming") // Compose components follow the shared component naming convention.
private fun SeekBadge(b: SeekBadgeState, modifier: Modifier) {
    Column(
        modifier.padding(horizontal = 32.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (b.side < 0) Icons.Rounded.FastRewind else Icons.Rounded.FastForward,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(28.dp),
        )
        Text(
            (if (b.side < 0) "−" else "+") + "${b.seconds} s",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
