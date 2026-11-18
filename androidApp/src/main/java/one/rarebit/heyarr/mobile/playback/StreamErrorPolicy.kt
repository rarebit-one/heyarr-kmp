package one.rarebit.heyarr.mobile.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import one.rarebit.heyarr.core.playback.Reconnect
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * How the film's reads ride out a lost connection (heyarr-kmp#232), on [Reconnect]'s
 * schedule. Media3's default gave up a few seconds after the buffer ran dry: a 20 s Wi-Fi
 * drop survived on the buffer, and anything longer ended in ERROR_CODE_IO_NETWORK_* and a
 * dead player. Here a connection failure ([isConnectionFailure]) is retried with a growing
 * wait for as long as [Reconnect.keepTrying] allows, and does not run down the retry budget
 * that would surface an error while it does. The player holds its position meanwhile, so the
 * film carries on from where it stalled. [onRetry] tells the session, for its message.
 *
 * A refusal that means the URL or lease lapsed ([Reconnect.expired]) is made fatal at once
 * rather than retried: the same request would be refused again, so the error reaches
 * [VideoSession], which has the item planned afresh ([PlayerRecovery.Decision.Refresh]).
 * Everything else is Media3's default.
 *
 * Like [LockedPlayback.ErrorPolicy], one instance serves one player, and what it remembers is
 * the outage: when the current run of failures began, and when the last one was.
 */
@UnstableApi
open class StreamErrorPolicy(
    private val clock: () -> Long = System::currentTimeMillis,
    private val onRetry: (attempt: Int, delayMs: Long) -> Unit = { _, _ -> },
    /**
     * Whether 401 / 403 / 410 are made fatal for the player to plan the item again. The film's
     * session does that; the music queue, whose reads ride render capabilities renewed by
     * themselves (RenderCapabilityCache), keeps Media3's own handling of them.
     */
    private val refreshExpired: Boolean = true,
) : DefaultLoadErrorHandlingPolicy() {
    @Volatile private var outageSince: Long? = null

    @Volatile private var lastFailureAt: Long? = null

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val error = loadErrorInfo.exception
        return when {
            refreshExpired && Reconnect.expired(httpStatus(error)) -> C.TIME_UNSET
            isConnectionFailure(error) -> outageRetry(loadErrorInfo.errorCount)
            else -> super.getRetryDelayMsFor(loadErrorInfo)
        }
    }

    /** The wait before the next try in an outage, or [C.TIME_UNSET] once it has gone on too long. */
    private fun outageRetry(errorCount: Int): Long {
        val now = clock()
        // Media3 restarts a load's error count once it has made progress: a first error is a new outage.
        val since = outageSince?.takeIf { errorCount > 1 } ?: now
        outageSince = since
        lastFailureAt = now
        if (!Reconnect.keepTrying(now - since)) return C.TIME_UNSET
        val delay = Reconnect.delayMs(errorCount)
        onRetry(errorCount, delay)
        return delay
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = minimumRetryCount(
        outageSince?.let { clock() - it },
        lastFailureAt?.let { clock() - it },
        super.getMinimumLoadableRetryCount(dataType),
    )

    companion object {
        /** How recent the last connection failure must be for the retry budget to stay lifted. */
        const val OUTAGE_GRACE_MS = 2 * Reconnect.MAX_DELAY_MS

        /**
         * The retry budget a starved stream is held to, pure so it is unit-tested: unbounded
         * while an outage is under way (a connection failure in the last [OUTAGE_GRACE_MS], and
         * the outage not yet given up on), else Media3's [default].
         */
        fun minimumRetryCount(outageMs: Long?, sinceLastFailureMs: Long?, default: Int): Int {
            val underWay = sinceLastFailureMs != null && sinceLastFailureMs in 0 until OUTAGE_GRACE_MS
            return if (underWay && outageMs != null && Reconnect.keepTrying(outageMs)) Int.MAX_VALUE else default
        }

        /**
         * A read that failed for want of a connection: the node unreachable, the network gone,
         * a timeout, a socket dropped mid-read. Not a refusal with a status, which is an answer.
         */
        fun isConnectionFailure(error: Throwable?): Boolean {
            // The first link that says either way; a refusal with a status is an answer, not a loss.
            val telling = generateSequence(error) { it.cause }.firstOrNull {
                it is HttpDataSource.InvalidResponseCodeException || lostConnection(it)
            }
            return telling != null && telling !is HttpDataSource.InvalidResponseCodeException
        }

        private fun lostConnection(e: Throwable): Boolean =
            e is UnknownHostException || e is SocketException || e is SocketTimeoutException ||
                // OkHttp's word for a connection the far end closed before answering.
                (e is IOException && e.message?.startsWith("unexpected end of stream") == true)

        /** The status of a refused read, when that is what [error] is. */
        fun httpStatus(error: Throwable?): Int? = generateSequence(error) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()?.responseCode
    }
}
