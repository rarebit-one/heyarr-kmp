package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.mobile.playback.HeyarrDataSource
import one.rarebit.heyarr.mobile.playback.PlaybackTarget
import org.junit.Assert.assertEquals
import org.junit.Test

/** A range read carries the proof in force now, not the one the play was planned with. */
class HeyarrDataSourceTest {
    private val planned = PlaybackTarget(
        contentUrl = "https://node.example:7777/api/v1/blobs/blake3:aa/content",
        credential = Credential.Device("cert.token", "proof.stale"),
        isVideo = true,
    )

    @Test
    fun theLiveHeaderWinsAndTheFrozenOneIsOnlyAFallback() {
        assertEquals(
            "Device cert.token proof.fresh",
            HeyarrDataSource.authorization("Device cert.token proof.fresh", planned),
        )
        assertEquals(planned.authHeaders()["Authorization"], HeyarrDataSource.authorization(null, planned))
        assertEquals(planned.authHeaders()["Authorization"], HeyarrDataSource.authorization("", planned))
    }
}
