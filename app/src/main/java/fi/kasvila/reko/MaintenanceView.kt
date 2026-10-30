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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Reminders and the automatic backup.
 *
 * Both are per-device: a folder chosen on this phone means nothing on the
 * desktop, and a notification schedule is a property of the phone in your
 * pocket. Neither is synced, and the screen says so, because a setting that
 * looks shared but isn't is worse than one that is obviously local.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaintenanceView(
    state: AppState,
    prefs: MaintenancePrefs,
    onBack: () -> Unit,
    onPrefs: (MaintenancePrefs) -> Unit,
    onPickFolder: () -> Unit,
    onBackupNow: () -> Unit,
    onRequestNotifPermission: () -> Unit,
    notifGranted: Boolean
) {
    val unpaidTotal = remember(state.orders, state.deliveries) { Unpaid.grandTotal(state) }
    val unpaidCount = remember(state.orders, state.deliveries) { Unpaid.list(state).size }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Muistutukset ja varmuuskopio") },
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
                "Nämä asetukset koskevat vain tätä puhelinta eivätkä siirry " +
                    "työpöytäohjelmaan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ── unpaid reminder ──
            Spacer(Modifier.height(20.dp))
            Text("Muistutus maksamattomista", fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium)
            Text(
                if (unpaidCount == 0) "Tällä hetkellä ei maksamattomia."
                else "Tällä hetkellä $unpaidCount asiakasta, " +
                    Reko.money(unpaidTotal, state.currency) + " maksamatta.",
                style = MaterialTheme.typography.bodySmall,
                color = if (unpaidCount == 0) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Viikoittainen ilmoitus")
                    Text(
                        "Ilmoitus tulee vain jos maksamattomia on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    prefs.unpaidEnabled,
                    {
                        if (it) onRequestNotifPermission()
                        onPrefs(prefs.copy(unpaidEnabled = it))
                    }
                )
            }
            if (prefs.unpaidEnabled) {
                if (!notifGranted) {
                    Text(
                        "⚠ Ilmoitukset eivät ole sallittuja — muistutus ei näy ennen kuin " +
                            "annat luvan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                val days = (1..7).map { Maintenance.weekdayLabel(it) }
                DropdownField(
                    label = "Päivä",
                    value = Maintenance.weekdayLabel(prefs.unpaidWeekday),
                    options = days,
                    editable = false,
                    onSelectIndex = { idx -> onPrefs(prefs.copy(unpaidWeekday = idx + 1)) }
                )
                Spacer(Modifier.height(10.dp))
                val hours = (6..22).map { "klo %02d:00".format(it) }
                DropdownField(
                    label = "Kellonaika",
                    value = "klo %02d:00".format(prefs.unpaidHour),
                    options = hours,
                    editable = false,
                    onSelectIndex = { idx -> onPrefs(prefs.copy(unpaidHour = idx + 6)) }
                )
            }

            // ── automatic backup ──
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("Automaattinen varmuuskopio", fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium)
            Text(
                "Kirjoittaa koko tilan päivätyksi tiedostoksi valitsemaasi kansioon joka yö. " +
                    "Jos valitset pilvipalvelun kansion (Drive, Dropbox, OneDrive), kopio on " +
                    "turvassa myös silloin kun puhelin katoaa synkronointien välissä.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (prefs.backupFolderUri.isBlank()) "Valitse kansio"
                    else "Vaihda kansio",
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (prefs.backupFolderUri.isNotBlank()) {
                Text(
                    "Kansio: " + prefs.backupFolderLabel.ifBlank { prefs.backupFolderUri },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Yöllinen varmuuskopio")
                    Text(
                        if (prefs.backupFolderUri.isBlank()) "Valitse ensin kansio."
                        else "Kirjoitetaan klo %02d:00, säilytetään %d viimeisintä."
                            .format(prefs.backupHour, prefs.backupKeep),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    prefs.backupEnabled,
                    { onPrefs(prefs.copy(backupEnabled = it)) },
                    enabled = prefs.backupFolderUri.isNotBlank()
                )
            }

            if (prefs.backupEnabled) {
                Spacer(Modifier.height(10.dp))
                val hours = (0..23).map { "klo %02d:00".format(it) }
                DropdownField(
                    label = "Kellonaika",
                    value = "klo %02d:00".format(prefs.backupHour),
                    options = hours,
                    editable = false,
                    onSelectIndex = { idx -> onPrefs(prefs.copy(backupHour = idx)) }
                )
                Spacer(Modifier.height(10.dp))
                val keeps = listOf(7, 14, 30, 60)
                DropdownField(
                    label = "Säilytettävien määrä",
                    value = "${prefs.backupKeep} kpl",
                    options = keeps.map { "$it kpl" },
                    editable = false,
                    onSelectIndex = { idx -> onPrefs(prefs.copy(backupKeep = keeps[idx])) }
                )
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onBackupNow,
                enabled = prefs.backupFolderUri.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Ota varmuuskopio nyt") }

            if (prefs.lastBackupAt.isNotBlank()) {
                Text(
                    "Viimeisin onnistunut: ${stampShort(prefs.lastBackupAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (prefs.lastBackupError.isNotBlank()) {
                Text(
                    "⚠ Viimeisin yritys epäonnistui: ${prefs.lastBackupError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Text(
                "Varmuuskopio on sama kaksisuuntainen synkronointitiedosto jonka voi tuoda " +
                    "takaisin sekä puhelimeen että työpöytäohjelmaan.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}
