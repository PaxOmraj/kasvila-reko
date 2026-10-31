package fi.kasvila.reko

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Myyntiloki — the phone-local sales journal of issued receipts (v47).
 *
 * A phone-only producer gets a full bookkeeping trail here without the desktop:
 * every receipt, searchable, reprintable (a faithful copy from the stored lines),
 * and exportable as CSV for accounting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptLogView(
    state: AppState,
    prefs: ReceiptPrefs,
    onBack: () -> Unit,
    /** v59: jatka numerointia (esim. uudelleenasennuksen jälkeen). Arvo = seuraavan kuitin numero. */
    onSetNextNumber: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var askNumber by remember { mutableStateOf(false) }
    if (askNumber) {
        var text by remember { mutableStateOf((state.receiptCounter + 1).toString()) }
        val n = text.trim().toIntOrNull()
        val ok = n != null && n > state.receiptCounter
        AlertDialog(
            onDismissRequest = { askNumber = false },
            title = { Text("Seuraava kuittinumero") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Jatka numerointia esimerkiksi edellisen puhelimen tai sovelluksen " +
                        "viimeisestä kuitista: anna sitä seuraava numero. Numeroa voi vain nostaa.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(text, { text = it.filter { c -> c.isDigit() }.take(7) },
                        singleLine = true, label = { Text("Numero") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        isError = !ok)
                }
            },
            confirmButton = {
                TextButton(enabled = ok, onClick = { onSetNextNumber(n!!); askNumber = false }) { Text("Tallenna") }
            },
            dismissButton = { TextButton(onClick = { askNumber = false }) { Text("Peruuta") } }
        )
    }
    var expanded by remember { mutableStateOf<String?>(null) }
    var pendingThermal by remember { mutableStateOf<IssuedReceipt?>(null) }

    val all = remember(state.issuedReceipts) { state.issuedReceipts.reversed() }
    val receipts = remember(all, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) all
        else all.filter {
            it.number.lowercase().contains(q) ||
                it.customer.lowercase().contains(q) ||
                it.dateTime.lowercase().contains(q)
        }
    }

    fun hasBt(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

    fun runThermal(r: IssuedReceipt) {
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                ThermalPrinter.printReceipt(context, ReceiptBuilder.fromIssued(state, r), prefs)
            }
            Toast.makeText(context, err ?: "Kuitti tulostettu", Toast.LENGTH_LONG).show()
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val pend = pendingThermal
        pendingThermal = null
        if (pend != null && hasBt()) runThermal(pend)
        else if (pend != null) Toast.makeText(context, "Bluetooth-lupa tarvitaan tulostukseen", Toast.LENGTH_LONG).show()
    }

    fun onThermal(r: IssuedReceipt) {
        if (hasBt()) runThermal(r)
        else {
            pendingThermal = r
            permLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
        }
    }

    fun onShare(r: IssuedReceipt) {
        val d = ReceiptBuilder.fromIssued(state, r)
        val file = ReceiptPdf.build(context, d)
        if (file == null) {
            Toast.makeText(context, "Kuittikopion luonti epäonnistui", Toast.LENGTH_LONG).show()
            return
        }
        RekoPdf.deliver(context, file, "Kuitti ${d.number}", share = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Myyntiloki") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin")
                    }
                },
                actions = {
                    if (all.isNotEmpty()) {
                        IconButton(onClick = {
                            CsvExport.share(
                                context, "kasvila_myyntiloki.csv",
                                CsvExport.receipts(state.issuedReceipts), "Myyntiloki"
                            )
                        }) { Icon(Icons.Filled.Share, "Vie CSV") }
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            // v59: seuraava kuittinumero näkyviin ja muutettavaksi. Numeroa voi vain
            // nostaa, jotta samaa numeroa ei koskaan anneta kahdesti.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Seuraava kuittinumero: ${state.receiptCounter + 1}", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { askNumber = true }) { Text("Muuta") }
            }
            Spacer(Modifier.height(8.dp))
            if (all.isEmpty()) {
                Text(
                    "Ei vielä annettuja kuitteja. Kun tulostat tai jaat kuitin, se näkyy täällä.",
                    style = MaterialTheme.typography.bodyLarge
                )
                return@Column
            }

            val totalSum = all.sumOf { it.total }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        Reko.money(totalSum, state.currency),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${all.size} kuittia",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Haku (numero, asiakas, päivä)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            receipts.forEach { r ->
                val isOpen = expanded == r.number
                Card(
                    onClick = { expanded = if (isOpen) null else r.number },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    r.customer.ifBlank { "(asiakas)" },
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${r.number}  ·  ${prettyDate(r.dateTime)}  ·  ${Reko.paymentLabel(r.paymentMethod)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(Reko.money(r.total, state.currency), fontWeight = FontWeight.Bold)
                        }

                        if (isOpen) {
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(8.dp))
                            r.items.forEach { it ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                    Text(
                                        "${qtyText(it.qty)} ${it.unit}",
                                        modifier = Modifier.width(70.dp),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        it.name,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        Reko.money(it.lineTotal, state.currency),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { onThermal(r) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.Print, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Tulosta (BT)", maxLines = 1)
                                }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(
                                    onClick = { onShare(r) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.Share, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Jaa kopio", maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun qtyText(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

private fun prettyDate(iso: String): String {
    val t = iso.trim()
    if (t.length < 10) return t
    val d = t.substring(0, 10).split("-")
    return if (d.size == 3) "${d[2].trimStart('0')}.${d[1].trimStart('0')}.${d[0]}" else t.substring(0, 10)
}
