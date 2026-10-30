package fi.kasvila.reko

import android.widget.Toast
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Tili ja turvallisuus" (v48): change the password, or issue a new recovery
 * code. Both re-wrap the in-memory DEK, so the encrypted data is never touched
 * and stays readable. Argon2id runs off the main thread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySettingsView(
    securityStore: SecurityStore,
    sessionStore: SessionStore,
    dek: ByteArray,
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account = remember { securityStore.load() }
    var stayLoggedIn by remember { mutableStateOf(sessionStore.stayLoggedIn()) }
    val canBiometric = remember { Biometric.canUse(context) }
    var biometricOn by remember { mutableStateOf(sessionStore.biometricRequired()) }

    var oldPw by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var newPw2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var newRecovery by remember { mutableStateOf<String?>(null) }

    fun changePassword() {
        error = null
        if (newPw.length < 6) { error = "Uuden salasanan tulee olla vähintään 6 merkkiä"; return }
        if (newPw != newPw2) { error = "Salasanat eivät täsmää"; return }
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (securityStore.unlockWithPassword(oldPw) == null) false
                else securityStore.changePassword(dek, newPw)
            }
            busy = false
            if (ok) {
                oldPw = ""; newPw = ""; newPw2 = ""
                Toast.makeText(context, "Salasana vaihdettu", Toast.LENGTH_SHORT).show()
            } else {
                error = "Nykyinen salasana on väärin"
            }
        }
    }

    fun regenerate() {
        busy = true
        scope.launch {
            val code = withContext(Dispatchers.IO) { securityStore.regenerateRecovery(dek) }
            busy = false
            if (code != null) newRecovery = code
            else Toast.makeText(context, "Palautuskoodin luonti epäonnistui", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tili ja turvallisuus") },
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
            account?.username?.takeIf { it.isNotBlank() }?.let {
                Text("Käyttäjä: $it", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
            }

            Text("Kirjautuminen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Pysy kirjautuneena")
                    Text(
                        "Sovellus avautuu suoraan ilman salasanaa, kunnes kirjaudut ulos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = stayLoggedIn,
                    onCheckedChange = {
                        stayLoggedIn = it
                        sessionStore.setStayLoggedIn(it, dek)
                        if (!it) biometricOn = false
                    }
                )
            }
            if (canBiometric) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Vaadi biometrinen tunnistus")
                        Text(
                            "Kysyy sormenjäljen tai kasvot sovellusta avattaessa.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = biometricOn,
                        onCheckedChange = {
                            biometricOn = it
                            if (it) stayLoggedIn = true
                            sessionStore.setBiometricRequired(it, dek)
                        }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { onLogout() }, modifier = Modifier.fillMaxWidth()) {
                Text("Kirjaudu ulos")
            }

            Spacer(Modifier.height(28.dp))
            HorizontalDivider()
            Spacer(Modifier.height(28.dp))

            Text("Vaihda salasana", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = oldPw, onValueChange = { oldPw = it },
                label = { Text("Nykyinen salasana") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = newPw, onValueChange = { newPw = it },
                label = { Text("Uusi salasana") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = newPw2, onValueChange = { newPw2 = it },
                label = { Text("Uusi salasana uudelleen") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { changePassword() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Vaihda salasana")
            }

            Spacer(Modifier.height(28.dp))
            HorizontalDivider()
            Spacer(Modifier.height(28.dp))

            Text("Palautuskoodi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Luo uusi palautuskoodi, jos vanha on hukkunut tai paljastunut. Vanha koodi " +
                    "lakkaa toimimasta heti.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { regenerate() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Luo uusi palautuskoodi")
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    newRecovery?.let { code ->
        RecoveryDialog(
            code = code,
            title = "Uusi palautuskoodi",
            body = "Kirjoita tämä ylös ja säilytä turvassa. Vanha palautuskoodi ei enää toimi. " +
                "Sitä ei näytetä uudelleen.",
            confirmLabel = "Olen tallentanut koodin",
            onConfirm = { newRecovery = null }
        )
    }
}
