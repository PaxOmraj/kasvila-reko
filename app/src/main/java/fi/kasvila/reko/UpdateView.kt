package fi.kasvila.reko

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Sovelluksen päivitykset" (v56). Manual + automatic update check against GitHub
 * Releases. Downloading opens the APK link in the browser; the user then taps the
 * downloaded file to install (no extra permission, and the same signing key means
 * it installs straight over the top).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSettingsView(
    currentVersionName: String,
    prefs: UpdatePrefs,
    onPrefs: (UpdatePrefs) -> Unit,
    onOpenUrl: (String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateChecker.UpdateResult?>(null) }

    fun runCheck() {
        if (busy) return
        busy = true
        result = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { UpdateChecker.check(BuildConfig.VERSION_CODE) }
            busy = false
            result = r
            onPrefs(prefs.copy(lastCheckIso = java.time.Instant.now().toString()))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sovelluksen päivitykset") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            Text("Nykyinen versio: $currentVersionName",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Päivitykset haetaan GitHubista. Kun painat Lataa, APK-tiedosto latautuu " +
                    "selaimen kautta — avaa se latauksen jälkeen asentaaksesi. Sama " +
                    "allekirjoitus takaa, että päivitys asentuu vanhan päälle datat säilyttäen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            Button(onClick = { runCheck() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Tarkista päivitykset nyt")
            }

            when (val r = result) {
                is UpdateChecker.UpdateResult.Available -> {
                    Spacer(Modifier.height(16.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Uusi versio saatavilla: ${r.versionName}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary)
                            if (r.notes.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(r.notes, style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { onOpenUrl(r.apkUrl.ifBlank { r.pageUrl }) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Lataa versio ${r.versionName}")
                            }
                        }
                    }
                }
                is UpdateChecker.UpdateResult.UpToDate -> {
                    Spacer(Modifier.height(16.dp))
                    Text("Käytössä on uusin versio.",
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                is UpdateChecker.UpdateResult.Error -> {
                    Spacer(Modifier.height(16.dp))
                    Text("Tarkistus ei onnistunut: ${r.message}",
                        color = MaterialTheme.colorScheme.error)
                }
                null -> {}
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tarkista automaattisesti", fontWeight = FontWeight.Bold)
                    Text("Ilmoittaa uudesta versiosta käynnistyksessä (korkeintaan kerran vuorokaudessa).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = prefs.autoCheck,
                    onCheckedChange = { onPrefs(prefs.copy(autoCheck = it)) }
                )
            }

            if (prefs.lastCheckIso.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text("Viimeksi tarkistettu: ${prettyInstant(prefs.lastCheckIso)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Dialog shown on launch when the automatic check finds a newer version. */
@Composable
fun UpdateDialog(
    avail: UpdateChecker.UpdateResult.Available,
    onDownload: () -> Unit,
    onSkip: () -> Unit,
    onLater: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("Uusi versio saatavilla") },
        text = {
            Column {
                Text("Versio ${avail.versionName} on ladattavissa.")
                if (avail.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(avail.notes.take(400), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onSkip, contentPadding = PaddingValues(0.dp)) {
                    Text("Ohita tämä versio")
                }
            }
        },
        confirmButton = {
            Button(onClick = onDownload) { Text("Lataa") }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text("Myöhemmin") }
        }
    )
}

/** Format an ISO-8601 instant as a short local date-time; falls back to raw text. */
private fun prettyInstant(iso: String): String = try {
    val dt = java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault())
    "%02d.%02d.%d klo %02d.%02d".format(
        dt.dayOfMonth, dt.monthValue, dt.year, dt.hour, dt.minute
    )
} catch (e: Exception) {
    iso
}
