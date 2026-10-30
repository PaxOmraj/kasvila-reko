package fi.kasvila.ostot

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Kamera kahteen käyttöön: viivakoodin luku (jatkuva) ja hintalapun teksti
 * (yksi kuva, kun [textRequest] kasvaa). Kaikki tunnistus tapahtuu puhelimessa
 * ML Kitillä ilman verkkoa.
 *
 * Viivakoodi hyväksytään vasta, kun sama koodi on luettu kahdesti peräkkäin:
 * se karsii huonossa valossa syntyvät väärät lukemat.
 */
@Composable
fun OstoCamera(
    modifier: Modifier = Modifier,
    readBarcodes: Boolean,
    textRequest: Int = 0,
    onBarcode: (String) -> Unit = {},
    onText: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> granted = ok }

    if (!granted) {
        Column(
            modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Viivakoodien ja hintalappujen lukemiseen tarvitaan kameran käyttölupa. " +
                    "Kuvia ei tallenneta eikä lähetetä minnekään.",
                textAlign = TextAlign.Center
            )
            Button(onClick = { askPermission.launch(Manifest.permission.CAMERA) }) {
                Text("Salli kamera")
            }
        }
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val barcodeCb by rememberUpdatedState(onBarcode)
    val textCb by rememberUpdatedState(onText)
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val barcodeMode = remember { AtomicBoolean(readBarcodes) }
    barcodeMode.set(readBarcodes)
    val pendingText = remember { AtomicBoolean(false) }
    val lastRequest = remember { mutableStateOf(textRequest) }
    if (textRequest != lastRequest.value) {
        lastRequest.value = textRequest
        pendingText.set(true)
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf("") }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val barcodes = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(
                Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E, Barcode.FORMAT_QR_CODE, Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_DATA_MATRIX
            ).build()
        )
        val texts = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val previous = AtomicReference<String?>(null)
        val delivered = AtomicBoolean(false)
        val main = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)

        val analyzer = ImageAnalysis.Analyzer { proxy -> analyze(proxy, barcodeMode, pendingText,
            previous, delivered, barcodes, texts,
            onCode = { code -> main.execute { problem = ""; barcodeCb(code) } },
            onRead = { text -> main.execute { textCb(text) } },
            onFail = { failed -> main.execute { problem = if (!failed) "" else "Tunnistus ei ole vielä valmis: puhelin lataa sitä Google Play -palveluista. Odota hetki verkkoyhteyden kanssa." } }) }

        providerFuture.addListener({
            val provider = runCatching { providerFuture.get() }.getOrNull() ?: return@addListener
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, analyzer) }
            runCatching {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            }
        }, main)

        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            camera = null
            executor.shutdown()
            barcodes.close()
            texts.close()
        }
    }

    Box(modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        val cam = camera
        if (cam != null && cam.cameraInfo.hasFlashUnit()) {
            FilledTonalIconButton(
                onClick = { torch = !torch; cam.cameraControl.enableTorch(torch) },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                Icon(if (torch) Icons.Filled.FlashOff else Icons.Filled.FlashOn, "Valo")
            }
        }
        if (problem.isNotBlank()) {
            Text(
                problem,
                color = MaterialTheme.colorScheme.onError,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
                    .background(MaterialTheme.colorScheme.error).padding(12.dp)
            )
        }
        if (readBarcodes) {
            Text(
                "Osoita viivakoodia",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
            )
        }
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun analyze(
    proxy: ImageProxy,
    barcodeMode: AtomicBoolean,
    pendingText: AtomicBoolean,
    previous: AtomicReference<String?>,
    delivered: AtomicBoolean,
    barcodes: com.google.mlkit.vision.barcode.BarcodeScanner,
    texts: com.google.mlkit.vision.text.TextRecognizer,
    onCode: (String) -> Unit,
    onRead: (String) -> Unit,
    onFail: (Boolean) -> Unit
) {
    val media = proxy.image
    if (media == null) {
        proxy.close()
        return
    }
    val wantText = pendingText.getAndSet(false)
    if (!wantText && (!barcodeMode.get() || delivered.get())) {
        proxy.close()
        return
    }
    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    if (wantText) {
        texts.process(image)
            .addOnSuccessListener { onFail(false); onRead(it.text) }
            .addOnFailureListener { onFail(true); onRead("") }
            .addOnCompleteListener { proxy.close() }
        return
    }
    barcodes.process(image)
        .addOnSuccessListener { list ->
            onFail(false)
            val code = list.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
            if (code != null) {
                if (code == previous.get() && delivered.compareAndSet(false, true)) onCode(code)
                previous.set(code)
            }
        }
        .addOnFailureListener { onFail(true) }
        .addOnCompleteListener { proxy.close() }
}
