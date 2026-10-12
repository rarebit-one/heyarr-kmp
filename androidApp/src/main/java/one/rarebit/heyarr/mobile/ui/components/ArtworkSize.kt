package one.rarebit.heyarr.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import one.rarebit.heyarr.mobile.library.Work
import kotlin.math.roundToInt

/**
 * The poster path for an image drawn [widthDp] wide on this screen: the width in physical
 * pixels picks the smallest ADR-0109 bucket that covers it, falling back to the original
 * when the node sent no buckets ([Work.artworkFor]).
 */
@Composable
fun Work.artworkForDp(widthDp: Int): String? = artworkFor((widthDp * LocalDensity.current.density).roundToInt())
