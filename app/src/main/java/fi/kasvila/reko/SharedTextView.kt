package fi.kasvila.reko

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/**
 * The landing screen for text shared in from another app.
 *
 * A REKO order arrives as a Facebook or Messenger comment. The shortest route
 * from there to a recorded order used to be: switch app, open the right ring,
 * open the right date, tap "new order", long-press, paste, parse. This screen
 * collapses that into one question — which ring, which day — because the text
 * itself is already in hand and everything else can be derived.
 *
 * The ring and date are pre-selected to the most plausible answer (the ring
 * whose pickup is soonest), so the common case is a single tap on "Jatka".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedTextView(
    state: AppState,
    text: String,
    onBack: () -> Unit,
    onContinue: (String, String) -> Unit,
    onKuljetus: () -> Unit
) {
    val rings = remember(state.rings) {
        state.visibleRings().filter { !it.deleted }
    }

    // Pre-select the ring whose next pickup is soonest — on a normal week that
    // is the one the comment is about.
    val ringDates = remember(rings) {
        rings.associate { it.id to Reko.upcomingDates(it, 8) }
    }
    val defaultRing = remember(rings) {
        rings.minByOrNull { ringDates[it.id]?.firstOrNull() ?: "9999" }
    }

    var ringId by remember { mutableStateOf(defaultRing?.id ?: "") }
    val dates = ringDates[ringId] ?: emptyList()
    var date by remember(ringId) {
        mutableStateOf(dates.firstOrNull() ?: LocalDate.now().toString())
    }

    // Parse right here so the screen can say what it found before committing to
    // anything. Offline rules only — see OrderEditView for why.
    val catalog = remember(state.products, state.localAliases) {
        OrderParser.mergeAliases(state.activeProducts(), state.localAliases)
    }
    val preview = remember(text, catalog) {
        val cn = OrderParser.extractCustomer(text, catalog)
        val res = OrderParser.parse(cn.second, catalog)
        Triple(cn.first, res.lines, res.unmatched)
    }
    val (guessedCustomer, foundLines, unmatchedFrags) = preview

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Jaettu tilaus") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin") }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            if (rings.isEmpty()) {
                Text(
                    "Ei ruokapiirejä, joten tilausta ei voi vielä kirjata. " +
                        "Lisää piiri kohdassa Asetukset → Tiedot ja synkronointi → Ruokapiirit, " +
                        "tai synkronoi työpöytäohjelman kanssa.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Takaisin")
                }
                return@Column
            }

            // ── what was recognised ──
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (foundLines.isEmpty())
                        MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        when {
                            foundLines.isEmpty() -> "Ei tunnistettuja tuotteita"
                            foundLines.size == 1 -> "1 tuote tunnistettu"
                            else -> "${foundLines.size} tuotetta tunnistettu"
                        },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (guessedCustomer != null) {
                        Text(
                            "Asiakas: $guessedCustomer",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    foundLines.forEach { l ->
                        Text(
                            "• ${l.name}  ${numOf(l.qty)} ${l.unit}" +
                                if (l.confidence == "medium") "   (epävarma)" else "",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (unmatchedFrags.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Tunnistamatta: " + unmatchedFrags.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Voit tarkistaa ja korjata kaiken seuraavassa näkymässä — mitään ei " +
                            "tallenneta vielä.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── where does it go ──
            Spacer(Modifier.height(18.dp))
            Text("Mihin tilaus kirjataan?", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            DropdownField(
                label = "Ruokapiiri",
                value = rings.firstOrNull { it.id == ringId }?.name ?: "",
                options = rings.map { r ->
                    val next = ringDates[r.id]?.firstOrNull()
                    r.name.ifBlank { r.id } + if (next != null) "  —  $next" else ""
                },
                editable = false,
                onSelectIndex = { idx -> ringId = rings[idx].id }
            )
            Spacer(Modifier.height(10.dp))
            DropdownField(
                label = "Noutopäivä (VVVV-KK-PP)",
                value = date,
                options = dates,
                editable = true,
                onValueChange = { date = it },
                onSelectIndex = { idx -> date = dates[idx] }
            )

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { onContinue(ringId, date.trim()) },
                enabled = ringId.isNotBlank() && date.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Jatka tilaukseen") }

            OutlinedButton(
                onClick = onKuljetus,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Tämä onkin kotiinkuljetus") }

            // ── the raw text, for checking ──
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("Jaettu teksti", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun numOf(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else String.format(java.util.Locale.US, "%.2f", v).replace(".", ",")
