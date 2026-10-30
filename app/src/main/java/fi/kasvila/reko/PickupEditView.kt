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

/**
 * One pickup's exceptions: cancelled, moved, renamed, annotated.
 *
 * The ring says "every other Thursday at five, at Ratina". This screen is where
 * you say "except this one" — and the saying travels to the desktop, so the
 * cancellation is not a thing one device knows and the other contradicts.
 *
 * An unremarkable pickup stores nothing. The record is created the moment
 * something is actually said about the day, and removed again when the last of
 * it is undone, so the sync never carries a list of days on which nothing
 * happened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickupEditView(
    state: AppState,
    ringId: String,
    date: String,
    onBack: () -> Unit,
    onSave: (Distribution) -> Unit,
    onClear: (Distribution) -> Unit
) {
    val ring = state.rings.firstOrNull { it.id == ringId }
    val existing = state.distributionFor(ringId, date)

    var cancelled by remember { mutableStateOf(existing?.cancelled ?: false) }
    var time by remember { mutableStateOf(existing?.time ?: "") }
    var address by remember { mutableStateOf(existing?.address ?: "") }
    var status by remember { mutableStateOf(existing?.status ?: "planned") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }

    val orders = state.liveOrders.filter { it.group_id == ringId && it.date == date && !it.cancelled }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Noudon tiedot")
                        Text(
                            (ring?.name ?: ringId) + "  ·  " + date,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
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
                "Tässä poiketaan piirin vakiotiedoista yhden noudon osalta. Tyhjä kenttä " +
                    "tarkoittaa \"kuten aina\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ── cancelled ──
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ei jakelua tänä päivänä", fontWeight = FontWeight.Bold)
                    Text(
                        "Päivä katoaa noutopäivävalikoista myös työpöytäohjelmassa. " +
                            "Jo kirjatut tilaukset säilyvät.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(cancelled, { cancelled = it })
            }
            if (cancelled && orders.isNotEmpty()) {
                Text(
                    "⚠ Tälle päivälle on jo ${orders.size} tilausta yhteensä " +
                        "${Reko.money(orders.sumOf { it.total }, state.currency)}. " +
                        "Ne säilyvät, mutta muista ilmoittaa asiakkaille.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // ── overrides ──
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))
            Text("Poikkeava aika ja paikka", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                time, { time = it },
                label = { Text("Kellonaika") },
                placeholder = { Text(ring?.time?.ifBlank { "esim. 17:00–18:00" } ?: "") },
                supportingText = {
                    Text(
                        if (ring?.time?.isNotBlank() == true) "Tyhjä = piirin oma (${ring.time})"
                        else "Tyhjä = piirin oma"
                    )
                },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                address, { address = it },
                label = { Text("Noutopaikka / osoite") },
                placeholder = { Text(ring?.address ?: "") },
                supportingText = {
                    Text(
                        if (ring?.address?.isNotBlank() == true) "Tyhjä = piirin oma (${ring.address})"
                        else "Tyhjä = piirin oma"
                    )
                },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )

            // ── status + note ──
            Spacer(Modifier.height(16.dp))
            DropdownField(
                label = "Tila",
                value = Distribution.statusLabel(status),
                options = Distribution.STATUSES.map { Distribution.statusLabel(it) },
                editable = false,
                onSelectIndex = { idx -> status = Distribution.STATUSES[idx] }
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                notes, { notes = it },
                label = { Text("Muistiinpano tästä noudosta") },
                modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5
            )

            if (orders.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "${orders.size} tilausta  ·  ${Reko.money(orders.sumOf { it.total }, state.currency)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    val base = existing ?: Distribution(
                        id = Distribution.keyOf(ringId, date),
                        group_id = ringId,
                        date = date
                    )
                    onSave(
                        base.copy(
                            cancelled = cancelled,
                            time = time.trim(),
                            address = address.trim(),
                            status = status,
                            notes = notes.trim()
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Tallenna") }

            if (existing != null && existing.isNoteworthy()) {
                OutlinedButton(
                    onClick = { onClear(existing) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Poista poikkeus (takaisin normaaliksi)") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
