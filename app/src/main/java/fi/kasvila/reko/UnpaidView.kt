package fi.kasvila.reko

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/**
 * Who still owes money, and the shortest path to doing something about it.
 *
 * Unpaid orders were visible before — as a figure in the summary and a flag on
 * each order — but never as a list you could act on. Chasing one customer meant
 * finding their order, reading the amount, switching to Messages, and typing the
 * same sentence you type every week.
 */

/** One customer's outstanding balance. */
data class UnpaidEntry(
    val name: String,
    val contact: String,
    val orders: List<Order>,
    val deliveries: List<Delivery>,
    val total: Double,
    val oldestDate: String
) {
    val count: Int get() = orders.size + deliveries.size

    /** Days since the oldest unpaid order, or 0 when undated/unparseable. */
    fun daysOverdue(): Long {
        val d = try { LocalDate.parse(oldestDate) } catch (e: Exception) { return 0 }
        return java.time.temporal.ChronoUnit.DAYS.between(d, LocalDate.now()).coerceAtLeast(0)
    }
}

object Unpaid {

    /** Everyone with an outstanding balance, biggest debt first. */
    fun list(state: AppState): List<UnpaidEntry> {
        val orders = state.liveOrders.filter { !it.paid && !it.cancelled && it.total > 0 }
        val deliveries = state.liveDeliveries.filter { !it.paid && !it.cancelled && it.total > 0 }

        val keys = LinkedHashMap<String, String>()      // normalised -> display
        for (o in orders) keys.putIfAbsent(Customer.norm(o.customer), o.customer.trim())
        for (d in deliveries) keys.putIfAbsent(Customer.norm(d.customer), d.customer.trim())

        return keys.map { (key, display) ->
            val myOrders = orders.filter { Customer.norm(it.customer) == key }
            val myDeliveries = deliveries.filter { Customer.norm(it.customer) == key }
            val contact = listOfNotNull(
                CustomerBook.find(state, display)?.let { c ->
                    listOf(c.phone, c.contact, c.email).firstOrNull { it.isNotBlank() }
                },
                myOrders.firstOrNull { it.contact.isNotBlank() }?.contact,
                myDeliveries.firstOrNull { it.contact.isNotBlank() }?.contact
            ).firstOrNull { it.isNotBlank() } ?: ""
            val dates = (myOrders.map { it.date } + myDeliveries.map { it.deliveryDate })
                .filter { it.isNotBlank() }
            UnpaidEntry(
                name = display.ifBlank { "(nimetön)" },
                contact = contact,
                orders = myOrders.sortedBy { it.date },
                deliveries = myDeliveries.sortedBy { it.deliveryDate },
                total = myOrders.sumOf { it.total } + myDeliveries.sumOf { it.total },
                oldestDate = dates.minOrNull() ?: ""
            )
        }.sortedByDescending { it.total }
    }

    fun grandTotal(state: AppState): Double = list(state).sumOf { it.total }

    /**
     * The reminder message.
     *
     * Deliberately short, factual and free of pressure: this goes to a neighbour
     * who buys vegetables, not to a debtor. It states what was ordered and when,
     * because "you owe me money" without a date is the thing that actually
     * annoys people.
     */
    fun reminderText(entry: UnpaidEntry, currency: String): String {
        val dates = (entry.orders.map { it.date } + entry.deliveries.map { it.deliveryDate })
            .filter { it.isNotBlank() }.distinct().sorted()
        val whenPart = when {
            dates.isEmpty() -> ""
            dates.size == 1 -> " (${dates.first()})"
            else -> " (${dates.first()}–${dates.last()}, ${dates.size} tilausta)"
        }
        return buildString {
            append("Hei ")
            append(entry.name.substringBefore(' '))
            append("! Muistutus Kasvilan REKO-tilauksesta: ")
            append(Reko.money(entry.total, currency))
            append(" on vielä maksamatta")
            append(whenPart)
            append(". Kiitos!")
        }
    }

