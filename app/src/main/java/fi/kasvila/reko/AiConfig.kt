package fi.kasvila.reko

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** On-device AI settings. The API key stays only on this phone. */
@Serializable
data class AiConfig(
    val provider: String = "mistral",
    val model: String = "mistral-small-latest",
    val keys: Map<String, String> = emptyMap(),   // provider -> api key
    val baseUrl: String = "",                      // for openai_compatible
    // v39: models fetched live from each provider (provider id -> model ids),
    // cached on device so the picker shows the latest models even offline.
    val fetchedModels: Map<String, List<String>> = emptyMap()
) {
    val apiKey: String get() = keys[provider] ?: ""

    /** Models to offer for [provider] in the picker: the live-fetched list when we
     *  have one, otherwise the built-in fallback baked into the app. */
    fun modelsFor(providerId: String): List<String> {
        val fetched = fetchedModels[providerId].orEmpty()
        if (fetched.isNotEmpty()) return fetched
        return AiProviders.byId(providerId)?.models.orEmpty()
    }
}

data class AiProvider(
    val id: String,
    val label: String,
    val endpoint: String,
    val auth: String,
    val models: List<String>,
    val customUrl: Boolean = false
)

object AiProviders {
    val list = listOf(
        AiProvider(
            "mistral", "Mistral (EU)",
            "https://api.mistral.ai/v1/chat/completions", "bearer",
            listOf("mistral-small-latest", "mistral-medium-latest", "mistral-large-latest")
        ),
        AiProvider(
            "anthropic", "Anthropic (Claude)",
            "https://api.anthropic.com/v1/messages", "x-api-key",
            listOf("claude-sonnet-4-6", "claude-opus-4-8", "claude-haiku-4-5", "claude-fable-5")
        ),
        AiProvider(
            "google", "Google (Gemini)",
            "https://generativelanguage.googleapis.com/v1beta/models", "x-goog-api-key",
            listOf("gemini-3.5-flash", "gemini-3.1-pro")
        ),
        AiProvider(
            "openai_compatible", "OpenAI-compatible (ChatGPT / custom)",
            "", "bearer", emptyList(), customUrl = true
        )
    )

    fun byId(id: String): AiProvider? = list.firstOrNull { it.id == id }
    fun defaultModel(id: String): String = byId(id)?.models?.firstOrNull() ?: ""
}

/** Persists AI settings to a private JSON file in internal storage. */
class AiStore(context: Context) {
    private val file = File(context.filesDir, "kasvila_ai_config.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun load(): AiConfig = try {
        if (file.exists()) json.decodeFromString(file.readText()) else AiConfig()
    } catch (e: Exception) {
        AiConfig()
    }

    fun save(c: AiConfig) {
        try {
            file.writeText(json.encodeToString(c))
        } catch (_: Exception) {
        }
    }
}
