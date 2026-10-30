package fi.kasvila.reko

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Local-network sync: finding the desktop and talking to it.
 *
 * ## Why this shape
 *
 * The desktop runs a tiny HTTP server only while its "Avaa yhteys" window is
 * open, announces itself over mDNS, and shows a six-digit code. The phone finds
 * it by name, the user types the code, and the two exchange full sync payloads.
 *
 * That buys a lot for very little: **no new Android permission** (INTERNET was
 * already there for the AI features), **no camera** and therefore no QR scanning
 * permission prompt, and nothing to configure — no IP addresses to read off a
 * screen unless discovery fails, in which case the address can still be typed.
 *
 * ## What the code is for
 *
 * The server is bound to the local network, but "on the same wifi" is not the
 * same as "authorised" — a café network has strangers on it. The code is a
 * short-lived, single-use shared secret that proves the person holding the phone
 * can see the desktop's screen. It is checked on every request, not just the
 * first, so a half-finished exchange can't be hijacked.
 *
 * ## Two phases, two humans
 *
 * `/exchange` moves data but changes nothing: each side ends up holding the
 * other's state and shows its own preview. Each side then applies only when the
 * person in front of *that* device confirms. So a sync can be accepted on the
 * phone and declined on the desktop without either ending up in a broken state —
 * the next sync simply reconciles what is still different.
 */

const val LAN_SERVICE_TYPE = "_kasvilareko._tcp."
const val LAN_DEFAULT_PORT = 8765

data class LanPeer(
    val name: String,
    val host: String,
    val port: Int
) {
    fun base(): String = "http://$host:$port"
    fun label(): String = "$name  ($host)"
}

sealed interface LanResult<out T> {
    data class Ok<T>(val value: T) : LanResult<T>
    data class Err(val message: String) : LanResult<Nothing>
}

data class LanHello(
    val deviceId: String,
    val deviceName: String,
    val version: Int
)

/**
 * mDNS discovery of desktops advertising the Kasvila service.
 *
 * Android's NsdManager is famously prone to leaking listeners: if discovery is
 * started twice or stopped twice the framework throws, and a listener left
 * registered keeps waking the radio. So this class owns exactly one listener,
 * tracks whether it is live, and makes [stop] safe to call any number of times —
 * including from a Compose `DisposableEffect` that may fire during recomposition.
 */
class LanDiscovery(context: Context) {

    private val nsd = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var listener: NsdManager.DiscoveryListener? = null
    private var running = false

    /** Serialises resolve() calls — older Android versions fail if one is already
     *  in flight, which is exactly what happens when several desktops answer. */
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start(onFound: (LanPeer) -> Unit, onError: (String) -> Unit) {
        val manager = nsd
        if (manager == null) {
            onError("Laitteen verkkopalveluhaku ei ole käytettävissä.")
            return
        }
        if (running) return

        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType?.contains("kasvilareko") != true) return
                synchronized(resolveQueue) {
                    resolveQueue.addLast(info)
                }
                pumpResolve(manager, onFound)
            }

            override fun onServiceLost(info: NsdServiceInfo) {}

            override fun onDiscoveryStopped(serviceType: String) {
                running = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                running = false
                onError("Haku ei käynnistynyt (virhe $errorCode).")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                running = false
            }
        }
        listener = l
        running = true
        try {
            manager.discoverServices(LAN_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            running = false
            listener = null
            onError("Haku ei käynnistynyt: ${e.message}")
        }
    }

    private fun pumpResolve(manager: NsdManager, onFound: (LanPeer) -> Unit) {
        val next = synchronized(resolveQueue) {
            if (resolving) return
            val item = resolveQueue.removeFirstOrNull() ?: return
            resolving = true
            item
        }
        @Suppress("DEPRECATION")
        manager.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                synchronized(resolveQueue) { resolving = false }
                pumpResolve(manager, onFound)
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                synchronized(resolveQueue) { resolving = false }
                val host = info.host?.hostAddress
                if (!host.isNullOrBlank()) {
                    onFound(
                        LanPeer(
                            name = info.serviceName ?: "Työpöytä",
                            host = host,
                            port = if (info.port > 0) info.port else LAN_DEFAULT_PORT
                        )
                    )
                }
                pumpResolve(manager, onFound)
            }
        })
    }

    fun stop() {
        val manager = nsd ?: return
        val l = listener ?: return
        listener = null
        if (!running) return
        running = false
        try {
            manager.stopServiceDiscovery(l)
        } catch (_: Exception) {
            // Already stopped by the framework — nothing to undo.
        }
        synchronized(resolveQueue) { resolveQueue.clear(); resolving = false }
    }
}

