package fi.kasvila.reko

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sales-announcement / producer-intro generator (v54). Builds a rule-based draft
 * from the app's data, offers an optional AI polish (the app's own AI settings +
 * the user's own key), and lets the producer edit and share the result. No
 * customer data is used.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AnnouncementView(
    state: AppState,
    aiConfig: AiConfig,
    intro: Boolean,
    ringId: String,
    date: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    fun buildDraft(): String =
        if (intro) AnnouncementBuilder.producerIntro(state)
        else AnnouncementBuilder.salesAnnouncement(state, ringId, date)

    var text by remember { mutableStateOf(buildDraft()) }
    var tone by remember { mutableStateOf("Rento") }
    var length by remember { mutableStateOf("Lyhyt") }
    var busy by remember { mutableStateOf(false) }

    val hasAi = aiConfig.apiKey.isNotBlank()

    fun enhance() {
        if (busy) return
        busy = true
        val (system, user) = AnnouncementBuilder.aiPrompts(text, intro, tone, length)
        scope.launch {
            val result = withContext(Dispatchers.IO) { AiClient.chat(aiConfig, system, user) }
            busy = false
            when (result) {
                is AiResult.Ok -> text = result.text.trim()
                is AiResult.Err -> Toast.makeText(
                    context, "Tekoäly ei vastannut (${result.code})", Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (intro) "Tuottajaesittely" else "REKO-ilmoitus") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        try {
                            context.startActivity(Intent.createChooser(send, "Jaa"))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Jako epäonnistui", Toast.LENGTH_SHORT).show()
                        }
                    }) { Icon(Icons.Filled.Share, "Jaa") }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            Text(
                "Muokkaa tekstiä vapaasti ja jaa se Facebookiin tai Messengeriin. " +
                    "Pohja on koottu sovelluksen tiedoista.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Teksti") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp)
            )

            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { text = buildDraft() }, modifier = Modifier.fillMaxWidth()) {
                Text("Palauta pohja")
            }

            Spacer(Modifier.height(20.dp))
            Text("Tekoälyn viimeistely (valinnainen)", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Sävy", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Rento", "Asiallinen", "Juhlava").forEach {
                    FilterChip(selected = tone == it, onClick = { tone = it }, label = { Text(it) })
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Pituus", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Lyhyt", "Keskipitkä").forEach {
                    FilterChip(selected = length == it, onClick = { length = it }, label = { Text(it) })
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = { enhance() }, enabled = hasAi && !busy, modifier = Modifier.fillMaxWidth()) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Luo tekoälyllä")
            }
            if (!hasAi) {
                Text(
                    "Ota tekoäly käyttöön Asetukset → Tekoälyasetukset (oma API-avain).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(text)) },
                    modifier = Modifier.weight(1f)
                ) { Text("Kopioi") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        try {
                            context.startActivity(Intent.createChooser(send, "Jaa"))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Jako epäonnistui", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Share, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Jaa")
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
