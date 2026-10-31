package fi.kasvila.ostot

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.concurrent.Executors

/**
 * Ostot ja varasto: koko osa yhdessä näkymässä. Sovellus kutsuu vain tätä.
 *
 * @param deviceId puhelimen pysyvä tunniste (sama kuin REKO-synkronoinnissa)
 * @param deviceName nimi, jonka työpöytä näyttää
 * @param onExit paluu sovelluksen etusivulle
 */
@Composable
fun OstotApp(deviceId: String, deviceName: String, onExit: () -> Unit) {
    val context = LocalContext.current
    val store = remember { OstoStore(context) }
    val saver = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { saver.shutdown() } }
    var state by remember { mutableStateOf(store.load()) }
    var screen by remember { mutableStateOf<OScreen>(OScreen.Home) }

    fun update(f: (OstoState) -> OstoState) {
        val next = f(state)
        state = next
        saver.execute { runCatching { store.save(next) } }
    }

    /** Muuta yhtä kertaa. Lähetetyn kerran muutos merkitään lähetettäväksi uudelleen. */
    fun updateSession(id: String, f: (OstoSession) -> OstoSession) = update { st ->
        st.copy(sessions = st.sessions.map { s ->
            if (s.id != id) s else f(s).let { n -> if (n.sentAt.isNotBlank()) n.copy(changedAfterSend = true) else n }
        })
    }

    when (val s = screen) {
        is OScreen.Home -> {
            BackHandler { onExit() }
            HomeView(
                state = state,
                onExit = onExit,
                onNewPurchase = { shop, date ->
                    val ses = OstoSession(
                        id = Osto.newSessionId(), type = "purchase", shop = shop, date = date,
                        startedAt = Osto.nowIso(), operator = state.operator
                    )
                    update { it.copy(sessions = it.sessions + ses, lastShop = shop) }
                    screen = OScreen.Session(ses.id)
                },
                onNewCount = {
                    val ses = OstoSession(
                        id = Osto.newSessionId(), type = "count", date = Osto.today(),
                        startedAt = Osto.nowIso(), operator = state.operator
                    )
                    update { it.copy(sessions = it.sessions + ses) }
                    screen = OScreen.Session(ses.id)
                },
                onOpen = { screen = OScreen.Session(it) },
                onSend = { screen = OScreen.Send },
                onHistory = { screen = OScreen.History },
                onOperator = { name -> update { it.copy(operator = name) } }
            )
        }

        is OScreen.Session -> {
            val ses = state.sessions.firstOrNull { it.id == s.id }
            if (ses == null) {
                screen = OScreen.Home
                return
            }
            BackHandler { screen = OScreen.Home }
            SessionView(
                state = state,
                session = ses,
                onBack = { screen = OScreen.Home },
                onScan = { screen = OScreen.Scan(ses.id) },
                onAddManual = { screen = OScreen.Line(ses.id, null) },
                onEditLine = { lid -> screen = OScreen.Line(ses.id, lid) },
                onChange = { f -> updateSession(ses.id, f) },
                onDelete = {
                    update { st -> st.copy(sessions = st.sessions.filter { it.id != ses.id }) }
                    screen = OScreen.Home
                }
            )
        }

        is OScreen.Scan -> {
            BackHandler { screen = OScreen.Session(s.sessionId) }
            ScanView(
                state = state,
                sessionId = s.sessionId,
                onBack = { screen = OScreen.Session(s.sessionId) },
                onCode = { code -> screen = OScreen.Line(s.sessionId, null, Osto.normEan(code), fromScan = true) }
            )
        }

        is OScreen.Line -> {
            val ses = state.sessions.firstOrNull { it.id == s.sessionId }
            if (ses == null) {
                screen = OScreen.Home
                return
            }
            val back: OScreen = if (s.fromScan) OScreen.Scan(ses.id) else OScreen.Session(ses.id)
            BackHandler { screen = back }
            LineEditorView(
                state = state,
                session = ses,
                line = s.lineId?.let { id -> ses.lines.firstOrNull { it.id == id } },
                scannedEan = s.ean,
                onCancel = { screen = back },
                onSave = { line, learn ->
                    updateSession(ses.id) { cur ->
                        val exists = cur.lines.any { it.id == line.id }
                        cur.copy(lines = if (exists) cur.lines.map { if (it.id == line.id) line else it } else cur.lines + line)
                    }
                    if (learn) update { Osto.learn(it, line) }
                    screen = back
                },
                onDelete = { lid ->
                    updateSession(ses.id) { cur -> cur.copy(lines = cur.lines.filter { it.id != lid }) }
                    screen = OScreen.Session(ses.id)
                }
            )
        }

        is OScreen.Send -> {
            BackHandler { screen = OScreen.Home }
            SendView(
                state = state,
                deviceId = deviceId,
                deviceName = deviceName,
                onBack = { screen = OScreen.Home },
                onUpdate = { f -> update(f) }
            )
        }

        is OScreen.History -> {
            BackHandler { screen = OScreen.Home }
            HistoryView(
                state = state,
                onBack = { screen = OScreen.Home },
                onOpen = { screen = OScreen.Session(it) },
                onClear = {
                    update { st -> st.copy(sessions = st.sessions.filter { !st.isDecided(it.id) }) }
                }
            )
        }
    }
}

