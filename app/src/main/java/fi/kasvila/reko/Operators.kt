package fi.kasvila.reko

import android.content.Context
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Multi-user "operators" (v53).
 *
 * Several people can share one device; each signs in for their shift with a
 * name + PIN, and their name is attributed to the receipts they issue (shown on
 * the receipt only if the producer turns it on). This is attribution, not hard
 * security — it sits under the device login. PINs are stored only as an
 * Argon2id hash, never in the clear.
 */
@Serializable
data class Operator(
    val id: String = "",
    val name: String = "",
    val pinSalt: String = "",   // base64
    val pinHash: String = "",   // base64 Argon2id raw hash
    val active: Boolean = true
) {
    val hasPin: Boolean get() = pinHash.isNotBlank()
}

@Serializable
data class OperatorState(
    val operators: List<Operator> = emptyList(),
    val currentId: String = ""
)

class OperatorStore(context: Context) {
    private val file = File(context.filesDir, "kasvila_operators.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    // PIN hashing params — light Argon2id; a PIN is low-entropy, so this is a
    // deterrent, consistent with "attribution, not hard security".
    private val iters = 2
    private val mem = 19456
    private val par = 1

    fun load(): OperatorState = try {
        if (file.exists()) json.decodeFromString(file.readText()) else OperatorState()
    } catch (e: Exception) {
        OperatorState()
    }

    private fun save(s: OperatorState) {
        try { file.writeText(json.encodeToString(s)) } catch (_: Exception) {}
    }

    /** Active operators, by name. */
    fun list(): List<Operator> = load().operators.filter { it.active }.sortedBy { it.name.lowercase() }

    fun current(): Operator? {
        val s = load()
        return s.operators.firstOrNull { it.id == s.currentId && it.active }
    }

    fun add(name: String, pin: String): Operator {
        val op = Operator(
            id = "op_" + UUID.randomUUID().toString().replace("-", "").take(8),
            name = name.trim(),
            pinSalt = "", pinHash = ""
        ).let { if (pin.isNotBlank()) withPin(it, pin) else it }
        val s = load()
        save(s.copy(operators = s.operators + op))
        return op
    }

    fun rename(id: String, name: String) {
        val s = load()
        save(s.copy(operators = s.operators.map { if (it.id == id) it.copy(name = name.trim()) else it }))
    }

    fun setPin(id: String, pin: String) {
        val s = load()
        save(s.copy(operators = s.operators.map { if (it.id == id) withPin(it, pin) else it }))
    }

    /** Soft-remove: mark inactive, and clear "current" if it was this one. */
    fun remove(id: String) {
        val s = load()
        save(
            s.copy(
                operators = s.operators.map { if (it.id == id) it.copy(active = false) else it },
                currentId = if (s.currentId == id) "" else s.currentId
            )
        )
    }

    fun verify(id: String, pin: String): Boolean {
        val op = load().operators.firstOrNull { it.id == id } ?: return false
        if (!op.hasPin) return true   // no PIN set → selecting is enough
        val salt = try { Base64.decode(op.pinSalt, Base64.NO_WRAP) } catch (e: Exception) { return false }
        val h = Base64.encodeToString(
            Crypto.deriveKey(pin, salt, iters, mem, par), Base64.NO_WRAP
        )
        return constantEquals(h, op.pinHash)
    }

    fun setCurrent(id: String) {
        save(load().copy(currentId = id))
    }

    fun clearCurrent() {
        save(load().copy(currentId = ""))
    }

    private fun withPin(op: Operator, pin: String): Operator {
        val salt = Crypto.randomBytes(16)
        val hash = Crypto.deriveKey(pin, salt, iters, mem, par)
        return op.copy(
            pinSalt = Base64.encodeToString(salt, Base64.NO_WRAP),
            pinHash = Base64.encodeToString(hash, Base64.NO_WRAP)
        )
    }

    private fun constantEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }
}
