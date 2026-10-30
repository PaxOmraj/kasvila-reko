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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The customer register.
 *
 * REKO customers repeat: the same dozen people order most weeks, often the same
 * things. The register turns that from a fact you retype every time into one the
 * app already knows — a name that completes itself, a contact you recorded once,
 * and last week's order as a starting point for this week's.
 *
 * History is never stored on the customer. It is derived from the orders by
 * matching the name, so there is only ever one copy of the fact, and renaming a
 * customer cannot orphan their past.
 */

/** One customer's activity, derived on the fly. */
data class CustomerHistory(
    val orders: List<Order>,
    val deliveries: List<Delivery>,
    val total: Double,
    val unpaid: Double,
    val lastDate: String
) {
    val count: Int get() = orders.size + deliveries.size
}

object CustomerBook {

    /**
     * Every name the app knows, for autocomplete.
     *
     * Deliberately includes names that appear only in past orders and were never
     * added to the register. The register starts empty, but the orders do not —
     * so completion works from the first day rather than after the user has done
     * a round of data entry that earns them nothing.
     */
    fun knownNames(state: AppState): List<String> {
        val out = LinkedHashMap<String, String>()   // normalised -> display
        for (c in state.activeCustomers()) {
            val n = c.name.trim()
            if (n.isNotEmpty()) out.putIfAbsent(Customer.norm(n), n)
        }
        for (o in state.liveOrders) {
            val n = o.customer.trim()
            if (n.isNotEmpty()) out.putIfAbsent(Customer.norm(n), n)
        }
        for (d in state.liveDeliveries) {
            val n = d.customer.trim()
            if (n.isNotEmpty()) out.putIfAbsent(Customer.norm(n), n)
        }
        return out.values.sortedBy { it.lowercase() }
    }

    /** Names starting with, then containing, the typed prefix. Max [limit]. */
    fun suggest(state: AppState, typed: String, limit: Int = 6): List<String> {
        val q = Customer.norm(typed)
        if (q.length < 2) return emptyList()
        val all = knownNames(state)
        val starts = all.filter { Customer.norm(it).startsWith(q) }
        val contains = all.filter { Customer.norm(it).contains(q) && it !in starts }
        return (starts + contains).filter { Customer.norm(it) != q }.take(limit)
    }

    fun historyFor(state: AppState, name: String): CustomerHistory {
        val key = Customer.norm(name)
        if (key.isEmpty()) return CustomerHistory(emptyList(), emptyList(), 0.0, 0.0, "")
        val orders = state.liveOrders
            .filter { Customer.norm(it.customer) == key }
            .sortedByDescending { it.date }
        val deliveries = state.liveDeliveries
            .filter { Customer.norm(it.customer) == key }
            .sortedByDescending { it.deliveryDate }
        val live = orders.filter { !it.cancelled } + emptyList()
        val liveDeliv = deliveries.filter { !it.cancelled }
        val total = live.sumOf { it.total } + liveDeliv.sumOf { it.total }
        val unpaid = live.filter { !it.paid }.sumOf { it.total } +
            liveDeliv.filter { !it.paid }.sumOf { it.total }
        val last = listOfNotNull(
            orders.firstOrNull()?.date?.takeIf { it.isNotBlank() },
            deliveries.firstOrNull()?.deliveryDate?.takeIf { it.isNotBlank() }
        ).maxOrNull() ?: ""
        return CustomerHistory(orders, deliveries, total, unpaid, last)
    }

    /**
     * The lines of this customer's most recent real order — what "same as last
     * time" means. Cancelled orders are skipped: the customer changed their mind
     * about those, so repeating them is the one thing they did not want.
     *
     * Prices are refreshed from the current catalog rather than copied from the
     * old order, because charging last month's price is a quiet way to lose
     * money on every repeat customer.
     */
    fun lastOrderLines(state: AppState, name: String, products: List<Product>): List<Line> {
        val key = Customer.norm(name)
        if (key.isEmpty()) return emptyList()
        val prev = state.liveOrders
            .filter { Customer.norm(it.customer) == key && !it.cancelled && it.lines.isNotEmpty() }
            .maxByOrNull { it.date + it.updated_at }
            ?: return emptyList()
        val byId = products.associateBy { it.id }
        return prev.lines.map { l ->
            val p = byId[l.product_id]
            if (p != null) l.copy(name = p.name, unit = p.unit, unit_price = p.price) else l
        }
    }

