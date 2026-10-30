package fi.kasvila.reko

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * On-device appearance settings (teema, vaalea/tumma, fontti, tekstikoko).
 *
 * Kept deliberately separate from [AppState]: those settings are exchanged with
 * the desktop (rings, products, orders, deliveries, aliases), while appearance is
 * a per-device preference that must NEVER ride along in a sync/export file. So it
 * lives in its own little JSON file, exactly like [AiConfig] does for AI settings.
 */
@Serializable
data class UiPrefs(
    val preset: String = "kasvila",   // kasvila | neutraali | kirkas | lammin
    val mode: String = "system",      // system | light | dark
    val font: String = "roboto",      // roboto | inter | lexend | atkinson
    val textScale: Float = 1.0f       // 0.9 | 1.0 | 1.15 | 1.3
)

/** Persists [UiPrefs] to a private JSON file in internal storage. */
class UiPrefsStore(context: Context) {
    private val file = File(context.filesDir, "kasvila_ui_prefs.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        coerceInputValues = true
    }

    fun load(): UiPrefs = try {
        if (file.exists()) json.decodeFromString(file.readText()) else UiPrefs()
    } catch (e: Exception) {
        UiPrefs()
    }

    fun save(p: UiPrefs) {
        try {
            file.writeText(json.encodeToString(p))
        } catch (_: Exception) {
        }
    }
}

/**
 * The option catalogs the settings screen offers. Ids are stable and stored in
 * [UiPrefs]; labels are the Finnish text shown to the user. Keeping them here (not
 * in the UI) means the theme engine and the picker never drift apart.
 */
object UiThemes {
    /** preset id -> label shown in the picker. */
    val PRESETS: List<Pair<String, String>> = listOf(
        "kasvila" to "Kasvila (oletus)",
        "neutraali" to "Neutraali",
        "kirkas" to "Kirkas (ulkokäyttö)",
        "lammin" to "Lämmin"
    )

    /** Short Finnish description per preset, shown under the picker. */
    fun presetDesc(id: String): String = when (id) {
        "neutraali" -> "Rauhallinen grafiitti — vähentää silmien väsymystä pitkässä käytössä."
        "kirkas" -> "Suuri kontrasti — paras luettavuus kirkkaassa auringonvalossa."
        "lammin" -> "Lämmin sävy, vähemmän sinivaloa — miellyttävä hämärässä ja illalla."
        else -> "Brändin oliivinvihreä, nyt myös vaaleana."
    }

    val MODES: List<Pair<String, String>> = listOf(
        "system" to "Järjestelmän mukaan",
        "light" to "Vaalea",
        "dark" to "Tumma"
    )

    val FONTS: List<Pair<String, String>> = listOf(
        "roboto" to "Roboto (järjestelmä)",
        "inter" to "Inter",
        "lexend" to "Lexend",
        "atkinson" to "Atkinson Hyperlegible"
    )

    val SIZES: List<Pair<Float, String>> = listOf(
        0.9f to "Pieni",
        1.0f to "Normaali",
        1.15f to "Suuri",
        1.3f to "Erittäin suuri"
    )

    fun labelOf(list: List<Pair<String, String>>, id: String): String =
        list.firstOrNull { it.first == id }?.second ?: list.first().second

    fun sizeLabel(scale: Float): String =
        SIZES.firstOrNull { kotlin.math.abs(it.first - scale) < 0.001f }?.second ?: "Normaali"
}
