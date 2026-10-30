package fi.kasvila.reko

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

sealed class AiResult {
    data class Ok(val text: String) : AiResult()
    data class Err(val code: String) : AiResult()
}

sealed class ModelsResult {
    data class Ok(val models: List<String>) : ModelsResult()
    data class Err(val code: String) : ModelsResult()
}

/**
 * Minimal multi-provider chat client. Mirrors the desktop ai_chat request and
 * response shapes for Anthropic (Claude), Google (Gemini), Mistral and any
 * OpenAI-compatible endpoint. Blocking — call it on a background dispatcher.
 * Uses only org.json + HttpURLConnection (no extra dependencies).
 */
object AiClient {

    fun normalizeOpenAiUrl(base: String): String {
        val u = base.trim().trimEnd('/')
        if (u.isEmpty()) return ""
        return if (u.endsWith("/chat/completions")) u else "$u/chat/completions"
    }

    /** Obvious non-chat models to hide from OpenAI-/Mistral-style lists, which also
     *  return embedding / audio / image models we can't use for order parsing. */
    private fun looksLikeChatModel(id: String): Boolean {
        val s = id.lowercase()
        val bad = listOf(
            "embed", "whisper", "tts", "audio", "transcrib", "moderation",
            "dall-e", "dalle", "image", "vision-embed", "rerank", "ocr", "code-embed"
        )
        return bad.none { s.contains(it) }
    }