/**
 * The HTTP half. Blocking — call from a background dispatcher, exactly like
 * [AiClient]. Uses HttpURLConnection and org.json so no dependency is added.
 */
object LanClient {

    private const val CONNECT_MS = 4000
    private const val READ_MS = 30000

    /** Cheap reachability + code check before anything is sent. */
    fun hello(peer: LanPeer, code: String): LanResult<LanHello> = request(
        url = "${peer.base()}/kasvila/hello",
        method = "GET",
        code = code,
        body = null
    ).let { res ->
        when (res) {
            is LanResult.Err -> res
            is LanResult.Ok -> try {
                val o = JSONObject(res.value)
                LanResult.Ok(
                    LanHello(
                        deviceId = o.optString("device_id"),
                        deviceName = o.optString("device_name", "Työpöytä"),
                        version = o.optInt("version", 1)
                    )
                )
            } catch (e: Exception) {
                LanResult.Err("Vastaus ei ollut odotetussa muodossa.")
            }
        }
    }

    /**
     * Send this phone's full state and receive the desktop's. Moves data only —
     * neither side has written anything when this returns.
     */
    fun exchange(peer: LanPeer, code: String, payloadJson: String): LanResult<SyncPayload> =
        request(
            url = "${peer.base()}/kasvila/exchange",
            method = "POST",
            code = code,
            body = payloadJson
        ).let { res ->
            when (res) {
                is LanResult.Err -> res
                is LanResult.Ok -> when (val parsed = SyncFiles.parse(res.value)) {
                    is ParsedFile.Sync -> LanResult.Ok(parsed.payload)
                    is ParsedFile.LegacyRings ->
                        LanResult.Ok(SyncFiles.fromLegacyRings(parsed.payload, "legacy_desktop"))
                    is ParsedFile.LegacyBundle ->
                        LanResult.Ok(SyncFiles.fromLegacyBundle(parsed.payload, "legacy_desktop"))
                    is ParsedFile.Unsupported -> LanResult.Err(parsed.reason)
                }
            }
        }

    /**
     * Tell the desktop this phone has applied its side, so it can update its own
     * "last synced" mark for us. Best-effort: if it fails, the only cost is that
     * the next sync re-offers a few changes we have already taken, which the
     * merge handles as "no difference".
     */
    fun commit(peer: LanPeer, code: String): LanResult<Unit> =
        when (val r = request("${peer.base()}/kasvila/commit", "POST", code, "{}")) {
            is LanResult.Err -> r
            is LanResult.Ok -> LanResult.Ok(Unit)
        }

    private fun request(
        url: String,
        method: String,
        code: String,
        body: String?
    ): LanResult<String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                setRequestProperty("X-Kasvila-Code", code)
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""
            when {
                status in 200..299 -> LanResult.Ok(text)
                status == 401 || status == 403 ->
                    LanResult.Err("Väärä koodi, tai koodi on jo vanhentunut. Pyydä uusi koneelta.")
                status == 404 ->
                    LanResult.Err("Kone vastasi, mutta synkronointi ei ole auki. Avaa yhteys työpöytäohjelmassa.")
                status == 409 ->
                    LanResult.Err("Koneella on synkronointi jo kesken toisen laitteen kanssa.")
                else -> LanResult.Err("Kone vastasi virheellä $status.")
            }
        } catch (e: java.net.SocketTimeoutException) {
            LanResult.Err("Kone ei vastannut ajoissa. Onko se samassa verkossa ja yhteys auki?")
        } catch (e: java.net.ConnectException) {
            LanResult.Err("Yhteyttä ei saatu. Tarkista että olet samassa wifi-verkossa.")
        } catch (e: Exception) {
            LanResult.Err("Yhteysvirhe: ${e.message}")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /** Accept "192.168.1.23" or "192.168.1.23:8765" typed by hand. */
    fun parseManualAddress(text: String): LanPeer? {
        val t = text.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (t.isBlank()) return null
        val parts = t.split(":")
        val host = parts[0].trim()
        if (host.isBlank() || !host.any { it.isDigit() || it == '.' }) return null
        val port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: LAN_DEFAULT_PORT
        return LanPeer(name = "Työpöytä ($host)", host = host, port = port)
    }
}
