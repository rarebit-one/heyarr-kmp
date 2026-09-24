package one.rarebit.heyarr.mobile.device

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import one.rarebit.voidbind.AuthenticationRequiredException
import kotlin.coroutines.resume

/**
 * A user-presence check. The device signing key's hardware wrapping key is bound to a
 * short (30 s) post-authentication window (voidbind-client `DeviceKeyStore`): when the
 * window has lapsed, provisioning or signing throws [AuthenticationRequiredException],
 * the app shows this prompt, and retries inside the window. The hardware never signs
 * without a recent user-presence check — that refusal is the exception.
 */
interface BiometricGate {
    /** Show the prompt and suspend until the user resolves it. True on success. */
    suspend fun authenticate(title: String, subtitle: String): Boolean

    /**
     * Run a keystore operation, and if the hardware refuses for want of a recent user
     * authentication, prompt once and retry. Blocking (callers are already on
     * `Dispatchers.IO` — the prompt itself is dispatched to Main and awaited).
     */
    fun <T> gated(title: String, subtitle: String, block: () -> T): T = try {
        block()
    } catch (e: AuthenticationRequiredException) {
        val ok = runBlocking { authenticate(title, subtitle) }
        if (ok) block() else throw e
    }

    /** No prompt available (a headless context) — the keystore's refusal propagates. */
    object None : BiometricGate {
        override suspend fun authenticate(title: String, subtitle: String) = false
    }
}

/** Backed by androidx `BiometricPrompt`; must be constructed with a [FragmentActivity]. */
class AndroidBiometricGate(private val activity: FragmentActivity) : BiometricGate {

    override suspend fun authenticate(title: String, subtitle: String): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (cont.isActive) cont.resume(false)
                    }

                    override fun onAuthenticationFailed() {
                        // A single non-match — the prompt stays open for a retry.
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build()
            prompt.authenticate(info)
        }
    }
}
