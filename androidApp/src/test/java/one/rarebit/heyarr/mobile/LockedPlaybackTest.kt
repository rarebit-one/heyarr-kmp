package one.rarebit.heyarr.mobile

import androidx.media3.common.util.UnstableApi
import one.rarebit.heyarr.mobile.net.AuthHeaderSource
import one.rarebit.heyarr.mobile.net.CredentialUnavailableException
import one.rarebit.heyarr.mobile.playback.LockedPlayback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.security.InvalidKeyException

/**
 * A locked phone cannot re-mint a Device proof. That refusal must reach the media stack
 * as a retryable [IOException] — not as the keystore's unchecked exception, which OkHttp
 * rethrows on its dispatcher thread and which killed the app mid-song under the lock
 * screen — and the player must wait it out rather than give up.
 */
@UnstableApi
class LockedPlaybackTest {

    @Test fun aKeystoreRefusalBecomesCredentialUnavailable() {
        val refusal = InvalidKeyException("Keystore operation failed")
        val source = AuthHeaderSource().apply { provider = { throw refusal } }
        try {
            source.current()
            fail("expected the refusal to surface")
        } catch (e: CredentialUnavailableException) {
            assertSame("the keystore's exception is kept as the cause", refusal, e.cause)
        }
    }

    @Test fun anUncheckedRefusalBecomesCredentialUnavailable() {
        val source = AuthHeaderSource().apply { provider = { throw IllegalStateException("auth required") } }
        try {
            source.current()
            fail("expected the refusal to surface")
        } catch (e: IOException) {
            // An IOException, so OkHttp hands it to the caller instead of crashing the process.
            assertTrue("it is marked as a locked-credential failure", CredentialUnavailableException.isCause(e))
        }
    }

    @Test fun anIoFailurePassesThroughUnwrapped() {
        val io = IOException("disk")
        val source = AuthHeaderSource().apply { provider = { throw io } }
        try {
            source.current()
            fail("expected the IOException")
        } catch (e: IOException) {
            assertSame("an IOException is already safe to throw", io, e)
        }
    }

    @Test fun aWorkingProviderIsUntouched() {
        assertEquals("Device c~p", AuthHeaderSource().apply { provider = { "Device c~p" } }.current())
        assertNull(AuthHeaderSource().current())
    }

    @Test fun theCauseIsFoundThroughWrapping() {
        val locked = CredentialUnavailableException(InvalidKeyException("locked"))
        // How it reaches the load policy: wrapped by the data source and then the loader.
        val wrapped = RuntimeException("load failed", IOException("open failed", locked))
        assertTrue(CredentialUnavailableException.isCause(locked))
        assertTrue(CredentialUnavailableException.isCause(wrapped))
        assertFalse(CredentialUnavailableException.isCause(IOException("connection reset")))
        assertFalse(CredentialUnavailableException.isCause(null))
    }

    @Test fun aLockedReadRetriesAndOthersKeepTheDefault() {
        val locked = IOException("open failed", CredentialUnavailableException(InvalidKeyException("locked")))
        assertEquals(
            "a locked phone is waited out",
            LockedPlayback.CREDENTIAL_RETRY_MS,
            LockedPlayback.retryDelayMs(locked) { -1L },
        )
        assertEquals(
            "any other failure keeps Media3's verdict",
            1_000L,
            LockedPlayback.retryDelayMs(IOException("connection reset")) { 1_000L },
        )
    }

    @Test fun aRecentLockLiftsTheRetryBudgetForEveryLoad() {
        assertEquals("no lock seen: Media3's budget", 3, LockedPlayback.minimumRetryCount(null, 3))
        assertEquals("locked just now", Int.MAX_VALUE, LockedPlayback.minimumRetryCount(0, 3))
        assertEquals(
            "unlocked, retry not yet run",
            Int.MAX_VALUE,
            LockedPlayback.minimumRetryCount(LockedPlayback.CREDENTIAL_RETRY_MS, 3),
        )
        assertEquals(
            "unlocked long enough ago: back to Media3's budget",
            3,
            LockedPlayback.minimumRetryCount(LockedPlayback.UNLOCK_GRACE_MS, 3),
        )
    }
}
