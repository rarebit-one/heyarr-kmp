package one.rarebit.heyarr.mobile.net

import java.io.IOException

/**
 * The live `Authorization` header value, for callers that are not our own transport:
 * the image loader and the media data source, which fetch through the shared OkHttp
 * client directly. The provider is swapped by the ViewModel as the credential changes
 * (QR session → Device cert → signed out); a null means "send nothing".
 */
class AuthHeaderSource {
    @Volatile
    var provider: () -> String? = { null }

    /**
     * The header to send now. A provider that cannot produce one surfaces as a
     * [CredentialUnavailableException], never as whatever the keystore threw.
     *
     * Why: re-minting a Device proof signs with the hardware key, and that key is
     * `setUnlockedDeviceRequired` (void-which-binds-client `DeviceKeyStore`), so once the
     * phone locks every re-mint fails with a `DEVICE_LOCKED` `InvalidKeyException` — or,
     * past the 1-hour user-auth window, an `AuthenticationRequiredException`. Both are
     * unchecked from OkHttp's point of view, and OkHttp rethrows a non-IO throwable from
     * an interceptor on its dispatcher thread, which killed the whole process: music
     * playing under the lock screen stopped about two minutes in, when the proof minted
     * before the lock was retired. As an [IOException] it is an ordinary failed read that
     * the caller (ExoPlayer, the image loader) retries or reports.
     */
    fun current(): String? = try {
        provider()
    } catch (e: IOException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        throw CredentialUnavailableException(e)
    }
}

/**
 * The live credential could not be produced for this request (the device key refused to
 * sign — typically because the phone is locked). Transient: it clears once the phone is
 * unlocked, so a reader may wait it out rather than fail.
 */
class CredentialUnavailableException(cause: Throwable) :
    IOException("credential unavailable: ${cause.message}", cause) {
    companion object {
        /** True when [error] is, or was caused by, a [CredentialUnavailableException]. Pure. */
        fun isCause(error: Throwable?): Boolean = generateSequence(error) { it.cause.takeIf { c -> c !== it } }
            .take(MAX_CAUSE_DEPTH)
            .any { it is CredentialUnavailableException }

        private const val MAX_CAUSE_DEPTH = 16
    }
}
