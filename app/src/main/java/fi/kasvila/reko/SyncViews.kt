package fi.kasvila.reko

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The synchronisation screens.
 *
 * Nothing on these screens writes anything until the user presses "Sovella
 * muutokset" on the preview. That is the single most important property of the
 * whole feature: a sync is a proposal first and a change second, so a wrong file,
 * a stale device or a surprise batch of deletions is something you decline rather
 * than something you discover afterwards.
 */

// ════════════════════════════════════════════════════════════════════════════
//  Sync hub
// ════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncView(
    state: AppState,
    snapshots: List<Snapshot>,
    onBack: () -> Unit,
    onExportFile: () -> Unit,
    onShareFile: () -> Unit,
    onImportFile: () -> Unit,
    onLanSync: () -> Unit,
    onOpenLog: () -> Unit,
    onRenameDevice: (String) -> Unit,
    onUndoLast: () -> Unit
) {
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf(state.deviceName) }
    var confirmUndo by remember { mutableStateOf(false) }

    val lastSync = state.syncLog.lastOrNull()
    val canUndo = lastSync != null && lastSync.snapshot.isNotBlank() &&
        snapshots.any { it.file == lastSync.snapshot }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Laiteyhteys ja synkronointi") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin") }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            // ── this device ──
            Text("Tämä laite", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            if (editingName) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        nameDraft, { nameDraft = it },
                        label = { Text("Laitteen nimi") },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        onRenameDevice(nameDraft.trim().ifBlank { "Puhelin" })
                        editingName = false
                    }) { Text("OK") }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(state.deviceName.ifBlank { "Puhelin" },
                            style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Tunniste ${state.deviceId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { nameDraft = state.deviceName; editingName = true }) {
                        Text("Nimeä")
                    }
                }
            }
            Text(
                "Nimi näkyy työpöytäohjelmassa synkronoinnin aikana.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(18.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))

            // ── LAN ──
            Text("Yhdistä lähiverkossa", fontWeight = FontWeight.Bold)
            Text(
                "Nopein tapa: avaa työpöytäohjelmassa REKO → Synkronointi → Avaa yhteys, " +
                    "ja valitse kone tästä. Molemmat näyttävät esikatselun, eikä mitään " +
                    "tallenneta ennen kuin kumpikin hyväksyy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            Button(onClick = onLanSync, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Icon(Icons.Filled.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text("Yhdistä työpöytään")
            }

            Spacer(Modifier.height(18.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))

            // ── file ──
            Text("Tiedostosynkronointi", fontWeight = FontWeight.Bold)
            Text(
                "Toimii aina, myös ilman lähiverkkoa. Sama tiedosto kulkee molempiin " +
                    "suuntiin: vie se koneelle, tai tuo koneelta saatu tiedosto tänne.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            OutlinedButton(onClick = onImportFile, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Icon(Icons.Filled.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("Tuo synkronointitiedosto")
            }
            OutlinedButton(onClick = onExportFile, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Icon(Icons.Filled.Upload, null)
                Spacer(Modifier.width(8.dp))
                Text("Tallenna synkronointitiedosto")
            }
            OutlinedButton(onClick = onShareFile, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Icon(Icons.Filled.Share, null)
                Spacer(Modifier.width(8.dp))
                Text("Jaa synkronointitiedosto")
            }
            Text(
                "Tiedosto sisältää tuotteet, piirit, tilaukset, kuljetukset ja opitut " +
                    "tuotenimet — kaiken mitä kaksisuuntainen yhdistäminen tarvitsee.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(Modifier.height(18.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))

            // ── peers ──
            Text("Synkronoidut laitteet", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            if (state.peers.isEmpty()) {
                Text(
                    "Ei vielä yhtään. Ensimmäinen synkronointi lisää työpöytäohjelman tähän.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                state.peers.entries.sortedByDescending { it.value.lastSyncAt }.forEach { (id, info) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(info.name.ifBlank { id }, fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Viimeksi ${stampShort(info.lastSyncAt)}" +
                                    if (info.lastDirection == "lan") "  ·  lähiverkko" else "  ·  tiedosto",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))

            // ── undo + log ──
            Text("Turvatoiminnot", fontWeight = FontWeight.Bold)
            Text(
                "Ennen jokaista synkronointia otetaan varmuuskopio. Viimeisin voidaan perua " +
                    "kokonaan, ja vanhemmat löytyvät lokista.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            OutlinedButton(
                onClick = { confirmUndo = true },
                enabled = canUndo,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            ) { Text("Peru viimeisin synkronointi") }
            if (lastSync != null && canUndo) {
                Text(
                    "Palauttaa tilanteen ennen synkronointia ${stampShort(lastSync.at)} (${lastSync.peerName}).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            OutlinedButton(onClick = onOpenLog, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Synkronointiloki ja varmuuskopiot (${snapshots.size})")
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmUndo && lastSync != null) {
        AlertDialog(
            onDismissRequest = { confirmUndo = false },
            title = { Text("Peru viimeisin synkronointi") },
            text = {
                Text(
                    "Palautetaan koko tila siihen, mitä se oli ennen synkronointia " +
                        "${stampShort(lastSync.at)} (${lastSync.peerName}).\n\n" +
                        "Myös perumisesta otetaan varmuuskopio, joten tämänkin voi perua."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmUndo = false; onUndoLast() }) { Text("Peru") }
            },
            dismissButton = { TextButton(onClick = { confirmUndo = false }) { Text("Sulje") } }
        )
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  Preview + conflicts
// ════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncPreviewView(
    plan: SyncEngine.MergePlan,
    currency: String,
    onCancel: () -> Unit,
    onApply: (Map<String, SyncEngine.Choice>) -> Unit
) {
    // Start from the engine's defaults (newest wins for edits, keep for deletes)
    // so the common case is a single tap, while every row stays overridable.
    val choices = remember(plan) { mutableStateMapOf<String, SyncEngine.Choice>().apply { putAll(plan.defaultChoices()) } }
    val s = plan.stats

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Synkronoinnin esikatselu") },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Peruuta") }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Peruuta") }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = { onApply(choices.toMap()) },
                        modifier = Modifier.weight(1.4f),
                        enabled = !s.isEmpty()
                    ) { Text(if (s.isEmpty()) "Ei muutoksia" else "Sovella muutokset") }
                }
            }
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            Text(
                plan.peerName,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
            )
            Text(
                "Tiedosto kirjoitettu ${stampShort(plan.incomingExportedAt)}" +
                    if (plan.effectiveLastSync.isBlank()) "  ·  ensimmäinen synkronointi"
                    else "  ·  edellinen synkronointi ${stampShort(plan.effectiveLastSync)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ── warnings ──
            if (plan.warnings.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        plan.warnings.forEach { w ->
                            Row(Modifier.padding(vertical = 3.dp)) {
                                Icon(
                                    Icons.Filled.Warning, null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    w.removePrefix("⚠ "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }

            // ── what will change ──
            Spacer(Modifier.height(16.dp))
            Text("Mitä muuttuu", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            if (s.isEmpty()) {
                Text(
                    "Ei mitään — molemmilla laitteilla on jo samat tiedot.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                ChangeLine("Tuotteet", s.productsNew, s.productsUpd)
                ChangeLine("Ruokapiirit", s.ringsNew, s.ringsUpd)
                ChangeLine("Tilaukset", s.ordersNew, s.ordersUpd)
                ChangeLine("Kuljetukset", s.deliveriesNew, s.deliveriesUpd)
                ChangeLine("Asiakkaat", s.customersNew, s.customersUpd)
                ChangeLine("Jakelupoikkeukset", s.distsNew, s.distsUpd)
                if (s.aliasesAdded > 0) {
                    Text("Opittuja tuotenimiä: +${s.aliasesAdded}",
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (s.deletesIncoming > 0) {
                    Text(
                        "Poistoja: ${s.deletesIncoming}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Text(
                "Kaikesta muusta pidetään kiinni — synkronointi ei koskaan poista tietoa, " +
                    "jota toinen laite ei tunne.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            // ── conflicts ──
            if (plan.conflicts.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Ristiriidat (${plan.conflicts.size})", fontWeight = FontWeight.Bold)
                        Text(
                            "Näitä on muokattu molemmilla laitteilla edellisen synkronoinnin " +
                                "jälkeen. Valitse kumpi jää voimaan.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        plan.conflicts.forEach { choices[it.key] = it.defaultChoice }
                    }) { Text("Uusimmat") }
                    TextButton(onClick = {
                        plan.conflicts.forEach { choices[it.key] = SyncEngine.Choice.LOCAL }
                    }) { Text("Kaikki puhelimesta") }
                    TextButton(onClick = {
                        plan.conflicts.forEach { choices[it.key] = SyncEngine.Choice.REMOTE }
                    }) { Text("Kaikki koneelta") }
                }

                plan.conflicts.forEach { c ->
                    ConflictCard(
                        conflict = c,
                        peerName = plan.peerName,
                        choice = choices[c.key] ?: c.defaultChoice,
                        onChoice = { choices[c.key] = it }
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ChangeLine(label: String, new: Int, updated: Int) {
    if (new == 0 && updated == 0) return
    val parts = buildList {
        if (new > 0) add("$new uutta")
        if (updated > 0) add("$updated päivitystä")
    }
    Text("$label: ${parts.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ConflictCard(
    conflict: SyncEngine.Conflict,
    peerName: String,
    choice: SyncEngine.Choice,
    onChoice: (SyncEngine.Choice) -> Unit
) {
    Card(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(
                conflict.kindLabel.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(conflict.label, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall)
            if (conflict.nature == "delete") {
                Text(
                    "Poistettu toisella laitteella, muokattu toisella.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Spacer(Modifier.height(10.dp))
            conflict.fields.forEach { f ->
                Text(f.label, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    Text(
                        f.local.ifBlank { "—" },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (choice == SyncEngine.Choice.LOCAL) FontWeight.Bold else FontWeight.Normal,
                        color = if (choice == SyncEngine.Choice.LOCAL) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("→", modifier = Modifier.padding(horizontal = 6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        f.remote.ifBlank { "—" },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (choice == SyncEngine.Choice.REMOTE) FontWeight.Bold else FontWeight.Normal,
                        color = if (choice == SyncEngine.Choice.REMOTE) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                FilterChip(
                    selected = choice == SyncEngine.Choice.LOCAL,
                    onClick = { onChoice(SyncEngine.Choice.LOCAL) },
                    label = {
                        Column {
                            Text("Tämä puhelin")
                            Text(stampShort(conflict.localAt), style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = choice == SyncEngine.Choice.REMOTE,
                    onClick = { onChoice(SyncEngine.Choice.REMOTE) },
                    label = {
                        Column {
                            Text(peerName, maxLines = 1)
                            Text(stampShort(conflict.remoteAt), style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  Log + snapshots
// ════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncLogView(
    state: AppState,
    snapshots: List<Snapshot>,
    onBack: () -> Unit,
    onRestoreSnapshot: (Snapshot) -> Unit
) {
    var pending by remember { mutableStateOf<Snapshot?>(null) }
    val entries = state.syncLog.reversed()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Synkronointiloki") },
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
                "Jos jokin tieto on joskus muuttunut yllättäen, vastaus löytyy täältä.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            Text("Tapahtumat (${entries.size})", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            if (entries.isEmpty()) {
                Text("Ei vielä synkronointeja.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            entries.forEach { e ->
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                e.peerName.ifBlank { e.peer },
                                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
                            )
                            Text(
                                stampShort(e.at),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            buildString {
                                val parts = buildList {
                                    if (e.productsNew + e.productsUpd > 0)
                                        add("tuotteet +${e.productsNew}/~${e.productsUpd}")
                                    if (e.ringsNew + e.ringsUpd > 0)
                                        add("piirit +${e.ringsNew}/~${e.ringsUpd}")
                                    if (e.ordersNew + e.ordersUpd > 0)
                                        add("tilaukset +${e.ordersNew}/~${e.ordersUpd}")
                                    if (e.deliveriesNew + e.deliveriesUpd > 0)
                                        add("kuljetukset +${e.deliveriesNew}/~${e.deliveriesUpd}")
                                    if (e.customersNew + e.customersUpd > 0)
                                        add("asiakkaat +${e.customersNew}/~${e.customersUpd}")
                                    if (e.distsNew + e.distsUpd > 0)
                                        add("jakelut +${e.distsNew}/~${e.distsUpd}")
                                    if (e.deletes > 0) add("poistoja ${e.deletes}")
                                    if (e.conflicts > 0) add("ristiriitoja ${e.conflicts}")
                                }
                                append(if (parts.isEmpty()) "ei muutoksia" else parts.joinToString(" · "))
                                append("  ·  ")
                                append(if (e.via == "lan") "lähiverkko" else "tiedosto")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))
            Text("Varmuuskopiot (${snapshots.size})", fontWeight = FontWeight.Bold)
            Text(
                "Otetaan automaattisesti ennen jokaista synkronointia ja tuontia. " +
                    "Uusin ${Store.MAX_SNAPSHOTS} säilytetään.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            if (snapshots.isEmpty()) {
                Text("Ei vielä varmuuskopioita.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            snapshots.forEach { snap ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stampShort(snap.at), fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium)
                        Text(
                            snap.label.ifBlank { "varmuuskopio" } + "  ·  ${snap.bytes / 1024} kt",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { pending = snap }) { Text("Palauta") }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    pending?.let { snap ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Palauta varmuuskopio") },
            text = {
                Text(
                    "Koko tila palautetaan siihen, mitä se oli ${stampShort(snap.at)}.\n\n" +
                        "Kaikki sen jälkeen tehdyt muutokset katoavat — mutta nykyisestä " +
                        "tilasta otetaan varmuuskopio ensin, joten tämänkin voi perua."
                )
            },
            confirmButton = {
                TextButton(onClick = { pending = null; onRestoreSnapshot(snap) }) { Text("Palauta") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Peruuta") } }
        )
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  LAN pairing
// ════════════════════════════════════════════════════════════════════════════

/**
 * Find the desktop on the local network and exchange states with it.
 *
 * Discovery is started when the screen appears and torn down when it leaves —
 * an mDNS listener left running keeps the wifi radio awake, so the
 * DisposableEffect here is not tidiness but battery life.
 *
 * The screen never applies anything. Its only outcome is a payload handed back
 * through [onExchanged], which the caller turns into a preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanSyncView(
    state: AppState,
    onBack: () -> Unit,
    onExchanged: (SyncPayload, LanPeer, String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val found = remember { mutableStateListOf<LanPeer>() }
    var selected by remember { mutableStateOf<LanPeer?>(null) }
    var manual by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    val discovery = remember { LanDiscovery(context) }
    DisposableEffect(Unit) {
        discovery.start(
            onFound = { peer ->
                // NsdManager can report the same service more than once.
                if (found.none { it.host == peer.host && it.port == peer.port }) found.add(peer)
            },
            onError = { msg -> error = msg }
        )
        onDispose { discovery.stop() }
    }

    fun connect(peer: LanPeer) {
        if (busy) return
        if (code.trim().length < 4) {
            error = "Syötä koneella näkyvä koodi."
            return
        }
        busy = true; error = ""; status = "Yhdistetään…"
        scope.launch {
            val theCode = code.trim()
            val peerInfo = when (val hello = withContext(Dispatchers.IO) { LanClient.hello(peer, theCode) }) {
                is LanResult.Err -> {
                    busy = false; status = ""; error = hello.message
                    return@launch
                }
                is LanResult.Ok -> hello.value
            }
            status = "Yhdistetty: ${peerInfo.deviceName}. Vaihdetaan tiedot…"

            val myPayload = SyncFiles.buildText(state, peerInfo.deviceId)
            val exchanged = withContext(Dispatchers.IO) {
                LanClient.exchange(peer, theCode, myPayload)
            }
            busy = false
            when (exchanged) {
                is LanResult.Err -> { status = ""; error = exchanged.message }
                is LanResult.Ok -> {
                    status = ""
                    onExchanged(exchanged.value, peer, theCode)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Yhdistä työpöytään") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin") }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            Text("1. Avaa yhteys koneella", fontWeight = FontWeight.Bold)
            Text(
                "Työpöytäohjelmassa: REKO → Synkronointi → Avaa yhteys. " +
                    "Koneen ruudulle ilmestyy kuusinumeroinen koodi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(18.dp))
            Text("2. Valitse kone", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            if (found.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Etsitään koneita samasta verkosta…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            found.forEach { peer ->
                val isSel = selected?.host == peer.host && selected?.port == peer.port
                Card(
                    onClick = { selected = peer },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSel) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                ) {
                    Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(peer.name, fontWeight = FontWeight.Bold)
                            Text(
                                "${peer.host}:${peer.port}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isSel) Text("✓", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Jos konetta ei löydy automaattisesti, kirjoita sen osoite käsin — " +
                    "työpöytäohjelma näyttää sen koodin alla.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    manual, { manual = it },
                    label = { Text("Osoite (esim. 192.168.1.23)") },
                    singleLine = true, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    val p = LanClient.parseManualAddress(manual)
                    if (p == null) error = "Tarkista osoite."
                    else {
                        error = ""
                        if (found.none { it.host == p.host && it.port == p.port }) found.add(p)
                        selected = p
                    }
                }) { Text("Lisää") }
            }

            Spacer(Modifier.height(18.dp))
            Text("3. Syötä koodi", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                code, { code = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("Koneella näkyvä koodi") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Koodi on voimassa viisi minuuttia ja kelpaa vain kerran. Se varmistaa, " +
                    "ettei kukaan muu samassa verkossa voi ottaa yhteyttä.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            if (error.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(error, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (status.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { selected?.let { connect(it) } },
                enabled = selected != null && code.trim().length >= 4 && !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Yhdistä ja hae muutokset") }
            Text(
                "Tiedot vain vaihdetaan — mitään ei tallenneta ennen kuin hyväksyt " +
                    "esikatselun. Kone näyttää oman esikatselunsa erikseen.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}
