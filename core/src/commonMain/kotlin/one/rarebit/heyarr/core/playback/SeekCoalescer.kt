package one.rarebit.heyarr.core.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Combines rapid seek inputs into one absolute target, owned by a player session. */
class SeekCoalescer(private val scope: CoroutineScope, private val commit: (Double) -> Unit) {
    private var pending: Double? = null
    private var job: Job? = null

    fun seekTo(seconds: Double) {
        job?.cancel()
        pending = seconds.coerceAtLeast(0.0)
        job = scope.launch {
            delay(SETTLE_MS)
            val target = pending ?: return@launch
            pending = null
            job = null
            commit(target)
        }
    }

    fun seekBy(currentSeconds: Double, deltaSeconds: Double) {
        seekTo((pending ?: currentSeconds) + deltaSeconds)
    }

    fun cancel() {
        job?.cancel()
        job = null
        pending = null
    }

    companion object {
        private const val SETTLE_MS = 250L
    }
}
