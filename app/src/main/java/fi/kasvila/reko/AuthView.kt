package fi.kasvila.reko

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The login gate (v48). Shown before the app's data is loaded: no state is read
 * or decrypted until the DEK is in hand. First launch creates the account; after
 * that it asks for the password (with a recovery-code path for a forgotten one).
 * Argon2id runs off the main thread so the UI never blocks.
 */
@Composable
fun AuthGate(securityStore: SecurityStore, onUnlocked: (ByteArray) -> Unit) {
    val hasAccount = remember { securityStore.hasAccount() }
    if (hasAccount) LoginView(securityStore, onUnlocked)
    else SetupView(securityStore, onUnlocked)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupView(securityStore: SecurityStore, onUnlocked: (ByteArray) -> Unit) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<SecurityStore.SetupResult?>(null) }

    fun create() {
        error = null
        if (username.isBlank()) { error = "Anna käyttäjätunnus"; return }
        if (password.length < 6) { error = "Salasanan tulee olla vähintään 6 merkkiä"; return }
        if (password != confirm) { error = "Salasanat eivät täsmää"; return }
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { securityStore.setup(username, password) }
            busy = false
            result = r
        }
    }

    GateScaffold(title = "Tervetuloa") {
        Text(
            "Luo käyttäjätunnus ja salasana. Salasana suojaa sovelluksen tiedot tässä " +
                "puhelimessa: tiedot salataan, eikä niitä voi avata ilman salasanaa.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = username, onValueChange = { username = it },
            label = { Text("Käyttäjätunnus") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Salasana") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = confirm, onValueChange = { confirm = it },
            label = { Text("Salasana uudelleen") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(18.dp))
        Button(onClick = { create() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("Luo tili")
        }
    }

    result?.let { r ->
        RecoveryDialog(
            code = r.recoveryCode,
            title = "Tallenna palautuskoodi",
            body = "Tämä on ainoa tapa avata tietosi, jos unohdat salasanan. Kirjoita se " +
                "ylös ja säilytä turvassa. Sitä ei näytetä uudelleen, ja jos menetät sekä " +
                "salasanan että tämän koodin, tietoja ei voi enää avata.",
            confirmLabel = "Olen tallentanut koodin",
            onConfirm = { onUnlocked(r.dek) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginView(securityStore: SecurityStore, onUnlocked: (ByteArray) -> Unit) {
    val scope = rememberCoroutineScope()
    val account = remember { securityStore.load() }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recoveryMode by remember { mutableStateOf(false) }
    var recoveryCode by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var newConfirm by remember { mutableStateOf("") }

    fun login() {
        error = null
        busy = true
        scope.launch {
            val dek = withContext(Dispatchers.IO) { securityStore.unlockWithPassword(password) }
            busy = false
            if (dek != null) onUnlocked(dek) else error = "Väärä salasana"
        }
    }

    fun reset() {
        error = null
        if (recoveryCode.isBlank()) { error = "Anna palautuskoodi"; return }
        if (newPassword.length < 6) { error = "Uuden salasanan tulee olla vähintään 6 merkkiä"; return }
        if (newPassword != newConfirm) { error = "Salasanat eivät täsmää"; return }
        busy = true
        scope.launch {
            val dek = withContext(Dispatchers.IO) { securityStore.unlockWithRecovery(recoveryCode) }
            if (dek == null) { busy = false; error = "Väärä palautuskoodi"; return@launch }
            withContext(Dispatchers.IO) { securityStore.changePassword(dek, newPassword) }
            busy = false
            onUnlocked(dek)
        }
    }

    GateScaffold(title = "Kirjaudu sisään") {
        Text(
            "Tervetuloa takaisin${account?.username?.let { ", $it" } ?: ""}.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        if (!recoveryMode) {
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Salasana") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(18.dp))
            Button(onClick = { login() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Kirjaudu")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { recoveryMode = true; error = null }, modifier = Modifier.fillMaxWidth()) {
                Text("Unohtuiko salasana?")
            }
        } else {
            Text(
                "Syötä palautuskoodi ja valitse uusi salasana.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = recoveryCode, onValueChange = { recoveryCode = it },
                label = { Text("Palautuskoodi") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = newPassword, onValueChange = { newPassword = it },
                label = { Text("Uusi salasana") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = newConfirm, onValueChange = { newConfirm = it },
                label = { Text("Uusi salasana uudelleen") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(18.dp))
            Button(onClick = { reset() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Palauta ja kirjaudu")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { recoveryMode = false; error = null }, modifier = Modifier.fillMaxWidth()) {
                Text("Takaisin")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GateScaffold(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center
        ) {
            Text("Kasvila REKO", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(20.dp))
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A one-time display of a recovery code, with a copy button. */
@Composable
fun RecoveryDialog(
    code: String,
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = { /* must acknowledge */ },
        title = { Text(title) },
        text = {
            Column {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        code,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                    Text("Kopioi leikepöydälle")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        }
    )
}

/**
 * Biometric unlock gate (v52). Shown at launch when biometric is required and a
 * session exists. On a good scan the stored DEK is released; the user can always
 * fall back to the password.
 */
@Composable
fun BiometricGate(
    sessionStore: SessionStore,
    onUnlock: (ByteArray) -> Unit,
    onUsePassword: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var message by remember { mutableStateOf<String?>(null) }

    fun launch() {
        if (activity == null) { onUsePassword(); return }
        Biometric.authenticate(
            activity,
            onSuccess = {
                val dek = sessionStore.restore()
                if (dek != null) onUnlock(dek)
                else message = "Avaus epäonnistui. Käytä salasanaa."
            },
            onFail = { msg -> message = msg }
        )
    }

    LaunchedEffect(Unit) { launch() }

    GateScaffold(title = "Avaa sovellus") {
        Text(
            "Tunnistaudu sormenjäljellä tai kasvoilla jatkaaksesi.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(18.dp))
        Button(onClick = { launch() }, modifier = Modifier.fillMaxWidth()) {
            Text("Avaa biometrisesti")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onUsePassword, modifier = Modifier.fillMaxWidth()) {
            Text("Käytä salasanaa")
        }
    }
}
