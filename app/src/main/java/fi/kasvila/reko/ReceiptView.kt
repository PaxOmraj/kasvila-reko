package fi.kasvila.reko

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The receipt screen (v45, Vaihe 1).
 *
 * One receipt covers a customer's whole pickup — all the [orders] and
 * [deliveries] handed over now. The producer picks the payment method, previews
 * the receipt, and either shares it electronically or prints/saves it as a PDF.
 * Issuing allocates the running number, records the receipt in the phone-local
 * sales log, and (by default) marks the orders paid. Bluetooth thermal printing
 * and the QR image arrive in Vaihe 2.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptView(
    state: AppState,
    orders: List<Order>,
    deliveries: List<Delivery>,
    onBack: () -> Unit,
    onOpenSeller: () -> Unit,
    onIssued: (receipt: IssuedReceipt, newCounter: Int, markPaid: Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefsStore = remember { ReceiptPrefsStore(context) }
    val prefs = remember { prefsStore.load() }
    val operatorStore = remember { OperatorStore(context) }
    var currentOperator by remember { mutableStateOf(operatorStore.current()) }
    var showPicker by remember { mutableStateOf(false) }
    var pinFor by remember { mutableStateOf<Operator?>(null) }
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    var paymentMethod by remember { mutableStateOf(deliveries.firstOrNull()?.paymentMethod ?: "cash") }
    var markPaid by remember { mutableStateOf(true) }
    var issuing by remember { mutableStateOf(false) }

    val nextCounter = state.receiptCounter + 1
    val data = remember(orders, deliveries, paymentMethod, state.receiptCounter, state.sellerInfo) {
        ReceiptBuilder.build(state, orders, deliveries, paymentMethod, nextCounter, currentOperator?.name ?: "", prefs.showOperator)
    }
    val sellerReady = state.sellerInfo.isComplete()

    fun issuedFrom(d: ReceiptData): IssuedReceipt =
        d.toIssued(orders.map { it.id }, deliveries.map { it.id }, state.deviceId)

    // Share (electronic) or Android print / Save-as-PDF.
    fun issuePdf(share: Boolean) {
        if (issuing) return
        issuing = true
        val d = ReceiptBuilder.build(state, orders, deliveries, paymentMethod, nextCounter)
        val file = ReceiptPdf.build(context, d)
        if (file == null) {
            Toast.makeText(context, "Kuitin luonti epäonnistui", Toast.LENGTH_LONG).show()
            issuing = false
            return
        }
        RekoPdf.deliver(context, file, "Kuitti ${d.number}", share)
        onIssued(issuedFrom(d), nextCounter, markPaid)
    }

    fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

    fun thermalPrint() {
        if (issuing) return
        issuing = true
        val d = ReceiptBuilder.build(state, orders, deliveries, paymentMethod, nextCounter)
        scope.launch {
            val err = withContext(Dispatchers.IO) { ThermalPrinter.printReceipt(context, d, prefs) }
            if (err == null) {
                onIssued(issuedFrom(d), nextCounter, markPaid)
            } else {
                Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                issuing = false
            }
        }
    }

    val btPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (hasBtPermission()) thermalPrint()
        else Toast.makeText(context, "Bluetooth-lupa tarvitaan tulostukseen", Toast.LENGTH_LONG).show()
    }

    fun onThermalClick() {
        if (hasBtPermission()) thermalPrint()
        else btPermLauncher.launch(
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kuitti") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            if (!sellerReady) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "Myyjän tiedot puuttuvat",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            "Lisää vähintään nimi ja Y-tunnus, jotta kuitti on lainmukainen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onOpenSeller) { Text("Täytä myyjän tiedot") }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            // ── Receipt preview ────────────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        state.sellerInfo.name.ifBlank { "Myyjä" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    state.sellerInfo.headerLines().forEach {
                        Text(it, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text("KUITTI", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("Nro ${data.number}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (data.customer.isNotBlank()) {
                        Text("Asiakas: ${data.customer}", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))

                    if (data.items.isEmpty()) {
                        Text("Ei rivejä.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        data.items.forEach { it ->
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
                                    Reko.money(it.lineTotal, data.currency),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text("Yhteensä", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(Reko.money(data.total, data.currency), fontWeight = FontWeight.Bold)
                    }

                    if (data.vatLines.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("ALV-erittely", style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold)
                        VatHeader()
                        data.vatLines.forEach { v ->
                            VatRow(
                                "${rateText(v.rate)} %",
                                Reko.money(v.net, data.currency),
                                Reko.money(v.vat, data.currency),
                                Reko.money(v.gross, data.currency)
                            )
                        }
                    }
                }
            }

            if (operatorStore.list().isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Palvelija: ${currentOperator?.name ?: "ei valittu"}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = { showPicker = true }) { Text("Vaihda") }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Payment method ─────────────────────────────────────────────
            Text("Maksutapa", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            FlowRowChips(
                selected = paymentMethod,
                onSelect = { paymentMethod = it }
            )

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = markPaid, onCheckedChange = { markPaid = it })
                Text("Merkitse tilaukset maksetuiksi")
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onThermalClick() },
                enabled = !issuing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Print, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Tulosta (Bluetooth-tulostin)", maxLines = 1)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { issuePdf(share = true) },
                    enabled = !issuing,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Share, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Jaa kuitti", maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { issuePdf(share = false) },
                    enabled = !issuing,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Tallenna PDF", maxLines = 1)
                }
            }
            Text(
                "Bluetooth-tulostus käyttää \"Kuitti ja tulostin\" -asetuksissa valittua " +
                    "tulostinta. \"Jaa\" lähettää sähköisen kuitin; \"Tallenna PDF\" käyttää " +
                    "Androidin tulostusta.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(32.dp))

            if (showPicker) {
                AlertDialog(
                    onDismissRequest = { showPicker = false },
                    title = { Text("Valitse palvelija") },
                    text = {
                        Column {
                            operatorStore.list().forEach { op ->
                                TextButton(
                                    onClick = {
                                        if (op.hasPin) { pinFor = op; pinInput = ""; pinError = null }
                                        else {
                                            operatorStore.setCurrent(op.id)
                                            currentOperator = op
                                            showPicker = false
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(op.name, modifier = Modifier.fillMaxWidth()) }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Sulje") } }
                )
            }
            pinFor?.let { op ->
                AlertDialog(
                    onDismissRequest = { pinFor = null },
                    title = { Text("PIN – ${op.name}") },
                    text = {
                        Column {
                            OutlinedTextField(
                                value = pinInput,
                                onValueChange = { pinInput = it },
                                label = { Text("PIN") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation()
                            )
                            pinError?.let {
                                Spacer(Modifier.height(6.dp))
                                Text(it, color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (operatorStore.verify(op.id, pinInput)) {
                                operatorStore.setCurrent(op.id)
                                currentOperator = op
                                pinFor = null
                                showPicker = false
                            } else pinError = "Väärä PIN"
                        }) { Text("OK") }
                    },
                    dismissButton = { TextButton(onClick = { pinFor = null }) { Text("Peruuta") } }
                )
            }
        }
    }
}

@Composable
private fun VatHeader() {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Cell("ALV %", weight = 1f, bold = true)
        Cell("Veroton", weight = 1.3f, bold = true, end = true)
        Cell("Vero", weight = 1.1f, bold = true, end = true)
        Cell("Verollinen", weight = 1.4f, bold = true, end = true)
    }
}

@Composable
private fun VatRow(rate: String, net: String, vat: String, gross: String) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Cell(rate, weight = 1f)
        Cell(net, weight = 1.3f, end = true)
        Cell(vat, weight = 1.1f, end = true)
        Cell(gross, weight = 1.4f, end = true)
    }
}

@Composable
private fun RowScope.Cell(text: String, weight: Float, bold: Boolean = false, end: Boolean = false) {
    Text(
        text,
        modifier = Modifier.weight(weight),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = if (end) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowChips(selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PAYMENT_METHODS.forEach { pm ->
            FilterChip(
                selected = selected == pm,
                onClick = { onSelect(pm) },
                label = { Text(Reko.paymentLabel(pm)) }
            )
        }
    }
}

private fun qtyText(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

private fun rateText(r: Double): String =
    if (r == r.toLong().toDouble()) r.toLong().toString()
    else String.format(java.util.Locale.US, "%.1f", r)
