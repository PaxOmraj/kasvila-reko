package fi.kasvila.reko

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Update-checker settings (v56). Device-local — never synced — like [ReceiptPrefs].
 * Whether to auto-check, when we last checked, and a version the user chose to
 * skip are properties of this one install, not of the shared REKO data.
 */
@Serializable
data class UpdatePrefs(
    val autoCheck: Boolean = true,   // check GitHub for a new version on launch (throttled)
    val lastCheckIso: String = "",   // ISO-8601 instant of the last successful check
    val skipVersion: Int = 0         // a versionCode the user chose to hide the prompt for
)

/** Persists [UpdatePrefs] to a private JSON file in internal storage. */
class UpdatePrefsStore(context: Context) {
    private val file = File(context.filesDir, "kasvila_update_prefs.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun load(): UpdatePrefs = try {
        if (file.exists()) json.decodeFromString(file.readText()) else UpdatePrefs()
    } catch (e: Exception) {
        UpdatePrefs()
    }

    fun save(p: UpdatePrefs) {
        try {
            file.writeText(json.encodeToString(p))
        } catch (_: Exception) {
        }
    }
}