internal sealed interface OScreen {
    data object Home : OScreen
    data class Session(val id: String) : OScreen
    data class Scan(val sessionId: String) : OScreen
    data class Line(
        val sessionId: String,
        val lineId: String?,
        val ean: String = "",
        val fromScan: Boolean = false
    ) : OScreen
    data object Send : OScreen
    data object History : OScreen
}

// ── Etusivu ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HomeView(
    state: OstoState,
    onExit: () -> Unit,
    onNewPurchase: (String, String) -> Unit,
    onNewCount: () -> Unit,
    onOpen: (String) -> Unit,
    onSend: () -> Unit,
    onHistory: () -> Unit,
    onOperator: (String) -> Unit
) {
    var askShop by remember { mutableStateOf(false) }
    var askOperator by remember { mutableStateOf(false) }
    val cat = state.catalog
    val drafts = state.sessions.filter { !it.isFinished }.sortedByDescending { it.startedAt }
    val waiting = state.sessions.filter { it.isFinished && !state.isDecided(it.id) }.sortedByDescending { it.finishedAt }
    val decided = state.sessions.count { state.isDecided(it.id) }
    val unsent = Osto.unsentCount(state)

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Ostot ja varasto") },
            navigationIcon = {
                IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin") }
            },
            actions = {
                IconButton(onClick = { askOperator = true }) { Icon(Icons.Filled.Person, "Kirjaaja") }
            }
        )
    }) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (cat.items.isEmpty()) {
                            Text("Luetteloa ei vielä ole", fontWeight = FontWeight.Bold)
                            Text(
                                "Hae raaka-aineet ja hinnat työpöytäohjelmasta kohdasta Lähetä ja päivitä. " +
                                    "Voit silti jo skannata: tuotteet yhdistetään työpöydällä.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            val ing = cat.items.count { it.kind == Kind.ING }
                            val pack = cat.items.count { it.kind == Kind.PACK }
                            Text("Luettelo: $ing raaka-ainetta, $pack pakkausta", fontWeight = FontWeight.Bold)
                            Text(
                                "Päivitetty ${Osto.dateFi(state.catalogReceivedAt)} · ${cat.eanLibrary.size + state.learned.size} tunnettua viivakoodia",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (state.operator.isNotBlank()) {
                            Text("Kirjaaja: ${state.operator}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { askShop = true }, modifier = Modifier.weight(1f).height(64.dp)) {
                        Icon(Icons.Filled.ShoppingCart, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Uusi ostokerta")
                    }
                    OutlinedButton(onClick = onNewCount, modifier = Modifier.weight(1f).height(64.dp)) {
                        Icon(Icons.Filled.Inventory, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Inventaario")
                    }
                }
            }
            item {
                Button(onClick = onSend, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Filled.Send, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (unsent > 0) "Lähetä työpöydälle ($unsent) ja päivitä" else "Päivitä luettelo työpöydältä")
                }
            }
            if (drafts.isNotEmpty()) {
                item { SectionTitle("Kesken") }
                items(drafts, key = { it.id }) { SessionRow(state, it) { onOpen(it.id) } }
            }
            if (waiting.isNotEmpty()) {
                item { SectionTitle("Valmiit") }
                items(waiting, key = { it.id }) { SessionRow(state, it) { onOpen(it.id) } }
            }
            if (cat.shoppingList.isNotEmpty()) {
                item { SectionTitle("Ostoslista (alle minimin)") }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val inCart = Osto.inCart(state)
                            for (n in cat.shoppingList.sortedBy { (inCart[n.kind to n.key] ?: 0.0) >= n.need }) {
                                val ci = Osto.item(state, n.kind, n.key)
                                val got = inCart[n.kind to n.key] ?: 0.0
                                val done = got >= n.need
                                Row(Modifier.fillMaxWidth()) {
                                    Text(
                                        (if (done) "✓ " else "") + (ci?.label() ?: n.key), Modifier.weight(1f),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        textDecoration = if (done) TextDecoration.LineThrough else null,
                                        color = if (done) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        when {
                                            done -> "korissa " + Osto.qtyText(got, n.kind)
                                            got > 0 -> "vielä " + Osto.qtyText(n.need - got, n.kind)
                                            else -> "vähintään " + Osto.qtyText(n.need, n.kind)
                                        },
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (decided > 0) {
                item {
                    TextButton(onClick = onHistory) { Text("Käsitellyt ($decided)") }
                }
            }
        }
    }

    if (askShop) {
        ShopDialog(
            shops = (listOf(state.lastShop) + state.catalog.shops).filter { it.isNotBlank() }.distinct(),
            initial = state.lastShop,
            onDismiss = { askShop = false },
            onOk = { shop, date -> askShop = false; onNewPurchase(shop, date) }
        )
    }
    if (askOperator) {
        var name by remember { mutableStateOf(state.operator) }
        AlertDialog(
            onDismissRequest = { askOperator = false },
            title = { Text("Kirjaaja") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Nimi näkyy työpöydällä jokaisessa ostokerrassa ja inventaariossa.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Nimi") })
                }
            },
            confirmButton = { TextButton(onClick = { onOperator(name.trim()); askOperator = false }) { Text("Tallenna") } },
            dismissButton = { TextButton(onClick = { askOperator = false }) { Text("Peruuta") } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShopDialog(
    shops: List<String>,
    initial: String,
    onDismiss: () -> Unit,
    onOk: (String, String) -> Unit
) {
    var shop by remember { mutableStateOf(initial) }
    var dateText by remember { mutableStateOf(Osto.dateFi(Osto.today())) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Missä ostat?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (sh in shops.take(12)) {
                        FilterChip(selected = shop == sh, onClick = { shop = sh }, label = { Text(sh) })
                    }
                }
                OutlinedTextField(shop, { shop = it }, singleLine = true, label = { Text("Kauppa tai tukku") },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(dateText, { dateText = it }, singleLine = true, label = { Text("Päivä") },
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onOk(shop.trim(), Osto.parseDate(dateText).ifBlank { Osto.today() })
            }) { Text("Aloita") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Peruuta") } }
    )
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun SessionRow(state: OstoState, s: OstoSession, onClick: () -> Unit) {
    val status = state.statusOf(s.id)?.status
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (s.isCount) Icons.Filled.Inventory else Icons.Filled.ShoppingCart, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (s.isCount) "Inventaario" else s.shop.ifBlank { "Ostokerta" },
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                )
                Text(Osto.dateFi(s.date), style = MaterialTheme.typography.bodySmall)
            }
            val sum = if (s.isCount) "" else " · " + Osto.money(Osto.sessionTotalSeen(s))
            val sent = when {
                status != null -> Osto.statusText(status)
                s.changedAfterSend -> "Muutettu lähettämisen jälkeen"
                s.sentAt.isNotBlank() -> if (s.sentVia == "file") "Tallennettu tiedostoon" else "Lähetetty"
                s.isFinished -> "Odottaa lähetystä"
                else -> "Kesken"
            }
            Text("${s.lines.size} riviä$sum · $sent", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ── Ostokerta / inventaario ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionView(
    state: OstoState,
    session: OstoSession,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onAddManual: () -> Unit,
    onEditLine: (String) -> Unit,
    onChange: ((OstoSession) -> OstoSession) -> Unit,
    onDelete: () -> Unit
) {
    val decided = state.isDecided(session.id)
    val status = state.statusOf(session.id)
    var confirmDelete by remember { mutableStateOf(false) }
    var receiptText by remember(session.id) { mutableStateOf(Osto.fmt(session.receiptTotal)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (session.isCount) "Inventaario" else session.shop.ifBlank { "Ostokerta" },
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Osto.dateFi(session.date), style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin") } },
                actions = {
                    if (!decided) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Poista") }
                }
            )
        },
        bottomBar = {
            if (!decided) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onScan, modifier = Modifier.weight(1f).height(56.dp)) {
                            Icon(Icons.Filled.QrCodeScanner, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Skannaa")
                        }
                        OutlinedButton(onClick = onAddManual, modifier = Modifier.weight(1f).height(56.dp)) {
                            Icon(Icons.Filled.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Ilman koodia")
                        }
                    }
                    if (!session.isFinished) {
                        Button(
                            onClick = { onChange { it.copy(finishedAt = Osto.nowIso()) }; onBack() },
                            enabled = session.lines.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Check, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Valmis")
                        }
                    }
                }
            }
        }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (status != null && status.status != "pending") {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(Osto.statusText(status.status) + " työpöydällä " + Osto.dateFi(status.decidedAt),
                                fontWeight = FontWeight.Bold)
                            if (status.note.isNotBlank()) Text(status.note, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else if (session.isFinished) {
                item {
                    Text(
                        if (session.sentAt.isBlank() || session.changedAfterSend)
                            "Valmis. Lähtee työpöydälle seuraavassa lähetyksessä. Voit vielä korjata rivejä."
                        else "Lähetetty. Odottaa hyväksyntää työpöydällä. Korjaukset lähtevät seuraavassa lähetyksessä.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else if (session.isCount) {
                item {
                    Text("Laske jokainen tuote ja kirjaa koko määrä. Työpöytä arvottaa määrät omilla hinnoillaan.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (session.lines.isEmpty()) {
                item {
                    Text("Ei vielä rivejä. Skannaa viivakoodi tai lisää tuote ilman koodia.",
                        modifier = Modifier.padding(vertical = 24.dp))
                }
            }
            items(session.lines, key = { it.id }) { ln ->
                LineRow(state, session, ln, enabled = !decided) { onEditLine(ln.id) }
            }
            if (!session.isCount && session.lines.isNotEmpty()) {
                item {
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Rivit yhteensä", Modifier.weight(1f))
                        Text(Osto.money(Osto.sessionTotalSeen(session), state.catalog.currency), fontWeight = FontWeight.Bold)
                    }
                }
                item {
                    OutlinedTextField(
                        value = receiptText,
                        onValueChange = {
                            receiptText = it
                            onChange { s -> s.copy(receiptTotal = Osto.parseNum(it)) }
                        },
                        enabled = !decided,
                        singleLine = true,
                        label = { Text("Kuitin loppusumma (valinnainen)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Poistetaanko?") },
            text = {
                Text(if (session.sentAt.isNotBlank())
                    "Kerta on jo lähetetty. Poisto koskee vain puhelinta: työpöydällä se pitää hylätä erikseen."
                else "Kerta ja sen ${session.lines.size} riviä poistetaan puhelimesta.")
            },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Poista") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Peruuta") } }
        )
    }
}

@Composable
private fun LineRow(state: OstoState, session: OstoSession, ln: OstoLine, enabled: Boolean, onClick: () -> Unit) {
    val item = Osto.item(state, ln.kind, ln.key)
    val title = item?.label() ?: ln.name.ifBlank { if (ln.ean.isNotBlank()) "Koodi ${ln.ean}" else "Nimetön" }
    Card(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(Osto.qtyText(ln.totalQty(), ln.kind.ifBlank { Kind.ING }))
            }
            val parts = mutableListOf<String>()
            if (ln.packages != null && ln.amount == null && ln.packQty != null) {
                parts += Osto.fmt(ln.packages, 2) + " × " + Osto.qtyText(ln.packQty, ln.kind.ifBlank { Kind.ING })
            }
            if (!session.isCount && ln.price != null) {
                val per = when (ln.pricePer) {
                    PricePer.KG -> "€/kg"; PricePer.UNIT -> "€/kpl"; PricePer.TOTAL -> "€ yht."; else -> "€/pkt"
                }
                parts += Osto.fmt(ln.price, 2) + " " + per
            }
            if (ln.bestBefore.isNotBlank()) parts += "pv " + Osto.dateFi(ln.bestBefore)
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            if (item == null) {
                Text("Yhdistetään työpöydällä", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ── Skannaus ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanView(state: OstoState, sessionId: String, onBack: () -> Unit, onCode: (String) -> Unit) {
    val ses = state.sessions.firstOrNull { it.id == sessionId }
    var typed by remember { mutableStateOf("") }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Skannaa (${ses?.lines?.size ?: 0} riviä)") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Valmis") } }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OstoCamera(
                modifier = Modifier.fillMaxWidth().weight(1f),
                readBarcodes = true,
                onBarcode = onCode
            )
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    typed, { typed = it.filter { c -> c.isDigit() } },
                    singleLine = true, label = { Text("Tai kirjoita koodi") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onCode(typed) }, enabled = typed.length >= 8) { Text("OK") }
            }
            TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)) {
                Text("Lopeta skannaus")
            }
        }
    }
}

// ── Käsitellyt ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryView(state: OstoState, onBack: () -> Unit, onOpen: (String) -> Unit, onClear: () -> Unit) {
    val done = state.sessions.filter { state.isDecided(it.id) }.sortedByDescending { it.date }
    var confirm by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Käsitellyt") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin") } },
            actions = { if (done.isNotEmpty()) TextButton(onClick = { confirm = true }) { Text("Tyhjennä") } }
        )
    }) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (done.isEmpty()) item { Text("Ei käsiteltyjä kertoja.") }
            items(done, key = { it.id }) { SessionRow(state, it) { onOpen(it.id) } }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Tyhjennetäänkö käsitellyt?") },
            text = { Text("${done.size} kertaa poistetaan puhelimesta. Työpöydällä ne säilyvät.") },
            confirmButton = { TextButton(onClick = { confirm = false; onClear() }) { Text("Tyhjennä") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Peruuta") } }
        )
    }
}