    /**
     * v39: fetch the provider's CURRENT model list using the saved API key.
     * Blocking — call it on a background dispatcher. Returns model ids the user can
     * pick; the caller caches them so the picker stays up to date and works offline.
     */
    fun listModels(config: AiConfig): ModelsResult {
        val prov = AiProviders.byId(config.provider) ?: return ModelsResult.Err("bad_provider")
        val key = config.apiKey.trim()
        if (key.isEmpty()) return ModelsResult.Err("no_key")
        try {
            val url: String
            val headers = HashMap<String, String>()
            when (config.provider) {
                "anthropic" -> {
                    url = "https://api.anthropic.com/v1/models?limit=1000"
                    headers["x-api-key"] = key
                    headers["anthropic-version"] = "2023-06-01"
                }
                "google" -> {
                    // prov.endpoint is already the models list endpoint.
                    url = prov.endpoint + "?pageSize=1000"
                    headers["x-goog-api-key"] = key
                }
                "mistral" -> {
                    url = "https://api.mistral.ai/v1/models"
                    headers["Authorization"] = "Bearer $key"
                }
                else -> {  // openai_compatible
                    val base = config.baseUrl.trim().trimEnd('/')
                        .removeSuffix("/chat/completions").trimEnd('/')
                    if (base.isEmpty()) return ModelsResult.Err("no_url")
                    url = "$base/models"
                    headers["Authorization"] = "Bearer $key"
                }
            }

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            for ((k, v) in headers) conn.setRequestProperty(k, v)

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""
            if (code !in 200..299) return ModelsResult.Err("http_$code: " + resp.take(160))
            if (resp.isBlank()) return ModelsResult.Err("tyhjä vastaus palvelimelta")

            val data = JSONObject(resp)
            val ids = ArrayList<String>()
            when (config.provider) {
                "google" -> {
                    val arr = data.optJSONArray("models")
                    if (arr != null) for (i in 0 until arr.length()) {
                        val m = arr.optJSONObject(i) ?: continue
                        val methods = m.optJSONArray("supportedGenerationMethods")
                        var supportsChat = methods == null
                        if (methods != null) for (j in 0 until methods.length()) {
                            if (methods.optString(j) == "generateContent") { supportsChat = true; break }
                        }
                        if (!supportsChat) continue
                        var name = m.optString("name")   // e.g. "models/gemini-1.5-pro"
                        if (name.startsWith("models/")) name = name.substring(7)
                        if (name.isNotBlank()) ids.add(name)
                    }
                }
                "anthropic" -> {
                    val arr = data.optJSONArray("data")
                    if (arr != null) for (i in 0 until arr.length()) {
                        val id = arr.optJSONObject(i)?.optString("id").orEmpty()
                        if (id.isNotBlank()) ids.add(id)
                    }
                }
                else -> {  // mistral / openai_compatible: OpenAI-style {data:[{id}]}
                    val arr = data.optJSONArray("data")
                    if (arr != null) for (i in 0 until arr.length()) {
                        val id = arr.optJSONObject(i)?.optString("id").orEmpty()
                        if (id.isNotBlank() && looksLikeChatModel(id)) ids.add(id)
                    }
                }
            }
            val cleaned = ids.distinct().sorted()
            if (cleaned.isEmpty()) return ModelsResult.Err("ei malleja vastauksessa")
            return ModelsResult.Ok(cleaned)
        } catch (e: java.net.UnknownHostException) {
            return ModelsResult.Err("ei nettiyhteyttä")
        } catch (e: java.net.SocketTimeoutException) {
            return ModelsResult.Err("aikakatkaisu – verkko liian hidas")
        } catch (e: javax.net.ssl.SSLException) {
            return ModelsResult.Err("TLS-virhe: ${e.message}")
        } catch (e: java.io.IOException) {
            return ModelsResult.Err("verkkovirhe: ${e.message}")
        } catch (e: Exception) {
            return ModelsResult.Err("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun chat(config: AiConfig, system: String, user: String, maxTokens: Int = 1500): AiResult {
        val prov = AiProviders.byId(config.provider) ?: return AiResult.Err("bad_provider")
        val key = config.apiKey.trim()
        if (key.isEmpty()) return AiResult.Err("no_key")
        try {
            val url: String
            val body: JSONObject
            val headers = HashMap<String, String>()
            headers["content-type"] = "application/json"

            when (config.provider) {
                "anthropic" -> {
                    url = prov.endpoint
                    val msgs = JSONArray().put(
                        JSONObject().put("role", "user").put("content", user)
                    )
                    body = JSONObject()
                        .put("model", config.model)
                        .put("max_tokens", maxTokens)
                        .put("messages", msgs)
                    if (system.isNotBlank()) body.put("system", system)
                    headers["x-api-key"] = key
                    headers["anthropic-version"] = "2023-06-01"
                }
                "google" -> {
                    url = "${prov.endpoint}/${config.model}:generateContent"
                    val parts = JSONArray().put(JSONObject().put("text", user))
                    val contents = JSONArray().put(
                        JSONObject().put("role", "user").put("parts", parts)
                    )
                    val genCfg = JSONObject()
                        .put("maxOutputTokens", maxOf(maxTokens, 2048))
                        .put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                    body = JSONObject().put("contents", contents).put("generationConfig", genCfg)
                    if (system.isNotBlank()) {
                        body.put(
                            "systemInstruction",
                            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
                        )
                    }
                    headers["x-goog-api-key"] = key
                }
                else -> {  // mistral / openai_compatible
                    url = if (config.provider == "openai_compatible")
                        normalizeOpenAiUrl(config.baseUrl) else prov.endpoint
                    if (url.isEmpty()) return AiResult.Err("no_url")
                    val msgs = JSONArray()
                    if (system.isNotBlank())
                        msgs.put(JSONObject().put("role", "system").put("content", system))
                    msgs.put(JSONObject().put("role", "user").put("content", user))
                    body = JSONObject()
                        .put("model", config.model)
                        .put("max_tokens", maxTokens)
                        .put("messages", msgs)
                    headers["Authorization"] = "Bearer $key"
                }
            }

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.doOutput = true
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""
            if (code !in 200..299) return AiResult.Err("http_$code: " + resp.take(160))
            if (resp.isBlank()) return AiResult.Err("tyhj\u00e4 vastaus palvelimelta")

            val data = JSONObject(resp)
            val text: String = when (config.provider) {
                "anthropic" -> {
                    val sb = StringBuilder()
                    val arr = data.optJSONArray("content")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val p = arr.optJSONObject(i)
                            if (p != null && p.optString("type") == "text") sb.append(p.optString("text"))
                        }
                    }
                    sb.toString()
                }
                "google" -> {
                    val sb = StringBuilder()
                    val cands = data.optJSONArray("candidates")
                    if (cands != null && cands.length() > 0) {
                        val parts = cands.optJSONObject(0)
                            ?.optJSONObject("content")?.optJSONArray("parts")
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                sb.append(parts.optJSONObject(i)?.optString("text") ?: "")
                            }
                        }
                    }
                    sb.toString()
                }
                else -> {
                    val choices = data.optJSONArray("choices")
                    if (choices != null && choices.length() > 0)
                        choices.optJSONObject(0)?.optJSONObject("message")?.optString("content") ?: ""
                    else ""
                }
            }
            return AiResult.Ok(text.trim())
        } catch (e: java.net.UnknownHostException) {
            return AiResult.Err("ei nettiyhteytt\u00e4 (${e.message})")
        } catch (e: java.net.SocketTimeoutException) {
            return AiResult.Err("aikakatkaisu \u2013 verkko liian hidas")
        } catch (e: javax.net.ssl.SSLException) {
            return AiResult.Err("TLS-virhe: ${e.message}")
        } catch (e: java.io.IOException) {
            return AiResult.Err("verkkovirhe: ${e.message}")
        } catch (e: Exception) {
            return AiResult.Err("${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
