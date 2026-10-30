package fi.kasvila.reko

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * "Kuitti ja tulostin" — Bluetooth thermal printer settings (v46).
 *
 * Pick the paper width and which paired printer to use, and run a test print so
 * the producer can confirm the printer works (and that ä/ö come out right) before
 * relying on it at a pickup.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptSettingsView(
    prefs: ReceiptPrefs,
    onBack: () -> Unit,
    onSave: (ReceiptPrefs) -> Unit,
    onTestPrint: (ReceiptPrefs) -> Unit
) {
    val context = LocalContext.current
    var paperWidth by remember { mutableStateOf(prefs.paperWidthMm) }
    var selectedAddress by remember { mutableStateOf(prefs.printerAddress) }
    var selectedName by remember { mutableStateOf(prefs.printerName) }
    var showOperator by remember { mutableStateOf(prefs.showOperator) }
    var printers by remember { mutableStateOf<List<ThermalPrinter.BtPrinter>>(emptyList()) }

    fun hasBt(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (hasBt()) printers = ThermalPrinter.pairedPrinters()
        else Toast.makeText(context, "Bluetooth-lupa tarvitaan tulostinten listaamiseen", Toast.LENGTH_LONG).show()
    }

    fun refresh() {
        if (hasBt()) printers = ThermalPrinter.pairedPrinters()
        else permLauncher.launch(
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        )
    }

    LaunchedEffect(Unit) { if (hasBt()) printers = ThermalPrinter.pairedPrinters() }

    fun current(): ReceiptPrefs =
        ReceiptPrefs(
            printerAddress = selectedAddress, printerName = selectedName,
            paperWidthMm = paperWidth, showOperator = showOperator
        )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kuitti ja tulostin") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin")
                    }
                },
                actions = {
                    TextButton(onClick = { onSave(current()) }) {
                        Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Tallenna")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            Text(
                "Bluetooth-lämpötulostin kuittien tulostukseen. Paritä tulostin ensin " +
                    "puhelimen Bluetooth-asetuksista, valitse se tästä ja tee testitulostus.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(18.dp))

            Text("Paperin leveys", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = paperWidth == 58, onClick = { paperWidth = 58 }, label = { Text("58 mm") })
                FilterChip(selected = paperWidth == 80, onClick = { paperWidth = 80 }, label = { Text("80 mm") })
            }

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Näytä palvelija kuitissa")
                    Text(
                        "Tulostaa \"Palvelija: nimi\" kuittiin (monikäyttäjä).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = showOperator, onCheckedChange = { showOperator = it })
            }

            Spacer(Modifier.height(20.dp))
            Text("Bluetooth-tulostin", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { refresh() }, modifier = Modifier.fillMaxWidth()) {
                Text("Etsi paritetut tulostimet")
            }
            Spacer(Modifier.height(8.dp))

            PrinterRow(
                label = "Ensimmäinen paritettu (oletus)",
                selected = selectedAddress.isBlank(),
                onClick = { selectedAddress = ""; selectedName = "" }
            )
            if (printers.isEmpty()) {
                Text(
                    "Ei löydettyjä tulostimia. Paritä tulostin puhelimen Bluetooth-asetuksista " +
                        "ja paina \"Etsi paritetut tulostimet\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else {
                printers.forEach { p ->
                    PrinterRow(
                        label = "${p.name}\n${p.address}",
                        selected = selectedAddress == p.address,
                        onClick = { selectedAddress = p.address; selectedName = p.name }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("Testitulostus", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { onTestPrint(current()) }, modifier = Modifier.fillMaxWidth()) {
                Text("Tee testitulostus")
            }
            Text(
                "Tarkista testitulosteesta, että ääkköset (ä/ö) näkyvät oikein. Jos eivät, " +
                    "kokeile toista tulostinmallia.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(24.dp))
            Button(onClick = { onSave(current()) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Tallenna")
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PrinterRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