    /** Hand the message to the share sheet — SMS, WhatsApp, Messenger, email. */
    fun sendReminder(context: Context, entry: UnpaidEntry, currency: String) {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, reminderText(entry, currency))
                putExtra(Intent.EXTRA_SUBJECT, "Kasvila REKO – maksumuistutus")
            }
            context.startActivity(Intent.createChooser(send, "Lähetä muistutus"))
        } catch (e: Exception) {
            Toast.makeText(context, "Viestin lähetys epäonnistui", Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnpaidView(
    state: AppState,
    onBack: () -> Unit,
    onMarkPaid: (List<Order>, List<Delivery>) -> Unit,
    onOpenOrder: (Order) -> Unit,
    onExportCsv: () -> Unit,
    onReceipt: (UnpaidEntry) -> Unit
) {
    val context = LocalContext.current
    val entries = remember(state.orders, state.deliveries, state.customers) { Unpaid.list(state) }
    val total = entries.sumOf { it.total }
    var expanded by remember { mutableStateOf<String?>(null) }
    var confirmPaid by remember { mutableStateOf<UnpaidEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Maksamattomat") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin") }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        IconButton(onClick = onExportCsv) { Icon(Icons.Filled.Share, "Vie CSV") }
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            if (entries.isEmpty()) {
                Text(
                    "Ei maksamattomia. Kaikki tilaukset on kuitattu maksetuiksi.",
                    style = MaterialTheme.typography.bodyLarge
                )
                return@Column
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        Reko.money(total, state.currency),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        "${entries.size} asiakasta  ·  ${entries.sumOf { it.count }} tilausta",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            entries.forEach { e ->
                val isOpen = expanded == e.name
                Card(
                    onClick = { expanded = if (isOpen) null else e.name },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.name, fontWeight = FontWeight.Bold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    buildString {
                                        append("${e.count} tilausta")
                                        val days = e.daysOverdue()
                                        if (days > 0) append("  ·  vanhin $days vrk sitten")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (e.contact.isNotBlank()) {
                                    Text(
                                        e.contact,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Text(
                                Reko.money(e.total, state.currency),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        if (isOpen) {
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(8.dp))
                            e.orders.forEach { o ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        o.date.ifBlank { "(ei päivää)" },
                                        modifier = Modifier.width(96.dp),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        state.rings.firstOrNull { it.id == o.group_id }?.name ?: "",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        Reko.money(o.total, state.currency),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    TextButton(onClick = { onOpenOrder(o) }) { Text("Avaa") }
                                }
                            }
                            e.deliveries.forEach { d ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        d.deliveryDate.ifBlank { "(ei päivää)" },
                                        modifier = Modifier.width(96.dp),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "Kuljetus", modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        Reko.money(d.total, state.currency),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }

                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { Unpaid.sendReminder(context, e, state.currency) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.Share, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Muistuta", maxLines = 1)
                                }
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = { confirmPaid = e },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Maksettu", maxLines = 1)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { onReceipt(e) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Tulosta kuitti")
                            }
                            Text(
                                "Muistutusviesti avautuu valitsemaasi sovellukseen — " +
                                    "voit muokata sitä ennen lähetystä.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    confirmPaid?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmPaid = null },
            title = { Text("Merkitse maksetuksi") },
            text = {
                Text(
                    "Merkitään ${e.count} tilausta maksetuiksi, yhteensä " +
                        "${Reko.money(e.total, state.currency)} (${e.name}).\n\n" +
                        "Muutos siirtyy työpöytäohjelmaan seuraavassa synkronoinnissa."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmPaid = null
                    onMarkPaid(e.orders, e.deliveries)
                }) { Text("Merkitse maksetuksi") }
            },
            dismissButton = {
                TextButton(onClick = { confirmPaid = null }) { Text("Peruuta") }
            }
        )
    }
}
