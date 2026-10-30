package fi.kasvila.ostot

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lähiverkkoyhteys työpöytään. Sama yhteysikkuna ja koodi kuin REKO-
 * synkronoinnissa (työpöydällä REKO → Synkronointi → Avaa yhteys, tai Varasto →
 * Puhelimesta → Lähiverkko). Ostot käyttää omaa osoitettaan /kasvila/osto:
 * yksi pyyntö vie valmiit kerrat ja tuo vastauksena tuoreen luettelon.
 *
 * Tämä on tarkoituksella erillinen kopio REKO-puolen haun perusideasta, jotta
 * Ostot-moduuli ei riipu sovelluksen koodista.
 */

const val OSTO_SERVICE_TYPE = "_kasvilareko._tcp."
const val OSTO_DEFAULT_PORT = 8765

data class OstoPeer(val name: String, val host: String, val port: Int) {
    fun base(): String = "http://$host:$port"
    fun label(): String = "$name  ($host)"
}

class OstoDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var listener: NsdManager.DiscoveryListener? = null
    private var running = false
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start(onFound: (OstoPeer) -> Unit, onError: (String) -> Unit) {
        val manager = nsd ?: run { onError("Verkkopalveluhaku ei ole käytettävissä."); return }
        if (running) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType?.contains("kasvilareko") != true) return
                synchronized(queue) { queue.addLast(info) }
                pump(manager, onFound)
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) { running = false }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                running = false
                onError("Haku ei käynnistynyt (virhe $errorCode).")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { running = false }
        }
        listener = l
        running = true
        try {
            manager.discoverServices(OSTO_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            running = false
            listener = null
            onError("Haku ei käynnistynyt: ${e.message}")
        }
    }

    private fun pump(manager: NsdManager, onFound: (OstoPeer) -> Unit) {
        val next = synchronized(queue) {
            if (resolving) return
            val item = queue.removeFirstOrNull() ?: return
            resolving = true
            item
        }
        @Suppress("DEPRECATION")
        manager.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                synchronized(queue) { resolving = false }
                pump(manager, onFound)
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                synchronized(queue) { resolving = false }
                @Suppress("DEPRECATION")
                val host = info.host?.hostAddress
                if (!host.isNullOrBlank()) {
                    onFound(OstoPeer(info.serviceName ?: "Työpöytä", host,
                        if (info.port > 0) info.port else OSTO_DEFAULT_PORT))
                }
                pump(manager, onFound)
            }
        })
    }

    fun stop() {
        val manager = nsd ?: return
        val l = listener ?: return
        listener = null
        if (!running) return
        running = false
        try { manager.stopServiceDiscovery(l) } catch (_: Exception) {}
        synchronized(queue) { queue.clear(); resolving = false }
    }
}

sealed interface OstoLanResult {
    data class Ok(val catalog: OstoCatalog) : OstoLanResult
    data class Err(val message: String) : OstoLanResult
}

object OstoLan {
    private const val CONNECT_MS = 4000
    private const val READ_MS = 30000

    /** Estävä kutsu: aja taustasäikeessä (Dispatchers.IO). */
    fun send(peer: OstoPeer, code: String, uploadJson: String): OstoLanResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("${peer.base()}/kasvila/osto").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                doOutput = true
                setRequestProperty("X-Kasvila-Code", code.trim())
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { it.write(uploadJson.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            when {
                status in 200..299 -> when (val p = Osto.parseCatalog(text)) {
                    is Osto.Parsed.Catalog -> OstoLanResult.Ok(p.catalog)
                    is Osto.Parsed.Error -> OstoLanResult.Err(p.reason)
                }
                status == 400 -> OstoLanResult.Err(
                    "Työpöytä ei hyväksynyt lähetystä: " + errorText(text))
                status == 401 || status == 403 ->
                    OstoLanResult.Err("Väärä koodi, tai koodi on vanhentunut. Katso uusi koodi koneelta.")
                status == 404 ->
                    OstoLanResult.Err("Kone vastasi, mutta ei tunne ostoja. Päivitä työpöytäohjelma (TK_162 tai uudempi).")
                status == 409 ->
                    OstoLanResult.Err("Koneella on yhteys jo toisen laitteen kanssa.")
                else -> OstoLanResult.Err("Kone vastasi virheellä $status.")
            }
        } catch (e: java.net.SocketTimeoutException) {
            OstoLanResult.Err("Kone ei vastannut ajoissa. Onko se samassa verkossa ja yhteysikkuna auki?")
        } catch (e: java.net.ConnectException) {
            OstoLanResult.Err("Yhteyttä ei saatu. Tarkista, että puhelin on samassa wifi-verkossa.")
        } catch (e: Exception) {
            OstoLanResult.Err("Yhteysvirhe: ${e.message}")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun errorText(body: String): String =
        Regex("\"error\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: body.take(200)

    /** "192.168.1.23" tai "192.168.1.23:8765" käsin kirjoitettuna. */
    fun parseManual(text: String): OstoPeer? {
        val t = text.trim().removePrefix("http://").trimEnd('/')
        if (t.isBlank()) return null
        val parts = t.split(":")
        val host = parts[0].trim()
        if (host.isBlank()) return null
        val port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: OSTO_DEFAULT_PORT
        return OstoPeer("Työpöytä", host, port)
    }
}
