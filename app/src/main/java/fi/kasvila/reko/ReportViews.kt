package fi.kasvila.reko

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * REKO routes and the REKO summary.
 *
 * Both are ports of desktop views, chosen because they are the two things you
 * actually want in your hand rather than on a desk: the route on the evening you
 * are driving it, and the summary when a customer asks what they still owe.
 */

private fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Toast.makeText(context, "Karttasovellusta ei voitu avata", Toast.LENGTH_SHORT).show()
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  Routes
// ════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutesView(
    state: AppState,
    onBack: () -> Unit,
    onOpenRing: (String) -> Unit
) {
    val context = LocalContext.current
    val (routes, unscheduled) = remember(state.rings) { Routes.build(state.rings) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("REKO-reitit") },
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
                "Piirit ryhmiteltyinä noutopäivän ja viikkorytmin mukaan — yksi kortti on " +
                    "yksi ajokerta. Punainen varoitus tarkoittaa, että kaksi noutoa menee " +
                    "ajallisesti päällekkäin eikä niille ole merkitty eri hoitajaa.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))

            if (routes.isEmpty() && unscheduled.isEmpty()) {
                Text(
                    "Ei ruokapiirejä. Lisää piirejä Tiedot ja synkronointi → Ruokapiirit.",
                    style = MaterialTheme.typography.bodyMedium
                )
                return@Column
            }

            routes.forEach { route ->
                RouteCard(route, onOpenRing) { url -> openUrl(context, url) }
            }

            if (unscheduled.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Ilman noutopäivää (${unscheduled.size})", fontWeight = FontWeight.Bold)
                        Text(
                            "Näille ei ole asetettu viikonpäivää, joten ne eivät kuulu mihinkään reittiin.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        unscheduled.forEach { r ->
                            TextButton(onClick = { onOpenRing(r.id) }) {
                                Text(r.name.ifBlank { "(nimetön)" })
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun RouteCard(
    route: Route,
    onOpenRing: (String) -> Unit,
    onOpenUrl: (String) -> Unit
) {
    Card(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(route.title, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium)
                    if (route.nextDate.isNotBlank()) {
                        Text(
                            "Seuraava: ${route.nextDate}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    "${route.stops.size} pysähdystä",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (route.hasConflict) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Warning, null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Päällekkäisiä noutoaikoja — merkitse eri hoitaja tai siirrä aikaa.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            route.stops.forEachIndexed { i, stop ->
                val r = stop.ring
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${i + 1}.",
                        modifier = Modifier.width(24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        r.time.ifBlank { "—" },
                        modifier = Modifier.width(92.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (stop.conflicting) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            r.name.ifBlank { "(nimetön)" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        val where = listOf(r.location, r.city).filter { it.isNotBlank() }.joinToString(", ")
                        if (where.isNotBlank() || r.handler.isNotBlank()) {
                            Text(
                                listOfNotNull(
                                    where.ifBlank { null },
                                    r.handler.ifBlank { null }?.let { "hoitaja: $it" }
                                ).joinToString("  ·  "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Routes.mapsUrlFor(r)?.let { url ->
                        IconButton(onClick = { onOpenUrl(url) }) {
                            Icon(Icons.Filled.Place, "Avaa kartalla",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (i < route.stops.size - 1) HorizontalDivider()
            }

            val routeUrl = Routes.mapsUrl(route)
            if (routeUrl != null && route.stops.size > 1) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onOpenUrl(routeUrl) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Place, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Avaa koko reitti kartalla")
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  REKO summary
// ════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RekoSummaryView(
    state: AppState,
    onBack: () -> Unit
) {
    var period by remember { mutableStateOf(SummaryPeriod.ALL) }
    var ringId by remember { mutableStateOf("") }
    var exportMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val rings = remember(state.rings) { state.rings.filter { !it.deleted } }
    val summary = remember(state, period, ringId) { RekoReports.compute(state, period, ringId) }
    // Exactly the rows behind the figures on screen, so an export can never
    // disagree with what the user is looking at.
    val (filteredOrders, filteredDeliveries) =
        remember(state, period, ringId) { RekoReports.filtered(state, period, ringId) }
    val cur = state.currency

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("REKO-yhteenveto") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin") }
                },
                actions = {
                    IconButton(onClick = { exportMenu = true }) {
                        Icon(Icons.Filled.Share, "Vie CSV")
                    }
                    DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Tilausrivit (CSV)") },
                            onClick = {
                                exportMenu = false
                                CsvExport.share(context, "kasvila_tilaukset.csv",
                                    CsvExport.orderLines(state, filteredOrders),
                                    "REKO-tilaukset")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Kuljetusrivit (CSV)") },
                            onClick = {
                                exportMenu = false
                                CsvExport.share(context, "kasvila_kuljetukset.csv",
                                    CsvExport.deliveryLines(state, filteredDeliveries),
                                    "Kotiinkuljetukset")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Tuotesummat (CSV)") },
                            onClick = {
                                exportMenu = false
                                CsvExport.share(context, "kasvila_tuotesummat.csv",
                                    CsvExport.productTotals(state, filteredOrders, filteredDeliveries),
                                    "Tuotesummat")
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Maksamattomat (CSV)") },
                            onClick = {
                                exportMenu = false
                                CsvExport.share(context, "kasvila_maksamattomat.csv",
                                    CsvExport.unpaid(state), "Maksamattomat")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Asiakasrekisteri (CSV)") },
                            onClick = {
                                exportMenu = false
                                CsvExport.share(context, "kasvila_asiakkaat.csv",
                                    CsvExport.customers(state), "Asiakasrekisteri")
                            }
                        )
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            DropdownField(
                label = "Ajanjakso",
                value = period.label,
                options = SummaryPeriod.entries.map { it.label },
                editable = false,
                onSelectIndex = { idx -> period = SummaryPeriod.entries[idx] }
            )
            Spacer(Modifier.height(10.dp))
            DropdownField(
                label = "Ruokapiiri",
                value = if (ringId.isBlank()) "Kaikki piirit"
                else rings.firstOrNull { it.id == ringId }?.name ?: "Kaikki piirit",
                options = listOf("Kaikki piirit") + rings.map { it.name.ifBlank { it.id } },
                editable = false,
                onSelectIndex = { idx -> ringId = if (idx == 0) "" else rings[idx - 1].id }
            )

            Spacer(Modifier.height(16.dp))
            // Headline figures.
            Row(Modifier.fillMaxWidth()) {
                StatCard("Myynti", Reko.money(summary.grandTotal, cur), Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                StatCard("Tilauksia", summary.orderCount.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                StatCard(
                    "Maksamatta", Reko.money(summary.unpaidTotal, cur), Modifier.weight(1f),
                    highlight = summary.unpaidTotal > 0
                )
                Spacer(Modifier.width(10.dp))
                StatCard("Asiakkaita", summary.customerCount.toString(), Modifier.weight(1f))
            }
            if (summary.homeCount > 0 && ringId.isBlank()) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    StatCard("Kuljetuksia", summary.homeCount.toString(), Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    StatCard("Kuljetusmyynti", Reko.money(summary.homeTotal, cur), Modifier.weight(1f))
                }
            }
            Text(
                "Jakeluja ${summary.distributionCount}  ·  noudettu ${summary.pickedCount}" +
                    if (summary.notPickedCount > 0) "  ·  noutamatta ${summary.notPickedCount}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
            Text(
                "Perutut tilaukset eivät ole mukana luvuissa.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (summary.orderCount == 0 && summary.homeCount == 0) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "Ei tilauksia tällä ajanjaksolla.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            RankBlock("Maksamatta asiakkaittain", summary.unpaidByCustomer, cur, emphasiseError = true)
            RankBlock("Myynti piireittäin", summary.perRing, cur)
            RankBlock("Suosituimmat tuotteet", summary.topProducts, cur)
            RankBlock("Parhaat asiakkaat", summary.topCustomers, cur)
            RankBlock("Myynti kuukausittain", summary.perMonth, cur, ranked = false)

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (highlight) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (highlight) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun RankBlock(
    title: String,
    rows: List<RankRow>,
    currency: String,
    ranked: Boolean = true,
    emphasiseError: Boolean = false
) {
    if (rows.isEmpty()) return
    Spacer(Modifier.height(20.dp))
    Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    rows.forEachIndexed { i, row ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (ranked) {
                Text(
                    "${i + 1}.",
                    modifier = Modifier.width(28.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                row.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (row.note.isNotBlank()) {
                Text(
                    row.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 10.dp)
                )
            }
            Text(
                Reko.money(row.value, currency),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (emphasiseError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        }
    }
}
