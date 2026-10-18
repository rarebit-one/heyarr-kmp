package one.rarebit.heyarr.mobile.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import one.rarebit.heyarr.mobile.net.CredentialUnavailableException

/**
 * How the audio queue keeps playing under the lock screen.
 *
 * Every range read carries a Device possession proof, a proof lives about two minutes,
 * and re-minting one signs with a hardware key that is `setUnlockedDeviceRequired`: a
 * locked phone cannot sign at all. That is the key's security posture and not ours to
 * loosen, so the player works around it twice over:
 *
 *  1. **Buffer deep while the phone can still sign.** Media3's default stops loading
 *     about 50 s ahead, so a locked phone ran dry a minute or two in. [loadControl] keeps
 *     loading — across track boundaries, since ExoPlayer fetches the next queue item once
 *     the current one is in — until [MAX_BUFFER_MS] of audio or [TARGET_BUFFER_BYTES] is
 *     held, whichever comes first. On a LAN a whole album lands in seconds.
 *  2. **Wait for the unlock instead of failing.** A read that could not get a credential
 *     ([CredentialUnavailableException]) is retried every [CREDENTIAL_RETRY_MS] for as
 *     long as it keeps failing that way, and does not count towards the retry budget that
 *     would surface a playback error. Past the buffer the player shows buffering, and it
 *     resumes on its own a few seconds after the phone is unlocked.
 */
@UnstableApi
object LockedPlayback {
    /** How far ahead to load. Audio only: 30 min is ~70 MB at 320 kb/s, so bytes usually bind first. */
    const val MAX_BUFFER_MS = 30 * 60 * 1000

    /**
     * The in-memory buffer ceiling (ExoPlayer holds it on the heap). 48 MiB is about 20
     * minutes of 320 kb/s audio and a comfortable fraction of a 256 MiB app heap.
     */
    const val TARGET_BUFFER_BYTES = 48 * 1024 * 1024

    /** How often a read blocked on a locked phone tries again. */
    const val CREDENTIAL_RETRY_MS = 3_000L

    // Media3's own start-up thresholds, unchanged: deep buffering must not delay the first note.
    private const val BUFFER_FOR_PLAYBACK_MS = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS
    private const val BUFFER_AFTER_REBUFFER_MS = DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS

    /**
     * min == max, so loading runs straight to the ceiling instead of idling between a low
     * and a high watermark; and size is not traded for time, so the byte cap always holds.
     */
    fun loadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(MAX_BUFFER_MS, MAX_BUFFER_MS, BUFFER_FOR_PLAYBACK_MS, BUFFER_AFTER_REBUFFER_MS)
        .setTargetBufferBytes(TARGET_BUFFER_BYTES)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()

    /**
     * The retry decision for one failed read, pure so it is unit-tested: a locked-phone
     * failure always retries after [CREDENTIAL_RETRY_MS]; anything else gets [otherwise]
     * (Media3's default backoff, or its verdict that the error is fatal).
     */
    fun retryDelayMs(error: Throwable?, otherwise: () -> Long): Long =
        if (CredentialUnavailableException.isCause(error)) CREDENTIAL_RETRY_MS else otherwise()

    /**
     * How long after the last locked-credential failure the retry budget stays lifted:
     * comfortably more than [CREDENTIAL_RETRY_MS], so it spans the gap between the unlock
     * and the retry that then succeeds.
     */
    const val UNLOCK_GRACE_MS = 15_000L

    /**
     * The retry budget a starved stream is held to, pure so it is unit-tested: unbounded
     * while a locked-credential failure was seen within [UNLOCK_GRACE_MS] (the phone is,
     * or was a moment ago, locked), else Media3's [default].
     */
    fun minimumRetryCount(msSinceCredentialFailure: Long?, default: Int): Int = if (msSinceCredentialFailure != null &&
        msSinceCredentialFailure in 0 until UNLOCK_GRACE_MS
    ) {
        Int.MAX_VALUE
    } else {
        default
    }

    /**
     * Media3's default policy, except for [CredentialUnavailableException]: see the object
     * doc. The Loader compares its error count against [getMinimumLoadableRetryCount]
     * whenever a starved renderer asks, and that count must not run out while the phone
     * sits locked, nor between the unlock and the retry that succeeds.
     *
     * One instance serves every period in the queue, so what it remembers is a fact about
     * the DEVICE, not about one load: when a read last failed for want of a credential.
     * While that is recent every load is held to an unbounded budget — every range read
     * needs a proof, so none can succeed until the unlock anyway — and nothing an
     * unrelated failure does can shorten it. Once the phone has been unlocked for
     * [UNLOCK_GRACE_MS], a dead node surfaces as a playback error as before.
     */
    class ErrorPolicy(private val clock: () -> Long = System::currentTimeMillis) : DefaultLoadErrorHandlingPolicy() {
        @Volatile
        private var lastCredentialFailureAt: Long? = null

        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            if (CredentialUnavailableException.isCause(loadErrorInfo.exception)) lastCredentialFailureAt = clock()
            return retryDelayMs(loadErrorInfo.exception) { super.getRetryDelayMsFor(loadErrorInfo) }
        }

        override fun getMinimumLoadableRetryCount(dataType: Int): Int = minimumRetryCount(
            lastCredentialFailureAt?.let { clock() - it },
            super.getMinimumLoadableRetryCount(dataType),
        )
    }
}
