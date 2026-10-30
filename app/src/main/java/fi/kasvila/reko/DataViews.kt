package fi.kasvila.reko

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * "Tiedot ja synkronointi" — the management side of the app.
 *
 * These screens let the phone own the catalog rather than merely receive it:
 * products and their prices, the REKO rings, and the trash that makes every
 * deletion reversible. Everything written here goes through
 * [SyncEngine.stamp] / [SyncEngine.softDelete] so the next merge can tell what
 * changed — an unstamped edit would look untouched and get quietly reverted by
 * the other device's older copy.
 */

private fun num(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else String.format(java.util.Locale.US, "%.2f", v).replace(".", ",")

private fun pct(v: Double): String = String.format(java.util.Locale.US, "%.1f", v).replace(".", ",") + " %"

// ════════════════════════════════════════════════════════════════════════════
//  Hub
// ════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSyncHubView(
    state: AppState,
    onBack: () -> Unit,
    onProducts: () -> Unit,
    onRings: () -> Unit,
    onCustomers: () -> Unit,
    onRoutes: () -> Unit,
    onMaintenance: () -> Unit,
    onSync: () -> Unit,
    onTrash: () -> Unit
) {
    val trashCount = state.trashedProducts().size + state.trashedRings().size +
        state.trashedCustomers().size
    val lastSync = state.syncLog.lastOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tiedot ja synkronointi") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin") }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            Text(
                "Täällä hallitaan tuotteet, hinnat ja ruokapiirit suoraan puhelimessa. " +
                    "Muutokset siirtyvät työpöytäohjelmaan synkronoinnissa — kumpikaan ohjelma " +
                    "ei ylikirjoita toisen tuoreempia tietoja.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            HubRow(
                title = "Tuotteet ja hinnat",
                subtitle = "${state.activeProducts().size} tuotetta käytössä",
                onClick = onProducts
            )
            HubRow(
                title = "Ruokapiirit",
                subtitle = "${state.activeRings().size} piiriä käytössä",
                onClick = onRings
            )
            HubRow(
                title = "Asiakkaat",
                subtitle = if (state.activeCustomers().isEmpty())
                    "Ei vielä rekisterissä"
                else "${state.activeCustomers().size} asiakasta",
                onClick = onCustomers
            )
            HubRow(
                title = "REKO-reitit",
                subtitle = "Noutopäivät, ajat ja päällekkäisyydet",
                onClick = onRoutes
            )
            HubRow(
                title = "Muistutukset ja varmuuskopio",
                subtitle = "Maksumuistutus ja automaattinen varmuuskopio",
                onClick = onMaintenance
            )
            HubRow(
                title = "Laiteyhteys ja synkronointi",
                subtitle = if (lastSync == null) "Ei vielä synkronoitu"
                else "Viimeksi ${stampShort(lastSync.at)} · ${lastSync.peerName}",
                onClick = onSync
            )
            HubRow(
                title = "Roskakori",
                subtitle = if (trashCount == 0) "Tyhjä"
                else "$trashCount poistettua · palautettavissa $TRASH_DAYS vrk",
                onClick = onTrash
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HubRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun stampShort(iso: String): String = try {
    val dt = java.time.LocalDateTime.parse(iso.take(19))
    "%d.%d. klo %02d:%02d".format(dt.dayOfMonth, dt.monthValue, dt.hour, dt.minute)
} catch (e: Exception) {
    iso.take(10)
}

// ════════════════════════════════════════════════════════════════════════════
//  Products
// ════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductsView(
    state: AppState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Product) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var showInactive by remember { mutableStateOf(false) }

    val visible = remember(state.products, query, showInactive) {
        val q = query.trim().lowercase()
        state.products
            .filter { !it.deleted }
            .filter { showInactive || it.active }
            .filter {
                q.isBlank() || it.name.lowercase().contains(q) ||
                    it.aliases.any { a -> a.lowercase().contains(q) }
            }
            .sortedWith(compareBy({ !it.active }, { it.name.lowercase() }))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tuotteet ja hinnat") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin") }
                },
                actions = {
                    IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, "Uusi tuote") }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize()) {
            OutlinedTextField(
                query, { query = it },
                label = { Text("Hae tuotetta") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(showInactive, { showInactive = it })
                Text("Näytä myös käytöstä poistetut", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Text(
                    "${visible.size} kpl",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (visible.isEmpty()) {
                Text(
                    if (state.products.isEmpty())
                        "Ei tuotteita. Lisää tuote plus-painikkeesta tai synkronoi työpöytäohjelman kanssa."
                    else "Ei hakua vastaavia tuotteita.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp)
                )
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize().padding(top = 6.dp)) {
                items(visible, key = { it.id.ifBlank { it.name } }) { p ->
                    ProductRow(p, state.currency) { onEdit(p) }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductRow(p: Product, currency: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(
            Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        p.name,
                        fontWeight = FontWeight.Bold,
                        color = if (p.active) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = if (p.active) null else TextDecoration.LineThrough
                    )
                    if (!p.active) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "POIS KÄYTÖSTÄ",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                val margin = p.marginPct()
                Text(
                    buildString {
                        append("${Reko.money(p.price, currency)} / ${p.unit}")
                        if (p.vat > 0) append("  ·  ALV ${num(p.vat)} %")
                        if (margin != null) append("  ·  kate ${pct(margin)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (p.aliases.isNotEmpty()) {
                    Text(
                        "alias: " + p.aliases.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(Icons.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductEditView(
    state: AppState,
    initial: Product?,
    onBack: () -> Unit,
    onSave: (Product) -> Unit,
    onDelete: (Product) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var priceText by remember { mutableStateOf(initial?.let { num(it.price) } ?: "") }
    var vat by remember { mutableStateOf(initial?.vat ?: state.defaultVat) }
    var unit by remember { mutableStateOf(initial?.unit ?: "kpl") }
    var aliasText by remember { mutableStateOf(initial?.aliases?.joinToString(", ") ?: "") }
    var active by remember { mutableStateOf(initial?.active ?: true) }
    var confirmDelete by remember { mutableStateOf(false) }

    val price = Reko.parseDecimal(priceText) ?: 0.0
    val priceValid = priceText.isBlank() || Reko.parseDecimal(priceText) != null
    val net = if (vat > 0) price / (1.0 + vat / 100.0) else price
    val cost = initial?.cost ?: 0.0
    val marginValue = if (cost > 0) net - cost else null
    val marginPctValue = if (cost > 0 && net > 0) (net - cost) / net * 100.0 else null

    // How many open orders still reference this product — used to warn before
    // taking it out of use, so a product that is about to be handed to a customer
    // never silently disappears from the picker.
    val openUses = remember(initial?.id, state.orders) {
        val today = java.time.LocalDate.now().toString()
        val pid = initial?.id ?: ""
        if (pid.isBlank()) 0
        else state.orders.count { o ->
            !o.cancelled && !o.deleted && (o.date.isBlank() || o.date >= today) &&
                o.lines.any { it.product_id == pid }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "Uusi tuote" else "Muokkaa tuotetta") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin") }
                },
                actions = {
                    if (initial != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, "Poista", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                name, { name = it },
                label = { Text("Tuotteen nimi") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                priceText, { priceText = it },
                label = { Text("Myyntihinta (sis. ALV)") },
                suffix = { Text(state.currency) },
                isError = !priceValid,
                supportingText = {
                    if (!priceValid) Text("Anna hinta muodossa 4,50")
                    else Text("Hinta jonka asiakas maksaa, sis\u00e4lt\u00e4\u00e4 ALV:n.")
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))
            DropdownField(
                label = "ALV-kanta",
                value = "${num(vat)} %",
                options = VAT_RATES.map { "${num(it)} %" },
                editable = true,
                onValueChange = { txt ->
                    Reko.parseDecimal(txt.replace("%", ""))?.let { vat = it }
                },
                onSelectIndex = { idx -> vat = VAT_RATES[idx] }
            )

            Spacer(Modifier.height(12.dp))
            DropdownField(
                label = "Yksikkö",
                value = unit,
                options = REKO_UNITS,
                editable = false,
                onSelectIndex = { idx -> unit = REKO_UNITS[idx] }
            )

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                aliasText, { aliasText = it },
                label = { Text("Aliakset (tilausnimet, pilkulla erotettuna)") },
                supportingText = { Text("Näillä nimillä tuote tunnistetaan liitetystä tilaustekstistä.") },
                modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 3
            )

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Käytössä", fontWeight = FontWeight.Bold)
                    Text(
                        if (active) "Näkyy tilauslomakkeen tuotevalikossa."
                        else "Piilotettu valikosta. Vanhat tilaukset säilyvät ennallaan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(active, { active = it })
            }
            if (!active && openUses > 0) {
                Text(
                    "⚠ Tuote on $openUses avoimessa tilauksessa. Ne säilyvät, mutta tuotetta ei voi enää lisätä uusiin tilauksiin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            // ── read-only, owned by the desktop ──
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("Työpöytäohjelman tiedot", fontWeight = FontWeight.Bold)
            Text(
                "Näitä ei muokata puhelimessa — ne tulevat työpöytäohjelman resepti- ja " +
                    "kustannuslaskennasta ja päivittyvät synkronoinnissa.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            ReadOnlyRow("Veroton hinta", Reko.money(net, state.currency))
            ReadOnlyRow("Kustannus / yksikkö", if (cost > 0) Reko.money(cost, state.currency) else "– ei tiedossa")
            ReadOnlyRow(
                "Kate",
                if (marginValue != null) Reko.money(marginValue, state.currency) else "– ei tiedossa"
            )
            ReadOnlyRow("Kate-%", if (marginPctValue != null) pct(marginPctValue) else "– ei tiedossa")
            if (initial?.category?.isNotBlank() == true) {
                ReadOnlyRow("Ryhmä", initial.category)
            }
            if (initial != null) {
                ReadOnlyRow("Muokattu", stampShort(initial.updated_at))
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    val base = initial ?: Product(id = Reko.newProductId(), name = "")
                    onSave(
                        base.copy(
                            name = name.trim(),
                            price = price,
                            vat = vat,
                            unit = unit,
                            aliases = aliasText.split(",", "\n")
                                .map { it.trim() }.filter { it.isNotBlank() }.distinct(),
                            active = active
                        )
                    )
                },
                enabled = name.isNotBlank() && priceValid,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Tallenna") }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete && initial != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Poista tuote") },
            text = {
                Column {
                    Text(
                        "Tuote siirtyy roskakoriin ja poistuu tuotevalikosta. " +
                            "Vanhat tilaukset ja myyntihistoria säilyvät ennallaan."
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Voit palauttaa sen roskakorista $TRASH_DAYS päivän ajan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (openUses > 0) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "⚠ Tuote on $openUses avoimessa tilauksessa.",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(initial) }) {
                    Text("Poista roskakoriin")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Peruuta") }
            }
        )
    }
}

@Composable
private fun ReadOnlyRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label, modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  Rings
// ════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RingsManageView(
    state: AppState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Ring) -> Unit
) {
    var showInactive by remember { mutableStateOf(false) }
    val visible = state.rings
        .filter { !it.deleted }
        .filter { showInactive || it.active }
        .sortedWith(compareBy({ !it.active }, { it.weekday ?: 9 }, { it.time }, { it.name.lowercase() }))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ruokapiirit") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin") }
                },
                actions = { IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, "Uusi piiri") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(showInactive, { showInactive = it })
                Text("Näytä myös käytöstä poistetut", style = MaterialTheme.typography.bodySmall)
            }
            if (visible.isEmpty()) {
                Text(
                    "Ei ruokapiirejä. Lisää piiri plus-painikkeesta tai synkronoi työpöytäohjelman kanssa.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp)
                )
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize().padding(top = 4.dp)) {
                items(visible, key = { it.id }) { r ->
                    val orderCount = state.orders.count { it.group_id == r.id && !it.deleted }
                    RingManageRow(r, orderCount) { onEdit(r) }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RingManageRow(r: Ring, orderCount: Int, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(
            Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        r.name.ifBlank { "(nimetön piiri)" },
                        fontWeight = FontWeight.Bold,
                        color = if (r.active) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = if (r.active) null else TextDecoration.LineThrough
                    )
                    if (!r.active) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "POIS KÄYTÖSTÄ", style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Text(
                    buildString {
                        val day = Reko.weekdayLabel(r.weekday)
                        if (day.isNotBlank()) append("$day ")
                        if (r.time.isNotBlank()) append("${r.time}  ")
                        append("· ${Reko.parityLabel(r.parity)}")
                        if (r.location.isNotBlank()) append("  ·  ${r.location}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (orderCount > 0) {
                    Text(
                        "$orderCount tilausta",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Icon(Icons.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RingEditView(
    state: AppState,
    initial: Ring?,
    onBack: () -> Unit,
    onSave: (Ring) -> Unit,
    onDelete: (Ring) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var city by remember { mutableStateOf(initial?.city ?: "") }
    var location by remember { mutableStateOf(initial?.location ?: "") }
    var address by remember { mutableStateOf(initial?.address ?: "") }
    var weekday by remember { mutableStateOf(initial?.weekday) }
    var time by remember { mutableStateOf(initial?.time ?: "") }
    var parity by remember { mutableStateOf(initial?.parity ?: "weekly") }
    var handler by remember { mutableStateOf(initial?.handler ?: "") }
    var fbUrl by remember { mutableStateOf(initial?.fb_url ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var active by remember { mutableStateOf(initial?.active ?: true) }
    var confirmDelete by remember { mutableStateOf(false) }

    val weekdayOptions = listOf("Ei päivää") + (0..6).map { Reko.weekdayFull(it) }
    val parityIds = listOf("weekly", "even", "odd")
    val parityOptions = parityIds.map { Reko.parityLabel(it) }

    val openOrders = remember(initial?.id, state.orders) {
        val today = java.time.LocalDate.now().toString()
        val rid = initial?.id ?: ""
        if (rid.isBlank()) 0
        else state.orders.count {
            it.group_id == rid && !it.cancelled && !it.deleted && (it.date.isBlank() || it.date >= today)
        }
    }

    // Live preview of the next pickup dates, so a wrong weekday/parity is obvious
    // before it has produced a batch of orders on the wrong day.
    val preview = remember(weekday, parity) {
        Reko.upcomingDates(Ring(id = "preview", weekday = weekday, parity = parity), 3)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "Uusi ruokapiiri" else "Muokkaa piiriä") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin") }
                },
                actions = {
                    if (initial != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, "Poista", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Piirin nimi") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(city, { city = it }, label = { Text("Paikkakunta") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(location, { location = it }, label = { Text("Noutopaikka") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(address, { address = it }, label = { Text("Osoite") },
                singleLine = true, modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(14.dp))
            DropdownField(
                label = "Noutopäivä",
                value = if (weekday == null) "Ei päivää" else Reko.weekdayFull(weekday),
                options = weekdayOptions,
                editable = false,
                onSelectIndex = { idx -> weekday = if (idx == 0) null else idx - 1 }
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(time, { time = it }, label = { Text("Kellonaika (esim. 17:00–18:00)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            DropdownField(
                label = "Rytmi",
                value = Reko.parityLabel(parity),
                options = parityOptions,
                editable = false,
                onSelectIndex = { idx -> parity = parityIds[idx] }
            )
            if (preview.isNotEmpty()) {
                Text(
                    "Seuraavat noutopäivät: " + preview.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Spacer(Modifier.height(14.dp))
            OutlinedTextField(handler, { handler = it }, label = { Text("Hoitaja (kuka vie tuotteet)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(fbUrl, { fbUrl = it }, label = { Text("Facebook-sivu") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(notes, { notes = it }, label = { Text("Muistiinpanot") },
                modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 4)

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Käytössä", fontWeight = FontWeight.Bold)
                    Text(
                        if (active) "Näkyy etusivulla ja tilausten piirivalikossa."
                        else "Piilotettu. Vanhat tilaukset säilyvät.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(active, { active = it })
            }
            if (!active && openOrders > 0) {
                Text(
                    "⚠ Piirillä on $openOrders avointa tilausta. Piiri näkyy \"poistuvana\" kunnes ne on hoidettu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (initial != null) {
                Spacer(Modifier.height(14.dp))
                ReadOnlyRow("Muokattu", stampShort(initial.updated_at))
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    val base = initial ?: Ring(id = Reko.newRingId())
                    onSave(
                        base.copy(
                            name = name.trim(), city = city.trim(), location = location.trim(),
                            address = address.trim(), weekday = weekday, time = time.trim(),
                            parity = parity, handler = handler.trim(), fb_url = fbUrl.trim(),
                            notes = notes.trim(), active = active
                        )
                    )
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Tallenna") }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete && initial != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Poista ruokapiiri") },
            text = {
                Column {
                    Text("Piiri siirtyy roskakoriin. Sen tilaukset säilyvät ennallaan.")
                    if (openOrders > 0) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "⚠ Piirillä on $openOrders avointa tilausta. Ne näkyvät edelleen " +
                                "\"poistuvat piirit\" -kohdassa, kunnes ne on hoidettu.",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Voit palauttaa piirin roskakorista $TRASH_DAYS päivän ajan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(initial) }) {
                    Text("Poista roskakoriin")
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Peruuta") } }
        )
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  Trash
// ════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashView(
    state: AppState,
    onBack: () -> Unit,
    onRestoreProduct: (Product) -> Unit,
    onRestoreRing: (Ring) -> Unit,
    onRestoreCustomer: (Customer) -> Unit
) {
    val products = state.trashedProducts().sortedByDescending { it.deleted_at }
    val rings = state.trashedRings().sortedByDescending { it.deleted_at }
    val customers = state.trashedCustomers().sortedByDescending { it.deleted_at }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Roskakori") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin") }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            Text(
                "Poistetut tuotteet ja piirit säilyvät täällä $TRASH_DAYS päivää. " +
                    "Poisto siirtyy myös työpöytäohjelmaan seuraavassa synkronoinnissa — " +
                    "ja niin siirtyy palautuskin.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            if (products.isEmpty() && rings.isEmpty() && customers.isEmpty()) {
                Text("Roskakori on tyhjä.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }

            if (products.isNotEmpty()) {
                Text("Tuotteet (${products.size})", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                products.forEach { p ->
                    TrashRow(
                        title = p.name.ifBlank { p.id },
                        subtitle = "Poistettu ${stampShort(p.deleted_at)}",
                        daysLeft = (TRASH_DAYS - Reko.daysSince(p.deleted_at)).coerceAtLeast(0),
                        onRestore = { onRestoreProduct(p) }
                    )
                }
                Spacer(Modifier.height(18.dp))
            }

            if (customers.isNotEmpty()) {
                Text("Asiakkaat (${customers.size})", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                customers.forEach { c ->
                    TrashRow(
                        title = c.name.ifBlank { c.id },
                        subtitle = "Poistettu ${stampShort(c.deleted_at)}",
                        daysLeft = (TRASH_DAYS - Reko.daysSince(c.deleted_at)).coerceAtLeast(0),
                        onRestore = { onRestoreCustomer(c) }
                    )
                }
                Spacer(Modifier.height(18.dp))
            }

            if (rings.isNotEmpty()) {
                Text("Ruokapiirit (${rings.size})", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                rings.forEach { r ->
                    TrashRow(
                        title = r.name.ifBlank { r.id },
                        subtitle = "Poistettu ${stampShort(r.deleted_at)}",
                        daysLeft = (TRASH_DAYS - Reko.daysSince(r.deleted_at)).coerceAtLeast(0),
                        onRestore = { onRestoreRing(r) }
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun TrashRow(title: String, subtitle: String, daysLeft: Long, onRestore: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(
            Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(
                    "$subtitle  ·  poistuu ${daysLeft} vrk kuluttua",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onRestore) {
                Icon(Icons.Filled.Refresh, null)
                Spacer(Modifier.width(6.dp))
                Text("Palauta")
            }
        }
    }
}
