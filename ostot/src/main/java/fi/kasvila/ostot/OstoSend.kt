package fi.kasvila.ostot

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Lähetys työpöydälle ja luettelon haku. Kaksi tapaa:
 *  1. Lähiverkko: sama yhteysikkuna ja 6-numeroinen koodi kuin REKO-synkronoinnissa.
 *     Yksi painallus vie valmiit kerrat ja tuo tuoreen luettelon.
 *  2. Tiedosto: kun koneet eivät ole samassa verkossa.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SendView(
    state: OstoState,
    deviceId: String,
    deviceName: String,
    onBack: () -> Unit,
    onUpdate: ((OstoState) -> OstoState) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sendable = Osto.sendable(state)
    val unsent = Osto.unsentCount(state)

    val peers = remember { mutableStateListOf<OstoPeer>() }
    var chosen by remember { mutableStateOf<OstoPeer?>(null) }
    var manual by remember { mutableStateOf(state.lastHost) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var discoveryError by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        val d = OstoDiscovery(context)
        d.start(
            onFound = { p ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (peers.none { it.host == p.host && it.port == p.port }) peers += p
                    if (chosen == null) chosen = p
                }
            },
            onError = { e -> android.os.Handler(android.os.Looper.getMainLooper()).post { discoveryError = e } }
        )
        onDispose { d.stop() }
    }

    fun sendLan() {
        val peer = chosen ?: OstoLan.parseManual(manual)
        if (peer == null) {
            message = "Koneen osoite puuttuu. Odota, että kone löytyy, tai kirjoita osoite."
            return
        }
        if (code.trim().length < 4) {
            message = "Kirjoita koodi, jonka työpöydän yhteysikkuna näyttää."
            return
        }
        busy = true
        message = ""
        val body = Osto.buildUpload(state, deviceId, deviceName)
        val n = sendable.size
        scope.launch {
            val res = withContext(Dispatchers.IO) { OstoLan.send(peer, code, body) }
            busy = false
            when (res) {
                is OstoLanResult.Ok -> {
                    onUpdate { st -> Osto.applyCatalog(st, res.catalog, "lan").copy(lastHost = peer.host) }
                    val got = res.catalog.received.size
                    message = (if (n > 0) "Lähetetty $got/$n kertaa. " else "") +
                        "Luettelo päivitetty: ${res.catalog.items.size} tuotetta. " +
                        (if (got > 0) "Hyväksy ne työpöydällä: Varasto → Puhelimesta." else "")
                }
                is OstoLanResult.Err -> message = res.message
            }
        }
    }

    val saveFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val body = Osto.buildUpload(state, deviceId, deviceName)
        val ids = sendable.map { it.id }.toSet()
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }.isSuccess
        if (ok) {
            val now = Osto.nowIso()
            onUpdate { st ->
                st.copy(sessions = st.sessions.map {
                    if (it.id in ids) it.copy(sentAt = now, sentVia = "file", changedAfterSend = false) else it
                })
            }
            message = "Tallennettu ${ids.size} kertaa. Vie tiedosto koneelle ja avaa se: Varasto → Puhelimesta → Tuo puhelimen tiedosto."
        } else message = "Tallennus epäonnistui."
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        message = when (val p = if (text == null) Osto.Parsed.Error("Tiedostoa ei voitu lukea.") else Osto.parseCatalog(text)) {
            is Osto.Parsed.Catalog -> {
                onUpdate { st -> Osto.applyCatalog(st, p.catalog, "file") }
                "Luettelo päivitetty: ${p.catalog.items.size} tuotetta."
            }
            is Osto.Parsed.Error -> p.reason
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Lähetä ja päivitä") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin") } }
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                when {
                    sendable.isEmpty() -> "Ei valmiita kertoja lähetettäväksi. Voit silti hakea tuoreen luettelon."
                    unsent == 0 -> "${sendable.size} kertaa odottaa työpöydällä. Ne lähetetään uudelleen, jotta tila päivittyy."
                    else -> "Lähetettävänä ${sendable.size} kertaa, joista $unsent uutta tai muuttunutta."
                } + if (state.learned.isNotEmpty()) " Mukana ${state.learned.size} opittua viivakoodia." else "",
                style = MaterialTheme.typography.bodyMedium
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Lähiverkko", fontWeight = FontWeight.Bold)
                    Text(
                        "Avaa työpöydällä Varasto → 📱 Puhelimesta → Avaa yhteys puhelimeen. Ikkuna näyttää koodin.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (peers.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.padding(end = 8.dp).size(24.dp))
                            Text(discoveryError.ifBlank { "Etsitään konetta…" }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    for (p in peers) {
                        Row(
                            Modifier.fillMaxWidth().clickable { chosen = p },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(chosen == p, { chosen = p })
                            Text(p.label())
                        }
                    }
                    OutlinedTextField(
                        manual, { manual = it; if (it.isNotBlank()) chosen = null }, singleLine = true,
                        label = { Text("Tai koneen osoite (esim. 192.168.1.23)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        code, { code = it.filter { c -> c.isDigit() }.take(8) }, singleLine = true,
                        label = { Text("Koodi") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = { sendLan() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (sendable.isEmpty()) "Hae luettelo" else "Lähetä ja hae luettelo")
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tiedostona", fontWeight = FontWeight.Bold)
                    Text(
                        "Kun puhelin ja kone eivät ole samassa verkossa. Siirrä tiedosto esimerkiksi " +
                            "sähköpostilla tai muistitikulla.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(
                        onClick = { saveFile.launch("kasvila-ostot-${LocalDate.now()}.json") },
                        enabled = sendable.isNotEmpty() || state.learned.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Tallenna lähetys tiedostoksi") }
                    OutlinedButton(
                        onClick = { openFile.launch(arrayOf("application/json", "text/*", "*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Avaa työpöydän luettelotiedosto") }
                }
            }

            if (busy) Text("Lähetetään…")
            if (message.isNotBlank()) Text(message, fontWeight = FontWeight.Bold)
        }
    }
}
