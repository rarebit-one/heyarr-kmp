package one.rarebit.heyarr.mobile.cast

import one.rarebit.heyarr.core.cast.ReceiverReport
import one.rarebit.heyarr.core.playback.ResumeItem
import java.net.Inet4Address

/** A Cast receiver this phone has a session with. [address] is null when the SDK did not say. */
data class CastReceiver(val id: String, val name: String, val address: Inet4Address?)

/**
 * One film to cast: what it is, where in it to start (film-absolute: a resume point, or where
 * the phone's own player was), and what its resume point is filed under.
 */
data class CastItem(
    val assetId: String,
    val title: String,
    val startSeconds: Double = 0.0,
    val resume: ResumeItem? = null,
    val autoplay: Boolean = true,
)

/** One LOAD into the receiver's media session. [durationSeconds] is what remains of the film from the start. */
data class CastLoad(
    val url: String,
    val title: String,
    val mime: String,
    val autoplay: Boolean,
    val durationSeconds: Double?,
)

/**
 * The receiver, as [CastManager] drives it: the Cast SDK's session and `RemoteMediaClient`
 * on a phone ([SdkCastChannel]), a script in the tests. Calls are made on the main thread,
 * which the SDK requires; the suspending ones resume when the receiver has answered.
 */
interface CastChannel {
    /** The receiver of the current session, null with none. */
    val receiver: CastReceiver?

    /** A connected session: false with none, or while the SDK has it suspended (a network drop). */
    val live: Boolean

    /** The receiver's last media status, receiver-relative; null when its session has no media. */
    fun report(): ReceiverReport?

    /** LOADs [load] and returns the receiver's new media session id. Throws when it refused. */
    suspend fun load(load: CastLoad): Int

    suspend fun play()

    suspend fun pause()

    /** Stops the media (a user's Stop), leaving the session up. */
    suspend fun stop()

    /**
     * Quits the receiver's media app and starts a fresh session on the same receiver: a
     * wedged Default Media Receiver stalls every LOAD into its session identically
     * (heyarr-kmp#248). The session events this causes are the channel's own, not a user's.
     */
    suspend fun relaunch(): Boolean

    /** Starts a session on [receiver] again after the SDK gave one up; true once it is live. */
    suspend fun rejoin(receiver: CastReceiver): Boolean

    /** The receiver's volume, 0 to 1; null with no session. The volume keys go through the SDK's own media session. */
    fun volume(): Double? = null

    fun setVolume(level: Double) = Unit
}
