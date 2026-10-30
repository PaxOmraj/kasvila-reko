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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * "Käyttäjät / palvelijat" (v53). Manage the people who share this device: add
 * them with a name + PIN, set who is on shift, edit or remove them. The one on
 * shift is attributed to the receipts they issue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperatorsView(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { OperatorStore(context) }
    var refresh by remember { mutableStateOf(0) }
    val operators = remember(refresh) { store.list() }
    val currentId = remember(refresh) { store.current()?.id ?: "" }

    var newName by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Operator?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Käyttäjät (palvelijat)") },
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
            Text(
                "Saman laitteen käyttäjät. Vuorossa oleva merkitään antamiinsa kuitteihin " +
                    "(näkyy kuitissa vain jos se on kytketty päälle Kuitti ja tulostin -asetuksista).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(18.dp))

            Text("Lisää palvelija", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = newName, onValueChange = { newName = it },
                label = { Text("Nimi") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = newPin, onValueChange = { newPin = it },
                label = { Text("PIN (valinnainen)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (newName.isNotBlank()) {
                        store.add(newName, newPin)
                        newName = ""; newPin = ""; refresh++
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Lisää palvelija") }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("Palvelijat", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (operators.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Ei palvelijoita. Lisää yllä.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            operators.forEach { op ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(op.name, fontWeight = FontWeight.Bold)
                                if (op.id == currentId) {
                                    Text("Vuorossa", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                                if (!op.hasPin) {
                                    Text("Ei PIN-koodia", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth()) {
                            if (op.id != currentId) {
                                TextButton(onClick = { store.setCurrent(op.id); refresh++ }) {
                                    Text("Vuoroon")
                                }
                            }
                            TextButton(onClick = { editing = op }) { Text("Muokkaa") }
                            TextButton(onClick = { store.remove(op.id); refresh++ }) {
                                Text("Poista", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    editing?.let { op ->
        var editName by remember(op.id) { mutableStateOf(op.name) }
        var editPin by remember(op.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Muokkaa: ${op.name}") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editName, onValueChange = { editName = it },
                        label = { Text("Nimi") }, singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editPin, onValueChange = { editPin = it },
                        label = { Text("Uusi PIN (tyhjä = ennallaan)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (editName.isNotBlank()) store.rename(op.id, editName)
                    if (editPin.isNotBlank()) store.setPin(op.id, editPin)
                    editing = null; refresh++
                }) { Text("Tallenna") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Peruuta") } }
        )
    }
}
