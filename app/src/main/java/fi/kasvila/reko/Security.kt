package fi.kasvila.reko

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Account security and data-at-rest encryption (v48).
 *
 * Design — two keys, so password changes never re-encrypt the data:
 *  - A random 256-bit **DEK** encrypts the app state (AES-256-GCM).
 *  - The DEK is stored twice, wrapped: once under a key derived from the
 *    **password** (Argon2id), once under a key derived from a one-time
 *    **recovery code**. GCM's authentication tag doubles as the password check —
 *    a wrong password simply fails to unwrap the DEK, so no password hash is
 *    stored separately.
 *  - Changing the password re-wraps the small DEK, not the whole database.
 *  - Forgetting BOTH password and recovery code makes the data unrecoverable —
 *    that is the point of encryption, and the setup screen says so.
 *
 * Only wrapped keys and random salts are persisted (kasvila_auth.json); no
 * secret is ever stored in the clear.
 */
object Crypto {
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private val rnd = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rnd.nextBytes(it) }

    /** AES-256-GCM. Output is iv(12) || ciphertext || tag. */
    fun aesGcmEncrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = randomBytes(GCM_IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(plaintext)
    }

    /** Decrypt output of [aesGcmEncrypt]. Returns null if the key is wrong or the
     *  data is not valid ciphertext (used to distinguish encrypted from legacy). */
    fun aesGcmDecrypt(key: ByteArray, blob: ByteArray): ByteArray? = try {
        if (blob.size <= GCM_IV_BYTES) null else {
            val iv = blob.copyOfRange(0, GCM_IV_BYTES)
            val ct = blob.copyOfRange(GCM_IV_BYTES, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ct)
        }
    } catch (e: Exception) {
        null
    }

    /** Derive a 32-byte key from a secret with Argon2id. */
    fun deriveKey(secret: String, salt: ByteArray, iters: Int, memKib: Int, parallelism: Int): ByteArray {
        val argon2 = Argon2Kt()
        val result = argon2.hash(
            mode = Argon2Mode.ARGON2_ID,
            password = secret.toByteArray(Charsets.UTF_8),
            salt = salt,
            tCostInIterations = iters,
            mCostInKibibyte = memKib,
            parallelism = parallelism,
            hashLengthInBytes = 32
        )
        return result.rawHashAsByteArray()
    }
}

/** Persisted account record. Contains only wrapped keys + salts — no secrets. */
@Serializable
data class AuthData(
    val version: Int = 1,
    val username: String = "",
    val pwSalt: String = "",     // base64
    val encDekPw: String = "",   // base64: DEK wrapped by the password key
    val recSalt: String = "",
    val encDekRec: String = "",  // base64: DEK wrapped by the recovery-code key
    val kdfIters: Int = 3,
    val kdfMem: Int = 19456,     // 19 MiB — OWASP mobile-friendly Argon2id
    val kdfPar: Int = 1
)

class SecurityStore(context: Context) {
    private val file = File(context.filesDir, "kasvila_auth.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    data class SetupResult(val dek: ByteArray, val recoveryCode: String)

    fun hasAccount(): Boolean = load()?.username?.isNotBlank() == true

    fun load(): AuthData? = try {
        if (file.exists()) json.decodeFromString(file.readText()) else null
    } catch (e: Exception) {
        null
    }

    private fun save(a: AuthData) {
        try { file.writeText(json.encodeToString(a)) } catch (_: Exception) {}
    }

    /** Create the account: mint a DEK, wrap it under the password and a fresh
     *  recovery code, persist. Returns the DEK (to unlock now) and the recovery
     *  code (to show the user once). */
    fun setup(username: String, password: String): SetupResult {
        val dek = Crypto.randomBytes(32)
        val recoveryCode = genRecoveryCode()
        val iters = 3; val mem = 19456; val par = 1
        val pwSalt = Crypto.randomBytes(16)
        val recSalt = Crypto.randomBytes(16)
        val pwKey = Crypto.deriveKey(password, pwSalt, iters, mem, par)
        val recKey = Crypto.deriveKey(canonicalCode(recoveryCode), recSalt, iters, mem, par)
        save(
            AuthData(
                username = username.trim(),
                pwSalt = b64(pwSalt), encDekPw = b64(Crypto.aesGcmEncrypt(pwKey, dek)),
                recSalt = b64(recSalt), encDekRec = b64(Crypto.aesGcmEncrypt(recKey, dek)),
                kdfIters = iters, kdfMem = mem, kdfPar = par
            )
        )
        return SetupResult(dek, recoveryCode)
    }

    /** Unlock with the password. Returns the DEK, or null on a wrong password. */
    fun unlockWithPassword(password: String): ByteArray? {
        val a = load() ?: return null
        val key = Crypto.deriveKey(password, unb64(a.pwSalt), a.kdfIters, a.kdfMem, a.kdfPar)
        return Crypto.aesGcmDecrypt(key, unb64(a.encDekPw))
    }

    /** Unlock with the recovery code. Returns the DEK, or null on a wrong code. */
    fun unlockWithRecovery(code: String): ByteArray? {
        val a = load() ?: return null
        val key = Crypto.deriveKey(canonicalCode(code), unb64(a.recSalt), a.kdfIters, a.kdfMem, a.kdfPar)
        return Crypto.aesGcmDecrypt(key, unb64(a.encDekRec))
    }

    /** Re-wrap the DEK under a new password. */
    fun changePassword(dek: ByteArray, newPassword: String): Boolean = try {
        val a = load() ?: return false
        val salt = Crypto.randomBytes(16)
        val key = Crypto.deriveKey(newPassword, salt, a.kdfIters, a.kdfMem, a.kdfPar)
        save(a.copy(pwSalt = b64(salt), encDekPw = b64(Crypto.aesGcmEncrypt(key, dek))))
        true
    } catch (e: Exception) {
        false
    }

    /** Generate a new recovery code, re-wrapping the DEK under it. Returns the code. */
    fun regenerateRecovery(dek: ByteArray): String? = try {
        val a = load() ?: return null
        val code = genRecoveryCode()
        val salt = Crypto.randomBytes(16)
        val key = Crypto.deriveKey(canonicalCode(code), salt, a.kdfIters, a.kdfMem, a.kdfPar)
        save(a.copy(recSalt = b64(salt), encDekRec = b64(Crypto.aesGcmEncrypt(key, dek))))
        code
    } catch (e: Exception) {
        null
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    /** Display form: 4×5 letters/digits with dashes, no easily-confused characters. */
    private fun genRecoveryCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val bytes = Crypto.randomBytes(20)
        val sb = StringBuilder()
        for (i in 0 until 20) {
            if (i > 0 && i % 5 == 0) sb.append('-')
            sb.append(alphabet[(bytes[i].toInt() and 0xFF) % alphabet.length])
        }
        return sb.toString()
    }

    /** Canonical form used for key derivation, so dashes/spaces/case don't matter. */
    private fun canonicalCode(code: String): String =
        code.uppercase().filter { it.isLetterOrDigit() }
}

/**
 * "Stay logged in" (v50). Keeps the app open across restarts without re-entering
 * the password, by wrapping the DEK with a hardware-backed Android Keystore key
 * and storing that wrapped blob on the device. The data stays encrypted at rest;
 * the Keystore key protects the wrapped DEK. Logging out deletes both.
 *
 * Tradeoff: while a session is stored, anyone who can open the app on an unlocked
 * device is in — that is what "stay logged in" means. A shared device should use
 * biometric unlock (next step) or turn this off.
 */
object SessionKeystore {
    private const val ALIAS = "kasvila_session_dek_v1"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    /** Wrap the DEK with the Keystore key. Returns base64 iv||ciphertext, or null. */
    fun wrap(dek: ByteArray): String? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(dek)
        Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }

    fun unwrap(blob: String): ByteArray? = try {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        if (bytes.size <= GCM_IV_BYTES) null else {
            val iv = bytes.copyOfRange(0, GCM_IV_BYTES)
            val ct = bytes.copyOfRange(GCM_IV_BYTES, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ct)
        }
    } catch (e: Exception) {
        null
    }

    fun deleteKey() {
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
        } catch (_: Exception) {
        }
    }
}

@Serializable
data class SessionData(
    val dekWrap: String = "",
    val stayLoggedIn: Boolean = true,
    val biometricRequired: Boolean = false
)

/** Persists the "stay logged in" preference and the Keystore-wrapped DEK. */
class SessionStore(context: Context) {
    private val file = File(context.filesDir, "kasvila_session.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun load(): SessionData = try {
        if (file.exists()) json.decodeFromString(file.readText()) else SessionData()
    } catch (e: Exception) {
        SessionData()
    }

    private fun save(s: SessionData) {
        try { file.writeText(json.encodeToString(s)) } catch (_: Exception) {}
    }

    fun stayLoggedIn(): Boolean = load().stayLoggedIn
    fun biometricRequired(): Boolean = load().biometricRequired
    fun hasSession(): Boolean = load().dekWrap.isNotBlank()

    /** Store the DEK for auto-unlock, if "stay logged in" is on. */
    fun persist(dek: ByteArray) {
        val s = load()
        if (!s.stayLoggedIn) return
        val wrap = SessionKeystore.wrap(dek) ?: return
        save(s.copy(dekWrap = wrap))
    }

    /** The stored DEK for auto-unlock, or null when there is none / it failed. */
    fun restore(): ByteArray? {
        val s = load()
        if (s.dekWrap.isBlank()) return null
        return SessionKeystore.unwrap(s.dekWrap)
    }

    fun setStayLoggedIn(enabled: Boolean, dek: ByteArray? = null) {
        val s = load()
        if (enabled) {
            val wrap = dek?.let { SessionKeystore.wrap(it) } ?: s.dekWrap
            save(s.copy(dekWrap = wrap, stayLoggedIn = true))
        } else {
            // Turning off stay-logged-in also turns off biometric (nothing to gate).
            save(s.copy(dekWrap = "", stayLoggedIn = false, biometricRequired = false))
            SessionKeystore.deleteKey()
        }
    }

    /** Require biometric before the stored session is used. Enabling it also makes
     *  sure a session exists (biometric gates that session). */
    fun setBiometricRequired(enabled: Boolean, dek: ByteArray? = null) {
        val s = load()
        if (enabled) {
            val wrap = if (s.dekWrap.isNotBlank()) s.dekWrap
            else (dek?.let { SessionKeystore.wrap(it) } ?: "")
            save(s.copy(dekWrap = wrap, stayLoggedIn = true, biometricRequired = true))
        } else {
            save(s.copy(biometricRequired = false))
        }
    }

    /** Log out: drop the stored session but keep the preferences. */
    fun clear() {
        save(load().copy(dekWrap = ""))
        SessionKeystore.deleteKey()
    }
}