    /** Find the register entry for a name, if there is one. */
    fun find(state: AppState, name: String): Customer? {
        val key = Customer.norm(name)
        if (key.isEmpty()) return null
        return state.customers.firstOrNull { !it.deleted && Customer.norm(it.name) == key }
    }

    /**
     * Names that have ordered but are not in the register yet — offered as a
     * one-tap import so the register fills itself from real activity instead of
     * from an evening of typing.
     */
    fun unregistered(state: AppState): List<String> {
        val known = state.customers.filter { !it.deleted }.map { Customer.norm(it.name) }.toSet()
        val seen = LinkedHashMap<String, String>()
        for (o in state.liveOrders) {
            val n = o.customer.trim()
            if (n.isNotEmpty() && Customer.norm(n) !in known) seen.putIfAbsent(Customer.norm(n), n)
        }
        for (d in state.liveDeliveries) {
            val n = d.customer.trim()
            if (n.isNotEmpty() && Customer.norm(n) !in known) seen.putIfAbsent(Customer.norm(n), n)
        }
        return seen.values.sortedBy { it.lowercase() }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  List
// ════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomersView(
    state: AppState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Customer) -> Unit,
    onImportAll: (List<String>) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var showInactive by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }

    val unregistered = remember(state.customers, state.orders, state.deliveries) {
        CustomerBook.unregistered(state)
    }

    val visible = remember(state.customers, query, showInactive) {
        val q = Customer.norm(query)
        state.customers
            .filter { !it.deleted }
            .filter { showInactive || it.active }
            .filter {
                q.isBlank() || Customer.norm(it.name).contains(q) ||
                    it.phone.contains(q) || it.contact.lowercase().contains(q)
            }
            .sortedWith(compareBy({ !it.active }, { it.name.lowercase() }))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Asiakkaat") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin") }
                },
                actions = { IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, "Uusi asiakas") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxSize()) {
            OutlinedTextField(
                query, { query = it },
                label = { Text("Hae asiakasta") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(showInactive, { showInactive = it })
                Text("Näytä myös poistetut käytöstä", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Text(
                    "${visible.size} kpl",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (unregistered.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "${unregistered.size} tilannutta ei ole vielä rekisterissä",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            unregistered.take(6).joinToString(", ") +
                                if (unregistered.size > 6) " …" else "",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = { confirmImport = true }) {
                            Text("Lisää kaikki rekisteriin")
                        }
                    }
                }
            }

            if (visible.isEmpty() && unregistered.isEmpty()) {
                Text(
                    "Ei asiakkaita. Asiakas lisätään plus-painikkeesta, tai hän ilmestyy " +
                        "tähän itsestään kun hänelle kirjataan ensimmäinen tilaus.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp)
                )
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize().padding(top = 6.dp)) {
                items(visible, key = { it.id }) { c ->
                    val h = CustomerBook.historyFor(state, c.name)
                    CustomerRow(c, h, state.currency) { onEdit(c) }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }

    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text("Lisää tilanneet rekisteriin") },
            text = {
                Text(
                    "Lisätään ${unregistered.size} nimeä asiakasrekisteriin. Yhteystiedot " +
                        "jäävät tyhjiksi — voit täydentää ne myöhemmin. Tilauksiin ei kosketa."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmImport = false; onImportAll(unregistered) }) {
                    Text("Lisää")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = false }) { Text("Peruuta") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomerRow(c: Customer, h: CustomerHistory, currency: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(
            Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        c.name.ifBlank { "(nimetön)" },
                        fontWeight = FontWeight.Bold,
                        color = if (c.active) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = if (c.active) null else TextDecoration.LineThrough
                    )
                    if (!c.active) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "POIS KÄYTÖSTÄ", style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                val contact = listOf(c.phone, c.contact, c.email).firstOrNull { it.isNotBlank() }
                if (contact != null) {
                    Text(
                        contact, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    if (h.count == 0) "Ei tilauksia"
                    else "${h.count} tilausta  ·  ${Reko.money(h.total, currency)}" +
                        if (h.lastDate.isNotBlank()) "  ·  viimeksi ${h.lastDate}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (h.count == 0) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary
                )
                if (h.unpaid > 0) {
                    Text(
                        "Maksamatta ${Reko.money(h.unpaid, currency)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Icon(Icons.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  Edit + history
// ════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerEditView(
    state: AppState,
    initial: Customer?,
    onBack: () -> Unit,
    onSave: (Customer) -> Unit,
    onDelete: (Customer) -> Unit,
    onOpenOrder: (Order) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var phone by remember { mutableStateOf(initial?.phone ?: "") }
    var email by remember { mutableStateOf(initial?.email ?: "") }
    var contact by remember { mutableStateOf(initial?.contact ?: "") }
    var address by remember { mutableStateOf(initial?.address ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var active by remember { mutableStateOf(initial?.active ?: true) }
    var confirmDelete by remember { mutableStateOf(false) }

    val history = remember(state, initial?.name) {
        CustomerBook.historyFor(state, initial?.name ?: "")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "Uusi asiakas" else "Asiakas") },
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
                name, { name = it }, label = { Text("Nimi") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                phone, { phone = it }, label = { Text("Puhelin") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                email, { email = it }, label = { Text("Sähköposti") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                contact, { contact = it },
                label = { Text("Muu yhteystieto (esim. Facebook-nimi)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                address, { address = it }, label = { Text("Osoite") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                notes, { notes = it }, label = { Text("Muistiinpanot") },
                modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 4
            )

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Käytössä", fontWeight = FontWeight.Bold)
                    Text(
                        if (active) "Ehdotetaan tilausta kirjattaessa."
                        else "Piilotettu ehdotuksista. Tilaushistoria säilyy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(active, { active = it })
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    val base = initial ?: Customer(id = Reko.newCustomerId())
                    onSave(
                        base.copy(
                            name = name.trim(), phone = phone.trim(), email = email.trim(),
                            contact = contact.trim(), address = address.trim(),
                            notes = notes.trim(), active = active
                        )
                    )
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Tallenna") }

            // ── history ──
            if (initial != null) {
                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(14.dp))
                Text("Tilaushistoria", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                if (history.count == 0) {
                    Text(
                        "Ei vielä tilauksia.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "${history.count} tilausta  ·  yhteensä ${Reko.money(history.total, state.currency)}" +
                            if (history.unpaid > 0)
                                "  ·  maksamatta ${Reko.money(history.unpaid, state.currency)}"
                            else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (history.unpaid > 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    history.orders.take(20).forEach { o ->
                        Card(
                            onClick = { onOpenOrder(o) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(Modifier.fillMaxWidth()) {
                                    Text(
                                        o.date.ifBlank { "(ei päivää)" },
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        textDecoration = if (o.cancelled) TextDecoration.LineThrough else null
                                    )
                                    Text(
                                        Reko.money(o.total, state.currency),
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                if (o.lines.isNotEmpty()) {
                                    Text(
                                        o.lines.joinToString("  ·  ") {
                                            "${it.name} ${trimQty(it.qty)} ${Reko.unitFor(it, state.products)}"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                val flags = buildList {
                                    if (o.cancelled) add("PERUTTU")
                                    if (o.paid) add("Maksettu") else add("Maksamatta")
                                    if (o.picked) add("Noudettu")
                                }
                                Text(
                                    flags.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (o.cancelled || !o.paid) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                    if (history.deliveries.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Kotiinkuljetukset (${history.deliveries.size})",
                            style = MaterialTheme.typography.labelLarge)
                        history.deliveries.take(10).forEach { d ->
                            Text(
                                "• ${d.deliveryDate.ifBlank { "(ei päivää)" }}  ·  " +
                                    Reko.money(d.total, state.currency),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete && initial != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Poista asiakas") },
            text = {
                Column {
                    Text(
                        "Asiakas siirtyy roskakoriin ja katoaa ehdotuksista. " +
                            "Tilaukset säilyvät ennallaan."
                    )
                    if (history.count > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "⚠ Asiakkaalla on ${history.count} tilausta.",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Voit palauttaa hänet roskakorista $TRASH_DAYS päivän ajan.",
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

private fun trimQty(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else String.format(java.util.Locale.US, "%.2f", v).replace(".", ",")
