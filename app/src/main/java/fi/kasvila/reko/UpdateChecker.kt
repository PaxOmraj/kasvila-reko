package fi.kasvila.reko

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app update check via GitHub Releases (v56).
 *
 * Asks GitHub for the latest published release of [REPO], compares its version to
 * the installed one, and — if newer — hands back the release notes and the APK
 * download link. Free (no server of our own), offline-safe (errors are quiet),
 * and uses only HttpURLConnection + org.json like [AiClient], so no new deps.
 *
 * Versioning contract: publish each GitHub release with a tag that contains the
 * app's versionCode as digits, e.g. tag "v56" for versionCode 56, and attach the
 * signed APK as a release asset. See GITHUB_JULKAISU.md.
 */
object UpdateChecker {

    /** owner/repo where signed APKs are published as GitHub Releases. */
    const val REPO = "PaxOmraj/kasvila-reko"

    sealed class UpdateResult {
        /** A newer release than the installed version is available. */
        data class Available(
            val versionCode: Int,
            val versionName: String,
            val notes: String,
            val apkUrl: String,   // direct .apk asset URL ("" if none attached)
            val pageUrl: String   // the release page, as a fallback
        ) : UpdateResult()

        /** Installed version is the newest (or nothing is published yet). */
        object UpToDate : UpdateResult()

        /** Check could not complete (no network, rate limit, etc.). */
        data class Error(val message: String) : UpdateResult()
    }

    /**
     * Blocking network call — run on a background dispatcher (Dispatchers.IO).
     * [currentVersionCode] is normally BuildConfig.VERSION_CODE.
     */
    fun check(currentVersionCode: Int): UpdateResult {
        try {
            val url = "https://api.github.com/repos/$REPO/releases/latest"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            // GitHub requires a User-Agent or it answers 403.
            conn.setRequestProperty("User-Agent", "KasvilaReko-UpdateChecker")
            conn.setRequestProperty("Accept", "application/vnd.github+json")

            val code = conn.responseCode
            // 404 = repo has no published release yet → treat as "up to date".
            if (code == 404) return UpdateResult.UpToDate
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""
            if (code !in 200..299) return UpdateResult.Error("http_$code")
            if (resp.isBlank()) return UpdateResult.Error("tyhjä vastaus")

            val obj = JSONObject(resp)
            // A draft/prerelease "latest" shouldn't be pushed to users.
            if (obj.optBoolean("draft", false)) return UpdateResult.UpToDate

            val tag = obj.optString("tag_name")
            val latest = parseVersion(tag)
            if (latest <= 0) return UpdateResult.Error("bad_tag")

            val notes = obj.optString("body")
            val pageUrl = obj.optString("html_url")

            var apkUrl = ""
            val assets = obj.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url")
                        break
                    }
                }
            }

            return if (latest > currentVersionCode) {
                UpdateResult.Available(
                    versionCode = latest,
                    versionName = tag.ifBlank { latest.toString() },
                    notes = notes,
                    apkUrl = apkUrl,
                    pageUrl = pageUrl
                )
            } else {
                UpdateResult.UpToDate
            }
        } catch (e: java.net.UnknownHostException) {
            return UpdateResult.Error("ei nettiyhteyttä")
        } catch (e: java.net.SocketTimeoutException) {
            return UpdateResult.Error("aikakatkaisu")
        } catch (e: javax.net.ssl.SSLException) {
            return UpdateResult.Error("TLS-virhe: ${e.message}")
        } catch (e: java.io.IOException) {
            return UpdateResult.Error("verkkovirhe: ${e.message}")
        } catch (e: Exception) {
            return UpdateResult.Error("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Pull the version number out of a tag like "v56" or "56" → 56. */
    private fun parseVersion(tag: String): Int =
        tag.filter { it.isDigit() }.toIntOrNull() ?: 0
}
