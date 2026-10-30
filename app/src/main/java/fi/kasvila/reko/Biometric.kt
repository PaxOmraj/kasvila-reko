package fi.kasvila.reko

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Biometric unlock (v52).
 *
 * An app-level gate: on open, the app requires a fingerprint/face before it
 * releases the stored session key (see [SessionStore]). This is a convenience
 * gate on top of the encrypted-at-rest data, not a cryptographic binding of the
 * key to the biometric — a hardware-bound key can be added later. If biometrics
 * aren't set up, the app falls back to the password.
 */
object Biometric {

    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_WEAK

    /** True when the device has enrolled biometrics we can use. */
    fun canUse(context: Context): Boolean = try {
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS
    } catch (e: Exception) {
        false
    }

    /**
     * Show the biometric prompt. [onSuccess] on a good scan; [onFail] on a
     * terminal error or the user choosing the negative button (with a message /
     * null when simply cancelled).
     */
    fun authenticate(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFail: (String?) -> Unit
    ) {
        try {
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity, executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onSuccess()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        // Negative button / cancel are not real errors — just fall back.
                        val cancel = errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                            errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                            errorCode == BiometricPrompt.ERROR_CANCELED
                        onFail(if (cancel) null else errString.toString())
                    }
                    // onAuthenticationFailed (one bad scan) keeps the prompt open — ignore.
                }
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Kasvila REKO")
                .setSubtitle("Tunnistaudu avataksesi")
                .setNegativeButtonText("Käytä salasanaa")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()
            prompt.authenticate(info)
        } catch (e: Exception) {
            onFail(e.message)
        }
    }
}
