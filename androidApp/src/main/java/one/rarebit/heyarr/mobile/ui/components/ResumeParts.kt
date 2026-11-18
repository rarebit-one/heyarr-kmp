@file:Suppress("FunctionNaming")

package one.rarebit.heyarr.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.rarebit.heyarr.core.playback.ResumePoint
import one.rarebit.heyarr.mobile.theme.Tokens
import one.rarebit.heyarr.ui.theme.LocalMediaTheme
import kotlin.time.Duration.Companion.seconds

/**
 * How far into an item this phone got (heyarr-kmp#232): a thin bar in the media's accent over
 * a muted track, under a Continue watching card's art and the detail screen's Resume. A
 * progress bar to TalkBack, which reads the share out.
 */
@Composable
fun ResumeBar(fraction: Float, modifier: Modifier = Modifier) {
    val f = fraction.coerceIn(0f, 1f)
    Box(
        modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Tokens.surface3)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f) },
    ) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(LocalMediaTheme.current.accent))
    }
}

/** `23:59 of 1:56:00`, or `Left at 23:59` when the runtime is unknown. */
fun resumeLabel(point: ResumePoint): String {
    val at = clockShort(point.positionSeconds.seconds.inWholeMilliseconds)
    val total = point.durationSeconds?.takeIf { it > 0 } ?: return "Left at $at"
    return "$at of ${clockShort(total.seconds.inWholeMilliseconds)}"
}
