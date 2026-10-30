package fi.kasvila.ostot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.abs

/**
 * Yhden rivin muokkaus. Avautuu skannauksen jälkeen (koodi valmiina),
 * "Ilman koodia" -napista tai olemassa olevaa riviä napauttamalla.
 *
 * Määrä annetaan joko pakkauksina (4 × 1000 g) tai kokonaismääränä
 * (raaka-aine kiloina, muut kappaleina). Hinta kirjataan niin kuin se näkyy
 * hyllyssä; työpöytä muuntaa sen ALV 0 %:iin.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun LineEditorView(
    state: OstoState,
    session: OstoSession,
    line: OstoLine?,
    scannedEan: String,
    onCancel: () -> Unit,
    onSave: (OstoLine, Boolean) -> Unit,
    onDelete: (String) -> Unit
) {
    val isCount = session.isCount
    val defaultVat = state.catalog.defaultVat
    val ean = line?.ean ?: scannedEan
    val weigh = Osto.isWeighCode(ean)
    val known = remember(ean) { if (line == null) Osto.lookup(state, ean) else null }

    var kind by remember { mutableStateOf(line?.kind ?: known?.kind?.ifBlank { null } ?: Kind.ING) }
    var key by remember { mutableStateOf(line?.key ?: known?.key ?: "") }
    var name by remember { mutableStateOf(line?.name ?: known?.name ?: "") }
    var brand by remember { mutableStateOf(line?.brand ?: known?.brand ?: "") }
    val firstItem = Osto.item(state, kind, key)

    // Määrä
    var byPackages by remember {
        mutableStateOf(if (line != null) line.amount == null else !(isCount || weigh))
    }
    var packagesText by remember { mutableStateOf(Osto.fmt(line?.packages ?: 1.0, 2)) }
    var packQtyText by remember {
        mutableStateOf(Osto.fmt(line?.packQty ?: known?.packQty ?: if (kind != Kind.ING) 1.0 else null, 1))
    }
    var amountText by remember {
        mutableStateOf(
            line?.amount?.let { a -> if (line!!.kind == Kind.ING || line.kind.isBlank()) Osto.fmt(a / 1000.0, 3) else Osto.fmt(a, 2) } ?: ""
        )
    }

    // Hinta
    val weighPrice = if (line == null) Osto.weighPriceGuess(ean) else null
    var priceText by remember { mutableStateOf(Osto.fmt(line?.price ?: weighPrice, 2)) }
    var pricePer by remember {
        mutableStateOf(line?.pricePer ?: if (weighPrice != null) PricePer.TOTAL else PricePer.PACKAGE)
    }
    var priceSource by remember {
        mutableStateOf(line?.priceSource ?: if (weighPrice != null) PriceSource.WEIGH else PriceSource.MANUAL)
    }
    var inclVat by remember { mutableStateOf(line?.priceInclVat ?: true) }
    var vatText by remember { mutableStateOf(Osto.fmt(line?.vat ?: firstItem?.vat ?: defaultVat, 1)) }
    var bestText by remember { mutableStateOf(line?.bestBefore?.let { Osto.dateFi(it) } ?: "") }
    var note by remember { mutableStateOf(line?.note ?: "") }
    var learn by remember { mutableStateOf(true) }

    var picking by remember { mutableStateOf(line == null && known == null && state.catalog.items.isNotEmpty()) }
    var reading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    val item = Osto.item(state, kind, key)
    val isIng = kind == Kind.ING

    fun build(): OstoLine? {
        val packages = if (byPackages) Osto.parseNum(packagesText) else null
        val packQty = Osto.parseNum(packQtyText)
        val amountIn = if (byPackages) null else Osto.parseNum(amountText)
        val amount = amountIn?.let { if (isIng) it * 1000.0 else it }
        val price = if (isCount) null else Osto.parseNum(priceText)
        val draft = OstoLine(
            id = line?.id ?: Osto.nextLineId(session.lines),
            ean = ean, name = name.trim().ifBlank { item?.label() ?: "" }, brand = brand.trim(),
            kind = kind, key = key,
            packages = packages, packQty = packQty, amount = amount,
            price = price, pricePer = pricePer,
            priceInclVat = inclVat, vat = Osto.parseNum(vatText),
            priceSource = if (price == null) PriceSource.NONE else priceSource,
            bestBefore = Osto.parseDate(bestText), note = note.trim()
        )
        val qty = draft.totalQty()
        if (qty == null || qty <= 0) {
            error = if (byPackages) "Anna pakkausten määrä ja pakkauskoko." else "Anna määrä."
            return null
        }
        if (bestText.isNotBlank() && draft.bestBefore.isBlank()) {
            error = "Parasta ennen -päivä ei ole päivämäärä (pp.kk.vvvv)."
            return null
        }
        if (!isCount && pricePer == PricePer.KG && !isIng) {
            error = "Kilohinta käy vain raaka-aineelle. Valitse hinta pakkausta tai kappaletta kohden."
            return null
        }
        return draft
    }

    val preview = remember(packagesText, packQtyText, amountText, priceText, pricePer, inclVat, vatText, byPackages, kind) {
        runCatching {
            val p = Osto.parseNum(priceText)
            val l = OstoLine(
                id = "x", kind = kind,
                packages = if (byPackages) Osto.parseNum(packagesText) else null,
                packQty = Osto.parseNum(packQtyText),
                amount = if (byPackages) null else Osto.parseNum(amountText)?.let { if (kind == Kind.ING) it * 1000 else it },
                price = p, pricePer = pricePer, priceInclVat = inclVat, vat = Osto.parseNum(vatText)
            )
            Osto.unitPriceNet(l, defaultVat)
        }.getOrNull()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (line == null) "Uusi rivi" else "Muokkaa riviä") },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin") } },
                actions = {
                    if (line != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Poista rivi") }
                }
            )
        },
        bottomBar = {
            Row(Modifier.padding(12.dp).imePadding(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Peruuta") }
                Button(onClick = {
                    error = ""
                    build()?.let { onSave(it, learn && ean.isNotBlank() && !weigh && key.isNotBlank()) }
                }, modifier = Modifier.weight(1f)) { Text("Tallenna") }
            }
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── Mikä tuote ──
            if (ean.isNotBlank()) {
                Text(
                    when {
                        weigh -> "Kaupan punnitustarra $ean"
                        known != null -> "Koodi $ean · tunnettu"
                        line == null -> "Koodi $ean · uusi koodi"
                        else -> "Koodi $ean"
                    },
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Card(
                Modifier.fillMaxWidth().clickable { picking = true },
                colors = CardDefaults.cardColors(
                    containerColor = if (item == null) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item?.label() ?: "Valitse tuote", fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                item == null && key.isNotBlank() -> "\"$key\" ei ole luettelossa"
                                item == null -> "Ilman valintaa rivi yhdistetään työpöydällä"
                                else -> kindLabel(item.kind) + itemInfo(item, state.catalog.currency)
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Icon(Icons.Filled.Search, "Vaihda")
                }
            }
            if (item == null) {
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nimi tai kuvaus (esim. kuitin rivi)") })
            }
            if (isCount && item?.stock != null) {
                Text("Ohjelman saldo nyt: " + Osto.qtyText(item.stock, item.kind),
                    style = MaterialTheme.typography.bodySmall)
            }

            // ── Määrä ──
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = byPackages, onClick = { byPackages = true }, label = { Text("Pakkauksina") })
                FilterChip(selected = !byPackages, onClick = { byPackages = false }, label = { Text("Kokonaismäärä") })
            }
            if (byPackages) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = {
                        val n = (Osto.parseNum(packagesText) ?: 1.0) - 1.0
                        packagesText = Osto.fmt(if (n < 1) 1.0 else n, 2)
                    }) { Icon(Icons.Filled.Remove, "Vähemmän") }
                    OutlinedTextField(
                        packagesText, { packagesText = it }, singleLine = true,
                        label = { Text("Pakkauksia") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(120.dp).padding(horizontal = 8.dp)
                    )
                    FilledTonalIconButton(onClick = {
                        packagesText = Osto.fmt((Osto.parseNum(packagesText) ?: 0.0) + 1.0, 2)
                    }) { Icon(Icons.Filled.Add, "Enemmän") }
                }
                OutlinedTextField(
                    packQtyText, { packQtyText = it }, singleLine = true,
                    label = { Text(if (isIng) "Pakkauskoko grammoina (1 kg = 1000)" else "Kappaletta pakkauksessa") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                OutlinedTextField(
                    amountText, { amountText = it }, singleLine = true,
                    label = { Text(if (isIng) "Määrä kiloina" else "Määrä kappaleina") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ── Hinta (vain ostoissa) ──
            if (!isCount) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        priceText, { priceText = it; priceSource = PriceSource.MANUAL }, singleLine = true,
                        label = { Text("Hinta €") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { reading = true }) {
                        Icon(Icons.Filled.CameraAlt, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Lue lapusta")
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(pricePer == PricePer.PACKAGE, { pricePer = PricePer.PACKAGE }, { Text("per pakkaus") })
                    if (isIng) FilterChip(pricePer == PricePer.KG, { pricePer = PricePer.KG }, { Text("€/kg") })
                    else FilterChip(pricePer == PricePer.UNIT, { pricePer = PricePer.UNIT }, { Text("€/kpl") })
                    FilterChip(pricePer == PricePer.TOTAL, { pricePer = PricePer.TOTAL }, { Text("rivi yhteensä") })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(inclVat, { inclVat = it })
                    Text("Hinta sisältää ALV:n", Modifier.weight(1f))
                    OutlinedTextField(
                        vatText, { vatText = it }, singleLine = true, label = { Text("ALV %") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(100.dp)
                    )
                }
                PriceCompare(item, preview, state.catalog.currency)
                OutlinedTextField(
                    bestText, { bestText = it }, singleLine = true,
                    label = { Text("Parasta ennen (valinnainen)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (ean.isNotBlank() && !weigh && key.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(learn, { learn = it })
                    Text("Muista koodi ja pakkauskoko tälle tuotteelle")
                }
            }
            OutlinedTextField(note, { note = it }, label = { Text("Huomio (valinnainen)") },
                modifier = Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (picking) {
        ItemPicker(
            state = state,
            isCount = isCount,
            onDismiss = { picking = false },
            onPick = { picked ->
                picking = false
                val changed = picked.kind != kind || picked.key != key
                kind = picked.kind
                key = picked.key
                if (name.isBlank()) name = picked.label()
                picked.vat?.let { vatText = Osto.fmt(it, 1) }
                if (changed && packQtyText.isBlank() && picked.kind != Kind.ING) packQtyText = "1"
                if (isCount && changed) byPackages = false
            },
            onNone = { picking = false; key = "" }
        )
    }

    if (reading) {
        LabelReader(
            onDismiss = { reading = false },
            onUse = { g ->
                reading = false
                when {
                    g.price != null -> {
                        priceText = Osto.fmt(g.price, 2)
                        pricePer = PricePer.PACKAGE
                    }
                    g.perKg != null && isIng -> {
                        priceText = Osto.fmt(g.perKg, 2)
                        pricePer = PricePer.KG
                    }
                }
                if (g.packG != null && isIng && Osto.parseNum(packQtyText) == null) packQtyText = Osto.fmt(g.packG, 1)
                priceSource = PriceSource.LABEL
                inclVat = true
            }
        )
    }

    if (confirmDelete && line != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Poistetaanko rivi?") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(line.id) }) { Text("Poista") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Peruuta") } }
        )
    }

}

private fun kindLabel(kind: String): String = when (kind) {
    Kind.ING -> "Raaka-aine"
    Kind.PACK -> "Pakkaus"
    Kind.PRODUCT -> "Oma tuote"
    else -> ""
}

private fun itemInfo(item: CatalogItem, currency: String): String {
    val parts = mutableListOf<String>()
    if (item.price > 0) {
        parts += Osto.fmt(item.price, 2) + " $currency/" + (if (item.isIng) "kg" else "kpl") + " alv 0"
    }
    if (item.stock != null) parts += "varastossa " + Osto.qtyText(item.stock, item.kind)
    return if (parts.isEmpty()) "" else " · " + parts.joinToString(" · ")
}

/** Uusi hinta rinnakkain ohjelman hinnan ja viime oston kanssa. */
@Composable
private fun PriceCompare(item: CatalogItem?, newNet: Double?, currency: String) {
    if (item == null && newNet == null) return
    val unit = if (item?.isIng != false) "kg" else "kpl"
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (newNet != null) {
            val pct = Osto.priceChangePct(newNet, item?.price)
            val color = when {
                pct == null -> MaterialTheme.colorScheme.onSurface
                pct > 10 -> MaterialTheme.colorScheme.error
                pct < -5 -> Color(0xFF2E7D32)
                else -> MaterialTheme.colorScheme.onSurface
            }
            val pctText = pct?.let { (if (it >= 0) " (+" else " (") + Osto.fmt(it, 1) + " %)" } ?: ""
            Text("Tämä ostos: ${Osto.fmt(newNet, 2)} $currency/$unit alv 0$pctText",
                color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        }
        if (item != null && item.price > 0) {
            Text("Ohjelmassa nyt: ${Osto.fmt(item.price, 2)} $currency/$unit alv 0" +
                (if (item.supplier.isNotBlank()) " · ${item.supplier}" else ""),
                style = MaterialTheme.typography.bodySmall)
        }
        val last = item?.last
        if (last != null && last.price > 0) {
            Text("Viimeksi ostettu ${Osto.dateFi(last.date)}: ${Osto.fmt(last.price, 2)} $currency/$unit" +
                (if (last.shop.isNotBlank()) " · ${last.shop}" else ""),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ── Tuotteen valinta ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemPicker(
    state: OstoState,
    isCount: Boolean,
    onDismiss: () -> Unit,
    onPick: (CatalogItem) -> Unit,
    onNone: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var kinds by remember { mutableStateOf(setOf(Kind.ING)) }
    val need = remember(state.catalog) { state.catalog.shoppingList.map { it.kind to it.key }.toSet() }
    val results = remember(query, kinds, state.catalog) { Osto.search(state.catalog.items, query, kinds) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                TopAppBar(
                    title = { Text("Valitse tuote") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin") } }
                )
            }) { pad ->
                Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
                    OutlinedTextField(
                        query, { query = it }, singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        label = { Text("Hae nimellä") }, modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(Kind.ING in kinds, { kinds = setOf(Kind.ING) }, { Text("Raaka-aineet") })
                        FilterChip(Kind.PACK in kinds, { kinds = setOf(Kind.PACK) }, { Text("Pakkaukset") })
                        if (isCount) FilterChip(Kind.PRODUCT in kinds, { kinds = setOf(Kind.PRODUCT) }, { Text("Tuotteet") })
                    }
                    if (state.catalog.items.isEmpty()) {
                        Text("Luetteloa ei vielä ole. Hae se työpöydältä, tai jätä rivi työpöydällä yhdistettäväksi.",
                            modifier = Modifier.padding(vertical = 12.dp))
                    }
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
                        items(results, key = { it.kind + ":" + it.key }) {
                            Column(
                                Modifier.fillMaxWidth().clickable { onPick(it) }.padding(vertical = 10.dp)
                            ) {
                                Row {
                                    Text(it.label(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (it.used) FontWeight.Bold else FontWeight.Normal)
                                    if ((it.kind to it.key) in need) {
                                        Text("ostoslistalla", color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                val info = itemInfo(it, state.catalog.currency).removePrefix(" · ")
                                if (info.isNotBlank()) Text(info, style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider()
                        }
                    }
                    TextButton(onClick = onNone, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Ei listassa: yhdistetään työpöydällä")
                    }
                }
            }
        }
    }
}

// ── Hintalapun luku ────────────────────────────────────────────────────────

@Composable
private fun LabelReader(onDismiss: () -> Unit, onUse: (Osto.LabelGuess) -> Unit) {
    var request by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var guess by remember { mutableStateOf<Osto.LabelGuess?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                OstoCamera(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    readBarcodes = false,
                    textRequest = request,
                    onText = { text -> busy = false; guess = Osto.parseLabel(text) }
                )
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val g = guess
                    when {
                        busy -> Text("Luetaan…")
                        g == null -> Text("Rajaa hyllylappu kuvaan ja paina Lue.")
                        g.isEmpty -> Text("Lapusta ei löytynyt hintaa. Yritä lähempää tai kirjoita hinta itse.")
                        else -> {
                            if (g.price != null) Text("Hinta: ${Osto.fmt(g.price, 2)} €", fontWeight = FontWeight.Bold)
                            if (g.perKg != null) Text("Vertailuhinta: ${Osto.fmt(g.perKg, 2)} €/kg")
                            if (g.packG != null) Text("Koko: ${Osto.qtyText(g.packG, Kind.ING)}")
                            if (g.price != null && g.perKg != null && g.packG != null) {
                                val calc = g.perKg * g.packG / 1000.0
                                if (abs(calc - g.price) > 0.05 * g.price + 0.02) {
                                    Text("Tarkista: hinta, koko ja kilohinta eivät täsmää.",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Peruuta") }
                        FilledTonalButton(onClick = { busy = true; request++ }, modifier = Modifier.weight(1f)) {
                            Text(if (guess == null) "Lue" else "Lue uudelleen")
                        }
                        Button(
                            onClick = { guess?.let(onUse) },
                            enabled = guess?.isEmpty == false,
                            modifier = Modifier.weight(1f)
                        ) { Text("Käytä") }
                    }
                }
            }
        }
    }
}
