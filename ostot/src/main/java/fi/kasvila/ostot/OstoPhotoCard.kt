package fi.kasvila.ostot

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v60: kuitin kuva ostokertaan. Kuvaus puhelimen omalla kamerasovelluksella
 * (tarkka kuva, automaattitarkennus) tai valmis kuva galleriasta.
 */
@Composable
internal fun ReceiptPhotoCard(
    session: OstoSession,
    enabled: Boolean,
    onChange: ((OstoSession) -> OstoSession) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val thumb = remember(session.receiptPhoto, session.receiptPhotoHash) { OstoPhoto.thumbnail(context, session) }
    val tmpFile = remember { File(context.cacheDir, "shared/kuitti_kamera.jpg") }

    fun store(uri: Uri) {
        busy = true
        message = ""
        scope.launch {
            val res = withContext(Dispatchers.IO) { OstoPhoto.save(context, uri, session.id) }
            busy = false
            if (res == null) message = "Kuvaa ei saatu luettua. Yritä uudelleen."
            else onChange { it.copy(receiptPhoto = res.first, receiptPhotoHash = res.second) }
            runCatching { tmpFile.delete() }
        }
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) store(Uri.fromFile(tmpFile))
    }
    fun launchCamera() {
        tmpFile.parentFile?.mkdirs()
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", tmpFile)
        runCatching { takePicture.launch(uri) }.onFailure { message = "Kameraa ei voitu avata." }
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) launchCamera() else message = "Kuvaamiseen tarvitaan kameran käyttölupa."
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) store(uri)
    }
    fun camera() {
        // Sovellus pyytää kameraluvan (viivakoodit), joten Android vaatii luvan myös kamerasovellukselle.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            launchCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Kuitin kuva", fontWeight = FontWeight.Bold)
            if (thumb != null) {
                Image(
                    thumb.asImageBitmap(), "Kuitin kuva",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                )
                Text(
                    "Kuva lähtee työpöydälle kerran mukana. Siellä hinnat luetaan kuvasta: " +
                        "Varasto → Puhelimesta → Lue kuittikuva.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (enabled) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { camera() }, enabled = !busy) { Text("Kuvaa uudelleen") }
                    TextButton(onClick = {
                        OstoPhoto.delete(context, session)
                        onChange { it.copy(receiptPhoto = "", receiptPhotoHash = "") }
                    }, enabled = !busy) { Text("Poista kuva") }
                }
            } else {
                Text(
                    "Kuvaa kuitti, niin työpöytä lukee siitä oikeat hinnat alennuksineen " +
                        "skannattuihin tuotteisiin. Hintoja ei silloin tarvitse kirjoittaa puhelimeen.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (enabled) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { camera() }, enabled = !busy) {
                        Icon(Icons.Filled.PhotoCamera, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Kuvaa kuitti")
                    }
                    OutlinedButton(onClick = { pickImage.launch("image/*") }, enabled = !busy) {
                        Icon(Icons.Filled.PhotoLibrary, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Galleriasta")
                    }
                }
            }
            if (busy) CircularProgressIndicator()
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
        }
    }
}
