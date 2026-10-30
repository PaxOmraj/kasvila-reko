package fi.kasvila.reko

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.FileOutputStream

/**
 * Myyjän tiedot — the seller/producer details printed on receipts (v45).
 *
 * Filled in entirely on the phone; nothing here needs the desktop. Multi-value
 * fields (addresses, phones, emails) are one-per-line text areas to keep the
 * editor simple. The logo is copied into the app's private storage so it survives
 * and can be drawn onto the receipt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SellerInfoView(
    seller: SellerInfo,
    onBack: () -> Unit,
    onSave: (SellerInfo) -> Unit
) {
    val context = LocalContext.current

    var name by remember { mutableStateOf(seller.name) }
    var businessId by remember { mutableStateOf(seller.businessId) }
    var addresses by remember { mutableStateOf(seller.addresses.joinToString("\n")) }
    var phones by remember { mutableStateOf(seller.phones.joinToString("\n")) }
    var emails by remember { mutableStateOf(seller.emails.joinToString("\n")) }
    var website by remember { mutableStateOf(seller.website) }
    var mobilePay by remember { mutableStateOf(seller.mobilePay) }
    var iban by remember { mutableStateOf(seller.iban) }
    var paymentReference by remember { mutableStateOf(seller.paymentReference) }
    var introText by remember { mutableStateOf(seller.introText) }
    var footerNote by remember { mutableStateOf(seller.footerNote) }
    var logoPath by remember { mutableStateOf(seller.logoPath) }

    // Reload the logo preview whenever the path changes.
    val logoBitmap = remember(logoPath) {
        if (logoPath.isBlank()) null
        else try { BitmapFactory.decodeFile(logoPath) } catch (e: Exception) { null }
    }

    val pickLogo = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val out = File(context.filesDir, "seller_logo.png")
                context.contentResolver.openInputStream(uri)?.use { inp ->
                    FileOutputStream(out).use { inp.copyTo(it) }
                }
                logoPath = out.absolutePath
            } catch (e: Exception) {
                Toast.makeText(context, "Logon lisääminen epäonnistui", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun linesOf(s: String): List<String> =
        s.split("\n").map { it.trim() }.filter { it.isNotBlank() }

    fun collect(): SellerInfo = SellerInfo(
        name = name.trim(),
        businessId = businessId.trim(),
        addresses = linesOf(addresses),
        phones = linesOf(phones),
        emails = linesOf(emails),
        website = website.trim(),
        logoPath = logoPath,
        mobilePay = mobilePay.trim(),
        iban = iban.trim().replace(" ", ""),
        paymentReference = paymentReference.trim(),
        introText = introText.trim(),
        footerNote = footerNote.trim()
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Myyjän tiedot") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin")
                    }
                },
                actions = {
                    TextButton(onClick = { onSave(collect()) }) {
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
                "Nämä tiedot tulostuvat asiakkaan kuittiin. Nimi ja Y-tunnus vaaditaan " +
                    "lain mukaan. Tiedot tallennetaan vain tähän puhelimeen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            Section("Perustiedot")
            Field("Yrityksen / tuottajan nimi", name) { name = it }
            Field("Y-tunnus", businessId) { businessId = it }
            Field("Osoite(et) — yksi per rivi", addresses, singleLine = false) { addresses = it }
            Field("Puhelin(numerot) — yksi per rivi", phones, singleLine = false) { phones = it }
            Field("Sähköposti(t) — yksi per rivi", emails, singleLine = false) { emails = it }
            Field("Verkkosivu", website) { website = it }

            Spacer(Modifier.height(20.dp))
            Section("Logo")
            if (logoBitmap != null) {
                Image(
                    bitmap = logoBitmap.asImageBitmap(),
                    contentDescription = "Logo",
                    modifier = Modifier.heightIn(max = 96.dp).padding(vertical = 8.dp)
                )
            } else {
                Text(
                    "Ei logoa. Yksinkertainen, kontrastikas logo tulostuu parhaiten " +
                        "(myös mustavalkoisella lämpötulostimella).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                OutlinedButton(onClick = { pickLogo.launch("image/*") }) {
                    Text(if (logoBitmap != null) "Vaihda logo" else "Valitse logo")
                }
                if (logoPath.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { logoPath = "" }) { Text("Poista") }
                }
            }

            Spacer(Modifier.height(20.dp))
            Section("Maksutiedot (kuitin QR ja maksurivi)")
            Field("MobilePay-numero tai -linkki", mobilePay) { mobilePay = it }
            Field("IBAN", iban) { iban = it }
            Field("Oletusviite / viesti", paymentReference) { paymentReference = it }
            Text(
                "QR-maksukoodi käyttää ensisijaisesti IBANia (SEPA-koodi, jonka moni " +
                    "pankkisovellus lukee). QR-kuva tulostuu seuraavassa versiossa.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(20.dp))
            Section("Kuitin lisätekstit")
            Field("Esittely / kiitosteksti", introText, singleLine = false) { introText = it }
            Field("Nouto- / aukiolohuomautus", footerNote, singleLine = false) { footerNote = it }

            Spacer(Modifier.height(24.dp))
            Button(onClick = { onSave(collect()) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Tallenna")
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Field(
    label: String,
    value: String,
    singleLine: Boolean = true,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}
