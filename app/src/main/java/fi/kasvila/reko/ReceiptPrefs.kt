package fi.kasvila.reko

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Thermal-printer settings (v46). Device-local — never synced — so it lives in
 * its own JSON file like [AiStore], not in the synced [AppState]. Which printer
 * and how wide its paper is are properties of this one phone + its Bluetooth
 * printer, not of the shared REKO data.
 */
@Serializable
data class ReceiptPrefs(
    val printerAddress: String = "",   // Bluetooth MAC; "" = use the first paired printer
    val printerName: String = "",      // shown in settings
    val paperWidthMm: Int = 58,        // 58 or 80
    val showOperator: Boolean = false  // print "Palvelija: X" on the receipt
)

/** Persists [ReceiptPrefs] to a private JSON file in internal storage. */
class ReceiptPrefsStore(context: Context) {
    private val file = File(context.filesDir, "kasvila_receipt_prefs.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun load(): ReceiptPrefs = try {
        if (file.exists()) json.decodeFromString(file.readText()) else ReceiptPrefs()
    } catch (e: Exception) {
        ReceiptPrefs()
    }

    fun save(p: ReceiptPrefs) {
        try {
            file.writeText(json.encodeToString(p))
        } catch (_: Exception) {
        }
    }
}
