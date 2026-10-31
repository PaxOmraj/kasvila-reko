package fi.kasvila.reko

import android.os.Bundle
import android.Manifest
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.fragment.app.FragmentActivity
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import fi.kasvila.ostot.OstotApp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import fi.kasvila.reko.ui.KasvilaTheme
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {
    // Holds a delivery id to open when launched from a reminder notification.
    private val openDeliveryId = mutableStateOf<String?>(null)

    // v47: text shared into the app from Facebook, Messenger, SMS, anywhere.
    private val sharedText = mutableStateOf<String?>(null)

    // v44: tapped the weekly "unpaid orders" notification.
    private val openUnpaid = mutableStateOf(false)

    /**
     * Pull order text out of a share / process-text intent.
     *
     * ACTION_SEND is the share sheet ("Jaa → Kasvila REKO"); ACTION_PROCESS_TEXT
     * is the text-selection menu that some keyboards and browsers offer. Both
     * hand over plain text, and both are the same thing from here: a customer's
     * order that should not have to be retyped.
     */
    private fun extractSharedText(i: Intent?): String? {
        if (i == null) return null
        val t = when (i.action) {
            Intent.ACTION_SEND ->
                if (i.type?.startsWith("text/") == true) i.getStringExtra(Intent.EXTRA_TEXT) else null
            Intent.ACTION_PROCESS_TEXT ->
                i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
        return t?.trim()?.takeIf { it.isNotBlank() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Reminders.ensureChannel(this)
        openDeliveryId.value = intent?.getStringExtra("open_delivery_id")
        sharedText.value = extractSharedText(intent)
        openUnpaid.value = intent?.getBooleanExtra("open_unpaid", false) == true
        setContent {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val uiStore = remember { UiPrefsStore(ctx) }
            var uiPrefs by remember { mutableStateOf(uiStore.load()) }
            KasvilaTheme(uiPrefs) {
                App(
                    openDeliveryId,
                    sharedText = sharedText,
                    openUnpaid = openUnpaid,
                    uiPrefs = uiPrefs,
                    onUiPrefs = { p -> uiPrefs = p; uiStore.save(p) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openDeliveryId.value = intent.getStringExtra("open_delivery_id")
        sharedText.value = extractSharedText(intent)
        openUnpaid.value = intent.getBooleanExtra("open_unpaid", false)
    }
}

private fun trimNum(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

/** v57: true if the device currently has a network with internet capability.
 *  Used to skip the automatic update check entirely when offline, so it doesn't
 *  spend a wake-up on a request that would only fail. */
private fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** Open a URL (e.g. the GitHub APK download) in the browser. Used by the v56
 *  update check; downloading the APK this way needs no extra permission. */
private fun openUrl(context: Context, url: String) {
    if (url.isBlank()) return
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        Toast.makeText(context, "Linkkiä ei voitu avata", Toast.LENGTH_SHORT).show()
    }
}

/** Share an exported JSON payload to another app (Quick Share, Bluetooth, email,
 *  Drive …) through the system share sheet. Writes the file to cacheDir/shared and
 *  hands out a FileProvider content:// uri, so no storage permission is needed and
 *  the same file goes to another phone or to the desktop. */
private fun shareJson(context: Context, filename: String, content: String, title: String) {
    try {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val f = File(dir, filename)
        f.writeText(content)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title))
    } catch (e: Exception) {
        Toast.makeText(context, "Jako ep\u00e4onnistui: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

// ── navigation ──────────────────────────────────────────────────────────────
private sealed interface Screen
private data object HomeScreen : Screen
private data class RingScreen(val ringId: String) : Screen
private data class EditScreen(
    val order: Order?, val ringId: String, val date: String,
    /** v47: text shared in from another app, pre-loaded into the paste field. */
    val prefillText: String = ""
) : Screen
private data class SharedTextScreen(val text: String) : Screen
private data object SettingsHubScreen : Screen
private data object AiSettingsScreen : Screen
private data object LocalAliasesScreen : Screen
private data class DaySummaryScreen(val ringId: String, val date: String) : Screen
private data class PickupEditScreen(val ringId: String, val date: String) : Screen
private data class WeekSummaryScreen(val monday: String) : Screen
private data class DeliverySummaryScreen(val date: String) : Screen
private data object KuljetusScreen : Screen
private data class DeliveryEditScreen(val delivery: Delivery?) : Screen

// v42+: "Tiedot ja synkronointi"
private data object DataSyncHubScreen : Screen
private data object ProductsScreen : Screen
private data class ProductEditScreen(val productId: String?) : Screen
private data object RingsManageScreen : Screen
private data class RingEditScreen(val ringId: String?) : Screen
private data object CustomersScreen : Screen
private data class CustomerEditScreen(val customerId: String?) : Screen
private data object UnpaidScreen : Screen
private data object SellerInfoScreen : Screen
private data object ReceiptSettingsScreen : Screen
private data object ReceiptLogScreen : Screen
private data object SecuritySettingsScreen : Screen
private data object OperatorsScreen : Screen
private data object UpdateSettingsScreen : Screen
private data class ReceiptScreen(
    val orderIds: List<String>,
    val deliveryIds: List<String>,
    val returnTo: Screen = UnpaidScreen
) : Screen
private data object MaintenanceScreen : Screen
private data object TrashScreen : Screen
private data object SyncScreen : Screen
private data object SyncPreviewScreen : Screen
private data object SyncLogScreen : Screen
private data object LanSyncScreen : Screen
private data object RoutesScreen : Screen
private data object RekoSummaryScreen : Screen
// v58: Ostot ja varasto (oma moduuli ostot/).
private data object OstotScreen : Screen
// v54: REKO-ilmoituksen / tuottajaesittelyn tekstigeneraattori.
private data class AnnouncementScreen(
    val ringId: String,
    val date: String,
    val intro: Boolean,
    val returnTo: Screen
) : Screen

@Composable
fun App(
    openDeliveryId: MutableState<String?> = remember { mutableStateOf<String?>(null) },
    sharedText: MutableState<String?> = remember { mutableStateOf<String?>(null) },
    openUnpaid: MutableState<Boolean> = remember { mutableStateOf(false) },
    uiPrefs: UiPrefs = UiPrefs(),
    onUiPrefs: (UiPrefs) -> Unit = {}
) {
    val context = LocalContext.current

    // ── login gate (v48) + stay logged in (v50) ──────────────────────────────
    val securityStore = remember { SecurityStore(context) }
    val sessionStore = remember { SessionStore(context) }
    val gateScope = rememberCoroutineScope()
    var unlockedDek by remember { mutableStateOf<ByteArray?>(null) }
    var restoreTried by remember { mutableStateOf(false) }
    var usePassword by remember { mutableStateOf(false) }
    val session = remember { sessionStore.load() }
    val biometricGate = session.dekWrap.isNotBlank() && session.biometricRequired

    LaunchedEffect(Unit) {
        // Silent auto-unlock only when a session exists and biometric is NOT required.
        if (securityStore.hasAccount() && session.dekWrap.isNotBlank() && !session.biometricRequired) {
            val restored = withContext(Dispatchers.IO) { sessionStore.restore() }
            if (restored != null) unlockedDek = restored
        }
        restoreTried = true
    }

    val dek = unlockedDek
    if (dek == null) {
        when {
            !restoreTried -> {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            biometricGate && !usePassword -> {
                BiometricGate(
                    sessionStore = sessionStore,
                    onUnlock = { d -> unlockedDek = d },
                    onUsePassword = { usePassword = true }
                )
            }
            else -> {
                AuthGate(
                    securityStore = securityStore,
                    onUnlocked = { d ->
                        unlockedDek = d
                        gateScope.launch { withContext(Dispatchers.IO) { sessionStore.persist(d) } }
                    }
                )
            }
        }
        return
    }

    val store = remember { Store(context, dek) }
    var state by remember { mutableStateOf(store.load()) }
    // v57: write to disk OFF the main thread, and coalesce bursts. The UI state
    // updates instantly; the (encrypting) disk write runs on Dispatchers.IO. The
    // channel is CONFLATED, so when edits arrive faster than they can be written
    // only the newest full snapshot is saved — which is exactly right, since each
    // AppState is a complete snapshot, not a delta. Fewer disk writes, no UI jank.
    val saveChannel = remember { Channel<AppState>(Channel.CONFLATED) }
    LaunchedEffect(saveChannel) {
        for (s in saveChannel) {
            withContext(Dispatchers.IO) { store.save(s) }
        }
    }
    fun persist(s: AppState) {
        state = s
        saveChannel.trySend(s)
    }

    val aiStore = remember { AiStore(context) }
    var aiConfig by remember { mutableStateOf(aiStore.load()) }
    fun persistAi(c: AiConfig) { aiConfig = c; aiStore.save(c) }

    val receiptPrefsStore = remember { ReceiptPrefsStore(context) }
    var receiptPrefs by remember { mutableStateOf(receiptPrefsStore.load()) }
    fun persistReceiptPrefs(p: ReceiptPrefs) { receiptPrefs = p; receiptPrefsStore.save(p) }

    // v56: GitHub update check. Device-local prefs; a newer release than this
    // build pops a dialog on launch (throttled to once a day, respects "skip").
    val updateStore = remember { UpdatePrefsStore(context) }
    var updatePrefs by remember { mutableStateOf(updateStore.load()) }
    fun persistUpdatePrefs(p: UpdatePrefs) { updatePrefs = p; updateStore.save(p) }
    var updateAvailable by remember { mutableStateOf<UpdateChecker.UpdateResult.Available?>(null) }
    LaunchedEffect(Unit) {
        if (!updatePrefs.autoCheck) return@LaunchedEffect
        val now = java.time.Instant.now()
        val due = updatePrefs.lastCheckIso.isBlank() || runCatching {
            java.time.Duration.between(
                java.time.Instant.parse(updatePrefs.lastCheckIso), now
            ).toHours() >= 24
        }.getOrDefault(true)
        if (!due) return@LaunchedEffect
        // v57: only reach out when actually online. If offline we don't mark the
        // check as done, so it retries next launch once there's a connection.
        if (!isOnline(context)) return@LaunchedEffect
        val r = withContext(Dispatchers.IO) { UpdateChecker.check(BuildConfig.VERSION_CODE) }
        persistUpdatePrefs(updatePrefs.copy(lastCheckIso = now.toString()))
        if (r is UpdateChecker.UpdateResult.Available && r.versionCode != updatePrefs.skipVersion) {
            updateAvailable = r
        }
    }

    var screen by remember { mutableStateOf<Screen>(HomeScreen) }

    // ── v42 sync state ──────────────────────────────────────────────────────
    // Nothing here writes. A picked file or a LAN exchange only ever produces a
    // MergePlan, which the preview screen renders; the state changes exactly once,
    // when the user presses "Sovella muutokset". That is what makes a wrong file
    // something you decline rather than something you discover afterwards.
    var pendingPlan by remember { mutableStateOf<SyncEngine.MergePlan?>(null) }
    var snapshots by remember { mutableStateOf(store.snapshots()) }
    // Set while a LAN exchange is in flight, so applying can tell the desktop we
    // took our side. Best-effort only — a failed commit costs nothing but a few
    // already-applied changes being re-offered next time.
    var lanSession by remember { mutableStateOf<Pair<LanPeer, String>?>(null) }
    val syncScope = rememberCoroutineScope()
    fun refreshSnapshots() { snapshots = store.snapshots() }

    // v44: per-device reminders and automatic backup.
    val maintStore = remember { MaintenanceStore(context) }
    var maintPrefs by remember { mutableStateOf(maintStore.load()) }
    fun persistMaint(p: MaintenancePrefs) {
        maintPrefs = p
        maintStore.save(p)
        Maintenance.apply(context, p)
    }
    // Re-apply on every launch: WorkManager schedules survive reboots, but a
    // schedule the user changed on another install, or one cancelled by the
    // system, should quietly come back.
    LaunchedEffect(Unit) {
        Maintenance.ensureChannel(context)
        Maintenance.apply(context, maintPrefs)
    }

    /** Pick the backup folder and keep access to it across reboots. */
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            // Without this the permission dies with the process and the nightly
            // backup fails silently from the second night onwards.
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            Toast.makeText(context, "Kansion k\u00e4ytt\u00f6oikeutta ei saatu", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        val label = try {
            Uri.decode(uri.lastPathSegment ?: "").substringAfterLast(':').ifBlank { uri.toString() }
        } catch (e: Exception) {
            uri.toString()
        }
        persistMaint(maintPrefs.copy(backupFolderUri = uri.toString(), backupFolderLabel = label,
            lastBackupError = ""))
        Toast.makeText(context, "Kansio valittu: $label", Toast.LENGTH_SHORT).show()
    }

    /**
     * Persist a catalog edit. Callers stamp the record itself (see
     * [SyncEngine.stamp]); this additionally clears tombstones old enough that
     * the other device has certainly seen them, so the trash does not grow
     * forever.
     */
    fun persistStamped(s: AppState) {
        persist(store.purgeTombstones(s))
    }

    /**
     * Turn any accepted file into a merge plan. One path for all formats: the v2
     * two-way file, and the v1 catalog / bundle files adapted into the same shape,
     * so a legacy import can no longer bypass the merge rules and replace the
     * catalog wholesale the way v41 did.
     */
    fun planFromText(text: String, via: String): Boolean {
        when (val parsed = SyncFiles.parse(text)) {
            is ParsedFile.Sync -> {
                pendingPlan = SyncEngine.plan(state, parsed.payload, via)
            }
            is ParsedFile.LegacyRings -> {
                val peer = SyncFiles.legacyPeerId("desktop")
                pendingPlan = SyncEngine.plan(
                    state, SyncFiles.fromLegacyRings(parsed.payload, peer), via
                )
            }
            is ParsedFile.LegacyBundle -> {
                val peer = SyncFiles.legacyPeerId("phone")
                pendingPlan = SyncEngine.plan(
                    state, SyncFiles.fromLegacyBundle(parsed.payload, peer), via
                )
            }
            is ParsedFile.Unsupported -> {
                Toast.makeText(context, parsed.reason, Toast.LENGTH_LONG).show()
                return false
            }
        }
        return true
    }

    /** Apply a previewed plan: snapshot first, then merge, then log. */
    fun applyPlan(plan: SyncEngine.MergePlan, choices: Map<String, SyncEngine.Choice>) {
        val snap = store.snapshot("ennen synkronointia")
        val (merged, entry) = SyncEngine.applyPlan(state, plan, choices, snap)
        persist(store.purgeTombstones(merged))
        refreshSnapshots()
        pendingPlan = null
        lanSession?.let { (peer, code) ->
            lanSession = null
            syncScope.launch { withContext(Dispatchers.IO) { LanClient.commit(peer, code) } }
        }
        val changes = entry.ringsNew + entry.ringsUpd + entry.productsNew + entry.productsUpd +
            entry.ordersNew + entry.ordersUpd + entry.deliveriesNew + entry.deliveriesUpd
        Toast.makeText(
            context,
            if (changes == 0) "Synkronoitu \u2014 ei muutoksia"
            else ("Synkronoitu: $changes muutosta" +
                (if (entry.conflicts > 0) ", ${entry.conflicts} ristiriitaa ratkaistu" else "")),
            Toast.LENGTH_LONG
        ).show()
    }

    /** Undo the most recent sync by restoring the snapshot taken just before it. */
    fun undoLastSync() {
        val last = state.syncLog.lastOrNull()
        if (last == null || last.snapshot.isBlank()) {
            Toast.makeText(context, "Ei perutavaa synkronointia", Toast.LENGTH_LONG).show()
            return
        }
        val restored = store.restoreSnapshot(last.snapshot)
        if (restored == null) {
            Toast.makeText(context, "Varmuuskopiota ei l\u00f6ytynyt", Toast.LENGTH_LONG).show()
            return
        }
        state = restored
        refreshSnapshots()
        Toast.makeText(context, "Synkronointi peruttu", Toast.LENGTH_LONG).show()
    }

    // Notification permission (Android 13+). Fire-and-forget: the worker re-checks
    // permission when it actually posts, so we only need to prompt here.
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !Reminders.hasNotifPermission(context)) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Re-sync all delivery reminders on startup (idempotent; safe after import/reboot).
    LaunchedEffect(Unit) { Reminders.syncAll(context, state.liveDeliveries) }

    // v47: text shared in from another app. Consumed once, so rotating the
    // phone or coming back from the background does not re-open the importer.
    LaunchedEffect(sharedText.value) {
        val t = sharedText.value ?: return@LaunchedEffect
        sharedText.value = null
        screen = SharedTextScreen(t)
    }

    // Tapped the weekly unpaid notification.
    LaunchedEffect(openUnpaid.value) {
        if (!openUnpaid.value) return@LaunchedEffect
        openUnpaid.value = false
        screen = UnpaidScreen
    }

    // If launched from a reminder notification, jump to that delivery.
    LaunchedEffect(openDeliveryId.value) {
        val id = openDeliveryId.value ?: return@LaunchedEffect
        val d = state.deliveries.firstOrNull { it.id == id }
        if (d != null) screen = DeliveryEditScreen(d)
        openDeliveryId.value = null   // consume so it doesn't re-fire
    }

    /**
     * Import ANY Kasvila REKO file. Accepts the v2 two-way sync file and every v1
     * format; the format is detected inside [planFromText], and in all cases the
     * result is a preview rather than a change.
     */
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            Toast.makeText(context, "Tiedostoa ei voitu lukea", Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        if (planFromText(text, "file")) screen = SyncPreviewScreen
    }

    /** Write this phone's full two-way sync file to a location the user picks. */
    val syncExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val peer = state.peers.keys.firstOrNull() ?: SyncFiles.legacyPeerId("desktop")
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(SyncFiles.buildText(state, peer).toByteArray())
            }
        }.isSuccess
        Toast.makeText(
            context,
            if (ok) "Synkronointitiedosto tallennettu" else "Tallennus ep\u00e4onnistui",
            Toast.LENGTH_LONG
        ).show()
    }

    /** Legacy one-way export, kept for a desktop that has not been updated yet. */
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(SyncJson.buildOrders(state.liveOrders).toByteArray())
            }
        }
        Toast.makeText(context, "Vietiin ${state.liveOrders.size} tilausta", Toast.LENGTH_LONG).show()
    }

    when (val s = screen) {
        is HomeScreen -> HomeView(
            state = state,
            onOpenRing = { ringId -> screen = RingScreen(ringId) },
            onImport = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
            onExport = {
                if (state.liveOrders.isEmpty())
                    Toast.makeText(context, "Ei tilauksia viet\u00e4v\u00e4ksi", Toast.LENGTH_SHORT).show()
                else exportLauncher.launch("kasvila_reko_tilaukset.json")
            },
            onSettings = { screen = SettingsHubScreen },
            onRekoSummary = { screen = RekoSummaryScreen },
            onUnpaid = { screen = UnpaidScreen },
            onWeekSummary = { screen = WeekSummaryScreen(Summaries.mondayOf(LocalDate.now()).toString()) },
            onDeliverySummary = { screen = DeliverySummaryScreen(LocalDate.now().toString()) },
            onKuljetus = { screen = KuljetusScreen },
            onOstot = { screen = OstotScreen }
        )

        is OstotScreen -> {
            // OstotApp hoitaa oman takaisin-näppäimensä; etusivulta poistutaan onExit-kutsulla.
            OstotApp(
                deviceId = state.deviceId,
                deviceName = state.deviceName,
                onExit = { screen = HomeScreen }
            )
        }

        is RingScreen -> {
            BackHandler { screen = HomeScreen }
            RingView(
                state = state,
                ringId = s.ringId,
                onBack = { screen = HomeScreen },
                onAdd = { date -> screen = EditScreen(null, s.ringId, date) },
                onEdit = { o -> screen = EditScreen(o, o.group_id, o.date) },
                onDaySummary = { rid, dt -> screen = DaySummaryScreen(rid, dt) },
                onEditPickup = { rid, dt -> screen = PickupEditScreen(rid, dt) }
            )
        }

        is EditScreen -> {
            BackHandler { screen = RingScreen(s.ringId) }
            OrderEditView(
                state = state,
                aiConfig = aiConfig,
                initial = s.order,
                ringId = s.ringId,
                date = s.date,
                initialPaste = s.prefillText,
                onBack = { screen = RingScreen(s.ringId) },
                onDelete = { o ->
                    // Tombstone, not removal — see AppState.liveOrders for why.
                    val gone = SyncEngine.stamp(
                        o.copy(deleted = true, deleted_at = Reko.nowIso()), state.deviceId
                    )
                    persist(state.copy(orders = state.orders.map { if (it.id == gone.id) gone else it }))
                    screen = RingScreen(s.ringId)
                },
                onSave = { saved ->
                    val stamped = SyncEngine.stamp(saved, state.deviceId)
                    persist(state.copy(orders = state.orders.filter { it.id != stamped.id } + stamped))
                    screen = RingScreen(s.ringId)
                },
                onReceipt = { o ->
                    screen = ReceiptScreen(listOf(o.id), emptyList(), RingScreen(s.ringId))
                },
                onLearnAlias = { pid, alias ->
                    val cur = state.localAliases[pid] ?: emptyList()
                    persist(state.copy(localAliases = state.localAliases + (pid to (cur + alias).distinct())))
                }
            )
        }

        is SharedTextScreen -> {
            BackHandler { screen = HomeScreen }
            SharedTextView(
                state = state,
                text = s.text,
                onBack = { screen = HomeScreen },
                onContinue = { ringId, date ->
                    screen = EditScreen(null, ringId, date, s.text)
                },
                onKuljetus = { screen = KuljetusScreen }
            )
        }

        is SettingsHubScreen -> {
            BackHandler { screen = HomeScreen }
            SettingsView(
                state = state,
                prefs = uiPrefs,
                onPrefs = onUiPrefs,
                onBack = { screen = HomeScreen },
                onOpenSeller = { screen = SellerInfoScreen },
                onOpenPrinter = { screen = ReceiptSettingsScreen },
                onOpenReceiptLog = { screen = ReceiptLogScreen },
                onOpenOperators = { screen = OperatorsScreen },
                onOpenSecurity = { screen = SecuritySettingsScreen },
                onOpenIntro = {
                    screen = AnnouncementScreen(
                        "", "", intro = true, returnTo = SettingsHubScreen
                    )
                },
                onOpenUpdates = { screen = UpdateSettingsScreen },
                onOpenAi = { screen = AiSettingsScreen },
                onOpenData = { screen = DataSyncHubScreen }
            )
        }

        is SellerInfoScreen -> {
            BackHandler { screen = SettingsHubScreen }
            SellerInfoView(
                seller = state.sellerInfo,
                onBack = { screen = SettingsHubScreen },
                onSave = { si ->
                    persist(state.copy(sellerInfo = si))
                    screen = SettingsHubScreen
                    Toast.makeText(context, "Myyjän tiedot tallennettu", Toast.LENGTH_SHORT).show()
                }
            )
        }

        is ReceiptSettingsScreen -> {
            BackHandler { screen = SettingsHubScreen }
            ReceiptSettingsView(
                prefs = receiptPrefs,
                onBack = { screen = SettingsHubScreen },
                onSave = { p ->
                    persistReceiptPrefs(p)
                    screen = SettingsHubScreen
                    Toast.makeText(context, "Tulostinasetukset tallennettu", Toast.LENGTH_SHORT).show()
                },
                onTestPrint = { p ->
                    syncScope.launch {
                        val err = withContext(Dispatchers.IO) { ThermalPrinter.printTest(context, p) }
                        Toast.makeText(
                            context, err ?: "Testitulostus lähetetty", Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        }

        is ReceiptLogScreen -> {
            BackHandler { screen = SettingsHubScreen }
            ReceiptLogView(
                state = state,
                prefs = receiptPrefs,
                onBack = { screen = SettingsHubScreen },
                onSetNextNumber = { n ->
                    if (n > state.receiptCounter) persist(state.copy(receiptCounter = n - 1))
                }
            )
        }

        is SecuritySettingsScreen -> {
            BackHandler { screen = SettingsHubScreen }
            SecuritySettingsView(
                securityStore = securityStore,
                sessionStore = sessionStore,
                dek = dek,
                onBack = { screen = SettingsHubScreen },
                onLogout = {
                    sessionStore.clear()
                    unlockedDek = null
                    screen = HomeScreen
                }
            )
        }

        is OperatorsScreen -> {
            BackHandler { screen = SettingsHubScreen }
            OperatorsView(onBack = { screen = SettingsHubScreen })
        }

        is UpdateSettingsScreen -> {
            BackHandler { screen = SettingsHubScreen }
            UpdateSettingsView(
                currentVersionName = BuildConfig.VERSION_NAME,
                prefs = updatePrefs,
                onPrefs = { p -> persistUpdatePrefs(p) },
                onOpenUrl = { url -> openUrl(context, url) },
                onBack = { screen = SettingsHubScreen }
            )
        }

        is AiSettingsScreen -> {
            BackHandler { screen = SettingsHubScreen }
            AiSettingsView(
                config = aiConfig,
                state = state,
                onRequestNotifPermission = { requestNotifPermission() },
                onBack = { screen = SettingsHubScreen },
                onSave = { c -> persistAi(c); screen = SettingsHubScreen },
                onPersist = { c -> persistAi(c) },
                onOpenAliases = { screen = LocalAliasesScreen }
            )
        }

        is LocalAliasesScreen -> {
            BackHandler { screen = AiSettingsScreen }
            LocalAliasesView(
                state = state,
                onBack = { screen = AiSettingsScreen },
                onAddAlias = { pid, alias ->
                    val cur = state.localAliases[pid] ?: emptyList()
                    persist(state.copy(localAliases = state.localAliases + (pid to (cur + alias).distinct())))
                },
                onDelete = { pid, alias ->
                    val cur = (state.localAliases[pid] ?: emptyList()).filter { it != alias }
                    val map = state.localAliases.toMutableMap()
                    if (cur.isEmpty()) map.remove(pid) else map[pid] = cur
                    persist(state.copy(localAliases = map))
                },
                onClearProduct = { pid ->
                    val map = state.localAliases.toMutableMap()
                    map.remove(pid)
                    persist(state.copy(localAliases = map))
                }
            )
        }

        is PickupEditScreen -> {
            BackHandler { screen = RingScreen(s.ringId) }
            PickupEditView(
                state = state,
                ringId = s.ringId,
                date = s.date,
                onBack = { screen = RingScreen(s.ringId) },
                onSave = { d ->
                    val stamped = SyncEngine.stamp(d, state.deviceId)
                    persistStamped(
                        state.copy(
                            distributions = state.distributions.filter { it.id != stamped.id } + stamped
                        )
                    )
                    screen = RingScreen(s.ringId)
                },
                onClear = { d ->
                    // Nothing special about the day any more. Tombstone rather
                    // than remove, or the peer would send the exception back.
                    val cleared = SyncEngine.stamp(
                        d.copy(
                            cancelled = false, time = "", address = "",
                            status = "planned", notes = "",
                            deleted = true, deleted_at = Reko.nowIso()
                        ),
                        state.deviceId
                    )
                    persistStamped(
                        state.copy(
                            distributions = state.distributions.map {
                                if (it.id == cleared.id) cleared else it
                            }
                        )
                    )
                    screen = RingScreen(s.ringId)
                }
            )
        }

        is DaySummaryScreen -> {
            BackHandler { screen = RingScreen(s.ringId) }
            DaySummaryView(
                state = state, ringId = s.ringId, date = s.date,
                onBack = { screen = RingScreen(s.ringId) },
                onReceipt = { o ->
                    screen = ReceiptScreen(
                        listOf(o.id), emptyList(), DaySummaryScreen(s.ringId, s.date)
                    )
                },
                onAnnounce = {
                    screen = AnnouncementScreen(
                        s.ringId, s.date, intro = false,
                        returnTo = DaySummaryScreen(s.ringId, s.date)
                    )
                }
            )
        }

        is WeekSummaryScreen -> {
            BackHandler { screen = HomeScreen }
            WeekSummaryView(
                state = state,
                monday = try { LocalDate.parse(s.monday) } catch (e: Exception) { Summaries.mondayOf(LocalDate.now()) },
                onBack = { screen = HomeScreen },
                onWeek = { m -> screen = WeekSummaryScreen(m.toString()) },
                onDay = { rid, dt -> screen = DaySummaryScreen(rid, dt) }
            )
        }

        is DeliverySummaryScreen -> {
            BackHandler { screen = HomeScreen }
            DeliverySummaryView(
                state = state,
                date = s.date,
                onBack = { screen = HomeScreen },
                onDay = { dt -> screen = DeliverySummaryScreen(dt) },
                onUpdateOrder = { o ->
                    val stamped = SyncEngine.stamp(o, state.deviceId)
                    persist(state.copy(orders = state.orders.map { if (it.id == stamped.id) stamped else it }))
                },
                onDeleteDay = {
                    val now = Reko.nowIso()
                    persist(state.copy(orders = state.orders.map {
                        if (it.date == s.date && !it.deleted)
                            SyncEngine.stamp(it.copy(deleted = true, deleted_at = now), state.deviceId)
                        else it
                    }))
                    screen = HomeScreen
                }
            )
        }

        is KuljetusScreen -> {
            BackHandler { screen = HomeScreen }
            KuljetusView(
                state = state,
                onBack = { screen = HomeScreen },
                onAdd = { screen = DeliveryEditScreen(null) },
                onEdit = { d -> screen = DeliveryEditScreen(d) }
            )
        }

        is DeliveryEditScreen -> {
            BackHandler { screen = KuljetusScreen }
            DeliveryEditView(
                state = state,
                initial = s.delivery,
                onRequestNotifPermission = { requestNotifPermission() },
                onBack = { screen = KuljetusScreen },
                onDelete = { d ->
                    Reminders.cancel(context, d.id)
                    val gone = SyncEngine.stamp(
                        d.copy(deleted = true, deleted_at = Reko.nowIso()), state.deviceId
                    )
                    persist(state.copy(deliveries = state.deliveries.map { if (it.id == gone.id) gone else it }))
                    screen = KuljetusScreen
                },
                onSave = { saved ->
                    val stamped = SyncEngine.stamp(saved, state.deviceId)
                    persist(state.copy(deliveries = state.deliveries.filter { it.id != saved.id } + stamped))
                    Reminders.schedule(context, stamped)
                    screen = KuljetusScreen
                }
            )
        }

        // ── v42+: Tiedot ja synkronointi ────────────────────────────────────

        is DataSyncHubScreen -> {
            BackHandler { screen = SettingsHubScreen }
            DataSyncHubView(
                state = state,
                onBack = { screen = SettingsHubScreen },
                onProducts = { screen = ProductsScreen },
                onRings = { screen = RingsManageScreen },
                onCustomers = { screen = CustomersScreen },
                onRoutes = { screen = RoutesScreen },
                onMaintenance = { screen = MaintenanceScreen },
                onSync = { refreshSnapshots(); screen = SyncScreen },
                onTrash = { screen = TrashScreen }
            )
        }

        is ProductsScreen -> {
            BackHandler { screen = DataSyncHubScreen }
            ProductsView(
                state = state,
                onBack = { screen = DataSyncHubScreen },
                onAdd = { screen = ProductEditScreen(null) },
                onEdit = { p -> screen = ProductEditScreen(p.id) }
            )
        }

        is ProductEditScreen -> {
            BackHandler { screen = ProductsScreen }
            val initial = s.productId?.let { pid -> state.products.firstOrNull { it.id == pid } }
            ProductEditView(
                state = state,
                initial = initial,
                onBack = { screen = ProductsScreen },
                onSave = { p ->
                    val stamped = SyncEngine.stamp(p, state.deviceId)
                    persistStamped(
                        state.copy(products = state.products.filter { it.id != stamped.id } + stamped)
                    )
                    screen = ProductsScreen
                },
                onDelete = { p ->
                    val gone = SyncEngine.softDelete(p, state.deviceId)
                    persistStamped(
                        state.copy(products = state.products.map { if (it.id == gone.id) gone else it })
                    )
                    screen = ProductsScreen
                }
            )
        }

        is RingsManageScreen -> {
            BackHandler { screen = DataSyncHubScreen }
            RingsManageView(
                state = state,
                onBack = { screen = DataSyncHubScreen },
                onAdd = { screen = RingEditScreen(null) },
                onEdit = { r -> screen = RingEditScreen(r.id) }
            )
        }

        is RingEditScreen -> {
            BackHandler { screen = RingsManageScreen }
            val initial = s.ringId?.let { rid -> state.rings.firstOrNull { it.id == rid } }
            RingEditView(
                state = state,
                initial = initial,
                onBack = { screen = RingsManageScreen },
                onSave = { r ->
                    val stamped = SyncEngine.stamp(r, state.deviceId)
                    persistStamped(
                        state.copy(rings = state.rings.filter { it.id != stamped.id } + stamped)
                    )
                    screen = RingsManageScreen
                },
                onDelete = { r ->
                    val gone = SyncEngine.softDelete(r, state.deviceId)
                    persistStamped(
                        state.copy(rings = state.rings.map { if (it.id == gone.id) gone else it })
                    )
                    screen = RingsManageScreen
                }
            )
        }

        is CustomersScreen -> {
            BackHandler { screen = DataSyncHubScreen }
            CustomersView(
                state = state,
                onBack = { screen = DataSyncHubScreen },
                onAdd = { screen = CustomerEditScreen(null) },
                onEdit = { c -> screen = CustomerEditScreen(c.id) },
                onImportAll = { names ->
                    // One stamped record per name, so the import is a normal edit
                    // as far as the next sync is concerned.
                    val added = names.map {
                        SyncEngine.stamp(Customer(id = Reko.newCustomerId(), name = it), state.deviceId)
                    }
                    persistStamped(state.copy(customers = state.customers + added))
                    Toast.makeText(context, "Lis\u00e4ttiin ${added.size} asiakasta", Toast.LENGTH_SHORT).show()
                }
            )
        }

        is CustomerEditScreen -> {
            BackHandler { screen = CustomersScreen }
            val initial = s.customerId?.let { cid -> state.customers.firstOrNull { it.id == cid } }
            CustomerEditView(
                state = state,
                initial = initial,
                onBack = { screen = CustomersScreen },
                onSave = { c ->
                    val stamped = SyncEngine.stamp(c, state.deviceId)
                    persistStamped(
                        state.copy(customers = state.customers.filter { it.id != stamped.id } + stamped)
                    )
                    screen = CustomersScreen
                },
                onDelete = { c ->
                    val gone = SyncEngine.softDelete(c, state.deviceId)
                    persistStamped(
                        state.copy(customers = state.customers.map { if (it.id == gone.id) gone else it })
                    )
                    screen = CustomersScreen
                },
                onOpenOrder = { o -> screen = EditScreen(o, o.group_id, o.date) }
            )
        }

        is UnpaidScreen -> {
            BackHandler { screen = HomeScreen }
            UnpaidView(
                state = state,
                onBack = { screen = HomeScreen },
                onMarkPaid = { os, ds ->
                    // Every touched record is stamped, or the desktop's older
                    // copy would mark them unpaid again on the next sync.
                    val orderIds = os.map { it.id }.toSet()
                    val delivIds = ds.map { it.id }.toSet()
                    persistStamped(
                        state.copy(
                            orders = state.orders.map {
                                if (it.id in orderIds)
                                    SyncEngine.stamp(it.copy(paid = true), state.deviceId) else it
                            },
                            deliveries = state.deliveries.map {
                                if (it.id in delivIds)
                                    SyncEngine.stamp(it.copy(paid = true), state.deviceId) else it
                            }
                        )
                    )
                    Toast.makeText(
                        context,
                        "Merkittiin ${orderIds.size + delivIds.size} tilausta maksetuiksi",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onOpenOrder = { o -> screen = EditScreen(o, o.group_id, o.date) },
                onExportCsv = {
                    CsvExport.share(
                        context, "kasvila_maksamattomat.csv",
                        CsvExport.unpaid(state), "Maksamattomat"
                    )
                },
                onReceipt = { e ->
                    screen = ReceiptScreen(e.orders.map { it.id }, e.deliveries.map { it.id })
                }
            )
        }

        is ReceiptScreen -> {
            BackHandler { screen = s.returnTo }
            val oids = s.orderIds.toSet()
            val dids = s.deliveryIds.toSet()
            ReceiptView(
                state = state,
                orders = state.orders.filter { it.id in oids },
                deliveries = state.deliveries.filter { it.id in dids },
                onBack = { screen = s.returnTo },
                onOpenSeller = { screen = SellerInfoScreen },
                onIssued = { issued, newCounter, markPaid ->
                    val orderIds = issued.orderIds.toSet()
                    val delivIds = issued.deliveryIds.toSet()
                    val newOrders = if (markPaid) state.orders.map {
                        if (it.id in orderIds)
                            SyncEngine.stamp(
                                it.copy(paid = true, paymentMethod = issued.paymentMethod),
                                state.deviceId
                            ) else it
                    } else state.orders
                    val newDeliveries = if (markPaid) state.deliveries.map {
                        if (it.id in delivIds)
                            SyncEngine.stamp(
                                it.copy(paid = true, paymentMethod = issued.paymentMethod),
                                state.deviceId
                            ) else it
                    } else state.deliveries
                    persistStamped(
                        state.copy(
                            orders = newOrders,
                            deliveries = newDeliveries,
                            issuedReceipts = state.issuedReceipts + issued,
                            receiptCounter = newCounter
                        )
                    )
                    screen = s.returnTo
                    Toast.makeText(context, "Kuitti ${issued.number} luotu", Toast.LENGTH_SHORT).show()
                }
            )
        }

        is MaintenanceScreen -> {
            BackHandler { screen = DataSyncHubScreen }
            MaintenanceView(
                state = state,
                prefs = maintPrefs,
                onBack = { screen = DataSyncHubScreen },
                onPrefs = { p -> persistMaint(p) },
                onPickFolder = {
                    try {
                        folderLauncher.launch(null)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Kansionvalitsinta ei voitu avata", Toast.LENGTH_LONG).show()
                    }
                },
                onBackupNow = {
                    syncScope.launch {
                        val err = withContext(Dispatchers.IO) {
                            Maintenance.runBackup(context, maintPrefs)
                        }
                        persistMaint(
                            maintPrefs.copy(
                                lastBackupAt = if (err.isEmpty()) Reko.nowIso() else maintPrefs.lastBackupAt,
                                lastBackupError = err
                            )
                        )
                        Toast.makeText(
                            context,
                            if (err.isEmpty()) "Varmuuskopio kirjoitettu" else "Varmuuskopio ep\u00e4onnistui: $err",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                onRequestNotifPermission = { requestNotifPermission() },
                notifGranted = Reminders.hasNotifPermission(context)
            )
        }

        is TrashScreen -> {
            BackHandler { screen = DataSyncHubScreen }
            TrashView(
                state = state,
                onBack = { screen = DataSyncHubScreen },
                onRestoreProduct = { p ->
                    val back = SyncEngine.restore(p, state.deviceId)
                    persistStamped(state.copy(products = state.products.map { if (it.id == back.id) back else it }))
                },
                onRestoreRing = { r ->
                    val back = SyncEngine.restore(r, state.deviceId)
                    persistStamped(state.copy(rings = state.rings.map { if (it.id == back.id) back else it }))
                },
                onRestoreCustomer = { c ->
                    val back = SyncEngine.restore(c, state.deviceId)
                    persistStamped(state.copy(customers = state.customers.map { if (it.id == back.id) back else it }))
                }
            )
        }

        is SyncScreen -> {
            BackHandler { screen = DataSyncHubScreen }
            SyncView(
                state = state,
                snapshots = snapshots,
                onBack = { screen = DataSyncHubScreen },
                onExportFile = { syncExportLauncher.launch(SyncFiles.fileName()) },
                onShareFile = {
                    val peer = state.peers.keys.firstOrNull() ?: SyncFiles.legacyPeerId("desktop")
                    shareJson(
                        context, SyncFiles.fileName(),
                        SyncFiles.buildText(state, peer), "Jaa synkronointitiedosto"
                    )
                },
                onImportFile = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                onLanSync = { screen = LanSyncScreen },
                onOpenLog = { refreshSnapshots(); screen = SyncLogScreen },
                onRenameDevice = { n -> persist(state.copy(deviceName = n)) },
                onUndoLast = { undoLastSync() }
            )
        }

        is SyncPreviewScreen -> {
            BackHandler { pendingPlan = null; lanSession = null; screen = SyncScreen }
            val plan = pendingPlan
            if (plan == null) {
                screen = SyncScreen
            } else {
                SyncPreviewView(
                    plan = plan,
                    currency = state.currency,
                    onCancel = { pendingPlan = null; lanSession = null; screen = SyncScreen },
                    onApply = { choices -> applyPlan(plan, choices); screen = SyncScreen }
                )
            }
        }

        is RoutesScreen -> {
            BackHandler { screen = DataSyncHubScreen }
            RoutesView(
                state = state,
                onBack = { screen = DataSyncHubScreen },
                onOpenRing = { rid -> screen = RingEditScreen(rid) }
            )
        }

        is RekoSummaryScreen -> {
            BackHandler { screen = HomeScreen }
            RekoSummaryView(
                state = state,
                onBack = { screen = HomeScreen }
            )
        }

        is AnnouncementScreen -> {
            BackHandler { screen = s.returnTo }
            AnnouncementView(
                state = state,
                aiConfig = aiConfig,
                intro = s.intro,
                ringId = s.ringId,
                date = s.date,
                onBack = { screen = s.returnTo }
            )
        }

        is LanSyncScreen -> {
            BackHandler { screen = SyncScreen }
            LanSyncView(
                state = state,
                onBack = { screen = SyncScreen },
                onExchanged = { payload, peer, code ->
                    lanSession = peer to code
                    pendingPlan = SyncEngine.plan(state, payload, "lan")
                    screen = SyncPreviewScreen
                }
            )
        }

        is SyncLogScreen -> {
            BackHandler { screen = SyncScreen }
            SyncLogView(
                state = state,
                snapshots = snapshots,
                onBack = { screen = SyncScreen },
                onRestoreSnapshot = { snap ->
                    val restored = store.restoreSnapshot(snap.file)
                    if (restored != null) {
                        state = restored
                        refreshSnapshots()
                        Toast.makeText(context, "Varmuuskopio palautettu", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Palautus ep\u00e4onnistui", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
    }

    // v56: update prompt from the automatic GitHub check. Rendered over any screen.
    updateAvailable?.let { avail ->
        UpdateDialog(
            avail = avail,
            onDownload = {
                openUrl(context, avail.apkUrl.ifBlank { avail.pageUrl })
                updateAvailable = null
            },
            onSkip = {
                persistUpdatePrefs(updatePrefs.copy(skipVersion = avail.versionCode))
                updateAvailable = null
            },
            onLater = { updateAvailable = null }
        )
    }

}

// ── home: rings overview ──────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeView(
    state: AppState,
    onOpenRing: (String) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onSettings: () -> Unit,
    onRekoSummary: () -> Unit,
    onUnpaid: () -> Unit,
    onWeekSummary: () -> Unit,
    onDeliverySummary: () -> Unit,
    onKuljetus: () -> Unit,
    onOstot: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Kasvila REKO", maxLines = 1, softWrap = false)
                        Text(
                            "versio ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, softWrap = false
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOstot) {
                        Icon(Icons.Filled.ShoppingCart, "Ostot ja varasto")
                    }
                    IconButton(onClick = onImport) {
                        Icon(Icons.Filled.Download, "Tuo piirit")
                    }
                    IconButton(onClick = onExport) {
                        Icon(Icons.Filled.Upload, "Vie tilaukset")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, "Asetukset")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            if (state.visibleRings().isEmpty()) {
                Text(
                    "Ei ruokapiirej\u00e4. Paina 'Tuo piirit' ja valitse ty\u00f6p\u00f6yt\u00e4ohjelmasta viety " +
                        "kasvila_reko_piirit.json.",
                    style = MaterialTheme.typography.bodyMedium
                )
                return@Column
            }

            val totalOrders = state.liveOrders.size
            val totalSum = state.liveOrders.sumOf { it.total }
            Text(
                "Tilauksia yhteens\u00e4 $totalOrders   \u00b7   ${Reko.money(totalSum, state.currency)}",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
            )
            if (state.catalogImportedAt.isNotBlank() || state.catalogExportedAt.isNotBlank()) {
                val exported = catalogStamp(state.catalogExportedAt)
                val imported = catalogStamp(state.catalogImportedAt)
                val verPart = if (state.catalogVersion > 0) "Katalogi v${state.catalogVersion}" else "Katalogi"
                val line = when {
                    exported.isNotBlank() && imported.isNotBlank() ->
                        "$verPart: viety $exported \u00b7 tuotu $imported"
                    imported.isNotBlank() -> "$verPart tuotu $imported"
                    else -> "$verPart viety $exported"
                }
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onWeekSummary, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
                ) {
                    Text("Viikkokooste", maxLines = 1, softWrap = false,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onDeliverySummary, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
                ) {
                    Text("Jakelukooste", maxLines = 1, softWrap = false,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onKuljetus, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
                ) {
                    Text("Kuljetus", maxLines = 1, softWrap = false,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedButton(
                    onClick = onRekoSummary, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                ) {
                    Text("Yhteenveto", maxLines = 1, softWrap = false,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.width(8.dp))
                val unpaidTotal = remember(state.orders, state.deliveries) { Unpaid.grandTotal(state) }
                OutlinedButton(
                    onClick = onUnpaid, modifier = Modifier.weight(1.3f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    colors = if (unpaidTotal > 0)
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error)
                    else ButtonDefaults.outlinedButtonColors()
                ) {
                    Text(
                        if (unpaidTotal > 0)
                            "Maksamatta ${Reko.money(unpaidTotal, state.currency)}"
                        else "Maksamattomat",
                        maxLines = 1, softWrap = false,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            // A cancelled pickup is the one thing you must not discover on the
            // day, so it goes where it cannot be missed.
            val cancellations = remember(state.distributions, state.rings) {
                Pickups.upcomingCancellations(state)
            }
            if (cancellations.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (cancellations.size == 1) "Tuleva jakelu peruttu"
                            else "${cancellations.size} tulevaa jakelua peruttu",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        cancellations.take(4).forEach { (ring, d) ->
                            Text(
                                "${d.date}  ·  ${ring.name}" +
                                    if (d.notes.isNotBlank()) "  —  ${d.notes}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))

            Column(Modifier.verticalScroll(rememberScrollState())) {
                val (activeRings, retiredRings) = state.visibleRings().partition { !it.retired }
                activeRings.forEach { ring ->
                    val orders = state.liveOrders.filter { it.group_id == ring.id }
                    RingSummaryCard(
                        name = ring.name,
                        count = orders.size,
                        sum = orders.sumOf { it.total },
                        currency = state.currency,
                        retired = false,
                        onClick = { onOpenRing(ring.id) }
                    )
                }
                if (retiredRings.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "Poistuvat piirit",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Eiv\u00e4t en\u00e4\u00e4 katalogissa, mutta niill\u00e4 on avoimia tilauksia. " +
                            "Poistuvat itsest\u00e4\u00e4n kun tilaukset on hoidettu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    retiredRings.forEach { ring ->
                        val orders = state.liveOrders.filter { it.group_id == ring.id }
                        RingSummaryCard(
                            name = ring.name,
                            count = orders.size,
                            sum = orders.sumOf { it.total },
                            currency = state.currency,
                            retired = true,
                            onClick = { onOpenRing(ring.id) }
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    "Avaa piiri lis\u00e4t\u00e4ksesi tai muokataksesi tilauksia.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RingSummaryCard(
    name: String, count: Int, sum: Double, currency: String,
    retired: Boolean = false, onClick: () -> Unit
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (retired) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface
                    )
                    if (retired) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "POISTUVA",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Text(
                    if (count == 0) "Ei tilauksia" else "$count tilausta  \u00b7  ${Reko.money(sum, currency)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (count == 0) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary
                )
            }
            Icon(Icons.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── ring detail: all orders for one ring, grouped by date ─────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RingView(
    state: AppState,
    ringId: String,
    onBack: () -> Unit,
    onAdd: (String) -> Unit,
    onEdit: (Order) -> Unit,
    onDaySummary: (String, String) -> Unit,
    onEditPickup: (String, String) -> Unit
) {
    val ring = state.rings.firstOrNull { it.id == ringId }
    // Exception-aware: cancelled days are gone from the list and a pickup moved
    // onto an off-pattern day is in it. See Pickups for why that matters.
    val upcoming = remember(ringId, state.distributions) {
        ring?.let { Pickups.upcoming(state, it) } ?: emptyList()
    }
    var date by remember(ringId) { mutableStateOf(upcoming.firstOrNull() ?: LocalDate.now().toString()) }
    val exceptions = remember(ringId, state.distributions) {
        Pickups.upcomingExceptions(state, ringId)
    }
    val thisPickup = state.distributionFor(ringId, date.trim())

    val orders = state.liveOrders.filter { it.group_id == ringId }
    val byDate = orders.groupBy { it.date }.toSortedMap(compareByDescending { it })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ring?.name ?: "Ruokapiiri") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin") }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            if (ring?.retired == true) {
                Text(
                    "\u26a0 T\u00e4m\u00e4 piiri on poistettu katalogista. N\u00e4ytet\u00e4\u00e4n, koska sill\u00e4 " +
                        "on viel\u00e4 avoimia tilauksia \u2014 hoida ne loppuun. Piiri poistuu " +
                        "itsest\u00e4\u00e4n kun tilaukset on hoidettu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(10.dp))
            }
            DropdownField(
                label = "P\u00e4iv\u00e4m\u00e4\u00e4r\u00e4 (VVVV-KK-PP)",
                value = date,
                options = upcoming,
                editable = true,
                onValueChange = { date = it },
                onSelectIndex = { idx -> date = upcoming[idx] }
            )

            // What is special about the selected day, if anything.
            if (thisPickup != null && thisPickup.isNoteworthy()) {
                val bits = buildList {
                    if (thisPickup.cancelled) add("EI JAKELUA")
                    if (thisPickup.time.isNotBlank()) add("klo ${thisPickup.time}")
                    if (thisPickup.address.isNotBlank()) add(thisPickup.address)
                    if (thisPickup.status != "planned")
                        add(Distribution.statusLabel(thisPickup.status))
                }
                Text(
                    bits.joinToString("  \u00b7  ") +
                        if (thisPickup.notes.isNotBlank()) "  \u2014  ${thisPickup.notes}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (thisPickup.cancelled) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = { onAdd(date.trim()) }) {
                    Icon(Icons.Filled.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Uusi tilaus")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { onEditPickup(ringId, date.trim()) }) {
                    Text("Tämän noudon tiedot", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (exceptions.size > 1 || (exceptions.size == 1 && exceptions.first().date != date.trim())) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tulevat poikkeukset: " + exceptions.joinToString("  ·  ") { e ->
                        e.date + (if (e.cancelled) " (ei jakelua)" else "")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Yhteens\u00e4 ${orders.size} tilausta  \u00b7  ${Reko.money(orders.sumOf { it.total }, state.currency)}",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))

            if (orders.isEmpty()) {
                Text(
                    "Ei viel\u00e4 tilauksia t\u00e4lle piirille.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    byDate.forEach { (d, list) ->
                        Row(
                            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                dateHeader(d),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { onDaySummary(ringId, d) }) { Text("Kooste") }
                        }
                        list.forEach { o -> OrderRow(o, state.currency, state.products) { onEdit(o) } }
                        Text(
                            "P\u00e4iv\u00e4n summa: ${Reko.money(list.filter { !it.cancelled }.sumOf { it.total }, state.currency)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                        )
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrderRow(o: Order, cur: String, products: List<Product>, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    o.customer.ifBlank { "(nimet\u00f6n)" }, fontWeight = FontWeight.Bold,
                    color = if (o.cancelled) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (o.cancelled) TextDecoration.LineThrough else null
                )
                Text(Reko.money(o.total, cur), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            if (o.lines.isNotEmpty()) {
                Text(
                    o.lines.joinToString("  \u00b7  ") { "${it.name} ${trimNum(it.qty)} ${Reko.unitFor(it, products)}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (o.cancelled) {
                Text("PERUTTU", style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            }
            val flags = buildList {
                if (o.paid) add("Maksettu")
                if (o.picked) add("Noudettu")
            }
            if (flags.isNotEmpty()) {
                Text(flags.joinToString(" \u00b7 "), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

// ── order edit ────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrderEditView(
    state: AppState,
    aiConfig: AiConfig,
    initial: Order?,
    ringId: String,
    date: String,
    initialPaste: String = "",
    onBack: () -> Unit,
    onDelete: (Order) -> Unit,
    onSave: (Order) -> Unit,
    onReceipt: (Order) -> Unit,
    onLearnAlias: (String, String) -> Unit
) {
    val context = LocalContext.current
    val ring = state.rings.firstOrNull { it.id == ringId }
    // Catalog for MATCHING pasted text = every product plus this phone's learned
    // aliases, including discontinued ones — an old order text should still be
    // recognised. Catalog for PICKING = only products actually in use.
    val catalog = OrderParser.mergeAliases(state.products, state.localAliases)
    val catalogProducts = state.activeProducts()
    var customer by remember { mutableStateOf(initial?.customer ?: "") }
    var contact by remember { mutableStateOf(initial?.contact ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var paid by remember { mutableStateOf(initial?.paid ?: false) }
    var picked by remember { mutableStateOf(initial?.picked ?: false) }
    var cancelled by remember { mutableStateOf(initial?.cancelled ?: false) }
    val lines = remember { mutableStateListOf<Line>().apply { initial?.lines?.let { addAll(it) } } }
    var showDupWarn by remember { mutableStateOf(false) }
    var editLineIndex by remember { mutableStateOf<Int?>(null) }   // v36

    // Paste/AI detection state. [initialPaste] arrives from a share intent and
    // is parsed immediately below, so shared text lands as reviewable suggestions
    // rather than as something the user still has to press a button about.
    var pasteText by remember { mutableStateOf(initialPaste) }
    var parsed by remember { mutableStateOf<List<ParsedLine>>(emptyList()) }
    var unmatched by remember { mutableStateOf<List<String>>(emptyList()) }
    var aiError by remember { mutableStateOf("") }
    var useAi by remember { mutableStateOf(false) }
    var aiBusy by remember { mutableStateOf(false) }

    /**
     * Text shared in from another app is parsed with the OFFLINE rules the
     * moment the editor opens, so the user sees reviewable suggestions instead
     * of a wall of text and a button.
     *
     * The AI path is deliberately never triggered automatically: it costs money,
     * needs a network that may not exist at a pickup point, and the whole point
     * of sharing the comment was to save time. The user can still press the AI
     * toggle if the rules missed something.
     */
    LaunchedEffect(initialPaste) {
        if (initialPaste.isNotBlank()) {
            val cn = OrderParser.extractCustomer(initialPaste, catalog)
            if (cn.first != null && customer.isBlank()) customer = cn.first!!
            val res = OrderParser.parse(cn.second, catalog)
            parsed = res.lines
            unmatched = res.unmatched
        }
    }

    // draft line — product chosen from the catalog only (no keyboard)
    var pName by remember { mutableStateOf("") }
    var pUnit by remember { mutableStateOf("kpl") }
    var pPrice by remember { mutableStateOf(0.0) }
    var pQty by remember { mutableStateOf(1.0) }
    var pProductId by remember { mutableStateOf("") }   // v37: keep the catalog id on manual lines

    fun addDraftLine() {
        if (pName.isBlank()) return
        lines.add(Line(pName.trim(), if (pQty <= 0) 1.0 else pQty, pUnit, pPrice, pProductId))
        pName = ""; pUnit = "kpl"; pPrice = 0.0; pQty = 1.0; pProductId = ""
    }

    fun doSave() {
        addDraftLine()   // commit any pending product automatically
        parsed.forEach { pl ->
            lines.add(Line(pl.name, pl.qty, pl.unit, pl.unitPrice, pl.productId))
        }
        onSave(
            Order(
                id = initial?.id ?: Reko.newOrderId(),
                group_id = ringId,
                date = date.trim(),
                time = ring?.time ?: "",
                address = ring?.address ?: "",
                customer = customer.trim(),
                contact = contact.trim(),
                paid = paid,
                picked = picked,
                cancelled = cancelled,
                note = note.trim(),
                updated_at = Reko.nowIso(),
                lines = lines.toList()
            )
        )
    }

    // Flag a likely duplicate: a new order for a customer who already has a
    // (non-cancelled) order in the same ring on the same date.
    fun isDuplicate(): Boolean {
        if (initial != null) return false
        val norm = customer.trim().lowercase()
        if (norm.isBlank()) return false
        return state.liveOrders.any {
            it.group_id == ringId && it.date == date.trim() && !it.cancelled &&
                it.customer.trim().lowercase() == norm
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "Uusi tilaus" else "Muokkaa tilausta") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin") }
                },
                actions = {
                    if (initial != null) {
                        IconButton(onClick = { onDelete(initial) }) {
                            Icon(Icons.Filled.Delete, "Poista", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            Text(
                "${ring?.name ?: ""}  \u00b7  $date",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
            )
            if (initial != null) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onReceipt(initial) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Print, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Tulosta kuitti asiakkaalle")
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(customer, { customer = it }, label = { Text("Asiakas") },
                singleLine = true, modifier = Modifier.fillMaxWidth())

            // v47: name completion from everyone the app has ever seen — the
            // register AND the order history, so it works before anyone has been
            // registered. Picking a name also fills in the contact we already
            // recorded, which is the whole reason to keep a register at all.
            val suggestions = remember(customer, state.customers, state.orders) {
                if (initial != null) emptyList() else CustomerBook.suggest(state, customer)
            }
            if (suggestions.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(top = 6.dp)
                ) {
                    suggestions.forEach { name ->
                        AssistChip(
                            onClick = {
                                customer = name
                                if (contact.isBlank()) {
                                    CustomerBook.find(state, name)?.let { c ->
                                        contact = listOf(c.phone, c.contact, c.email)
                                            .firstOrNull { it.isNotBlank() } ?: ""
                                    }
                                }
                            },
                            label = { Text(name, maxLines = 1) },
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
            }

            // "Same as last time" — the single most common REKO order.
            val repeatLines = remember(customer, state.orders, state.products) {
                if (customer.isBlank()) emptyList()
                else CustomerBook.lastOrderLines(state, customer, state.products)
            }
            if (repeatLines.isNotEmpty() && lines.isEmpty()) {
                OutlinedButton(
                    onClick = { lines.addAll(repeatLines) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) {
                    Text(
                        "Sama kuin viimeksi (" + repeatLines.size + " rivi\u00e4)",
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "Hinnat p\u00e4ivitet\u00e4\u00e4n nykyisen katalogin mukaan.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(contact, { contact = it }, label = { Text("Yhteystieto (valinnainen)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(16.dp))
            Text("Tuoterivit", fontWeight = FontWeight.Bold)
            lines.forEachIndexed { i, ln ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${ln.name}  ${trimNum(ln.qty)} ${Reko.unitFor(ln, state.products)}  \u00d7 ${Reko.money(ln.unit_price, state.currency)}",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(Reko.money(ln.qty * ln.unit_price, state.currency),
                        color = MaterialTheme.colorScheme.primary)
                    IconButton(onClick = { editLineIndex = i }) {
                        Icon(Icons.Filled.Edit, "Muokkaa rivi\u00e4")
                    }
                    IconButton(onClick = { lines.removeAt(i) }) {
                        Icon(Icons.Filled.Delete, "Poista rivi")
                    }
                }
            }
            editLineIndex?.let { idx ->
                if (idx < lines.size) {
                    LineEditDialog(
                        line = lines[idx],
                        products = state.products,
                        currency = state.currency,
                        onSave = { updated -> lines[idx] = updated; editLineIndex = null },
                        onDismiss = { editLineIndex = null }
                    )
                } else {
                    editLineIndex = null
                }
            }

            // add-line — product picked from the menu only (no keyboard)
            Spacer(Modifier.height(10.dp))
            DropdownField(
                label = "Valitse tuote",
                value = if (pName.isBlank()) "" else "$pName  (${Reko.money(pPrice, state.currency)} / $pUnit)",
                options = catalogProducts.map { "${it.name}  \u2013  ${Reko.money(it.price, state.currency)} / ${it.unit}" },
                editable = false,
                onSelectIndex = { idx ->
                    val pr = catalogProducts[idx]
                    pName = pr.name; pUnit = pr.unit; pPrice = pr.price; pProductId = pr.id
                }
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("M\u00e4\u00e4r\u00e4:", modifier = Modifier.padding(end = 8.dp))
                QtyStepper(pQty, pUnit) { pQty = it }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { addDraftLine() }, enabled = pName.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) { Text("Lis\u00e4\u00e4", maxLines = 1, softWrap = false) }
            }
            if (pName.isNotBlank()) {
                Text(
                    "Voit painaa suoraan Tallenna \u2013 valittu tuote lis\u00e4t\u00e4\u00e4n automaattisesti.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // ── Optional: paste a Facebook order and auto-detect products ──
            Spacer(Modifier.height(16.dp))
            Text("Liit\u00e4 tai sanele tilaus (valinnainen)", fontWeight = FontWeight.Bold)
            Text(
                "Liit\u00e4 asiakkaan tilaus tai sanele se mikrofonilla. Ohjelma tunnistaa asiakkaan, tuotteet ja m\u00e4\u00e4r\u00e4t. Tarkista ehdotukset ennen lis\u00e4yst\u00e4.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                pasteText, { pasteText = it },
                label = { Text("Liit\u00e4 tilausteksti") },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                minLines = 2, maxLines = 5
            )
            val scope = rememberCoroutineScope()
            val aiKey = aiConfig.keys[aiConfig.provider] ?: ""
            val provLabel = AiProviders.byId(aiConfig.provider)?.label ?: aiConfig.provider

            // Shared detection for both paste and voice. extractCust=true (voice)
            // also pulls a customer name from the spoken text.
            fun detect(src: String, extractCust: Boolean) {
                if (src.isBlank()) return
                var ruleText = src
                if (extractCust) {
                    val cn = OrderParser.extractCustomer(src, catalog)
                    if (cn.first != null) {
                        if (customer.isBlank()) customer = cn.first!!
                        ruleText = cn.second
                    }
                }
                if (useAi && aiKey.isNotBlank()) {
                    aiBusy = true
                    val msgs = OrderParser.buildAiMessages(src, catalog)
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { AiClient.chat(aiConfig, msgs.first, msgs.second) }
                        aiBusy = false
                        when (result) {
                            is AiResult.Ok -> {
                                val pr = OrderParser.parseAiResponse(result.text, catalog)
                                if (pr.lines.isEmpty() && pr.unmatched.isEmpty()) {
                                    val rb = OrderParser.parse(ruleText, catalog)
                                    parsed = rb.lines; unmatched = rb.unmatched
                                    aiError = "Teko\u00e4ly ei palauttanut tuloksia \u2013 k\u00e4ytettiin offline-tunnistusta"
                                } else {
                                    parsed = pr.lines; unmatched = pr.unmatched
                                    if (pr.customer != null && customer.isBlank()) customer = pr.customer!!
                                    aiError = ""
                                }
                            }
                            is AiResult.Err -> {
                                val rb = OrderParser.parse(ruleText, catalog)
                                parsed = rb.lines; unmatched = rb.unmatched
                                aiError = "Teko\u00e4ly: ${result.code}"
                                Toast.makeText(context, "Teko\u00e4ly ep\u00e4onnistui: ${result.code}", Toast.LENGTH_LONG).show()
                            }
                        }
                        if (parsed.isEmpty())
                            Toast.makeText(context, "Ei tunnistettuja tuotteita", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val res = OrderParser.parse(ruleText, catalog)
                    parsed = res.lines
                    unmatched = res.unmatched
                    if (res.lines.isEmpty())
                        Toast.makeText(context, "Ei tunnistettuja tuotteita", Toast.LENGTH_SHORT).show()
                }
            }

            // Speech recognition (fi-FI). The recognizer app handles the mic;
            // no RECORD_AUDIO permission is needed for this intent approach.
            val speechLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { res ->
                if (res.resultCode == Activity.RESULT_OK) {
                    val spoken = res.data
                        ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                        ?.firstOrNull().orEmpty()
                    if (spoken.isNotBlank()) {
                        pasteText = spoken
                        detect(spoken, true)
                    } else {
                        Toast.makeText(context, "Ei puhetta tunnistettu", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            fun startVoice() {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fi-FI")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Sanele tilaus \u2013 esim. Venlalle yksi favaburgermassa ja kaksi hummusta")
                }
                try {
                    speechLauncher.launch(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "Puheentunnistus ei ole k\u00e4ytett\u00e4viss\u00e4 t\u00e4ll\u00e4 laitteella", Toast.LENGTH_LONG).show()
                }
            }

            OutlinedButton(
                onClick = { startVoice() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                enabled = !aiBusy
            ) {
                Icon(Icons.Filled.Mic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Sanele tilaus")
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Checkbox(useAi, { useAi = it }, enabled = aiKey.isNotBlank())
                Text(
                    if (aiKey.isBlank()) "Teko\u00e4ly: aseta malli ja avain asetuksissa"
                    else "K\u00e4yt\u00e4 teko\u00e4ly\u00e4 ($provLabel)",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (aiKey.isBlank()) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurface
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { detect(pasteText, false) },
                    enabled = pasteText.isNotBlank() && !aiBusy
                ) { Text(if (aiBusy) "Tunnistetaan\u2026" else "Tunnista tuotteet") }
                if (parsed.isNotEmpty()) {
                    Spacer(Modifier.weight(1f))
                    Button(onClick = {
                        parsed.forEach { pl ->
                            lines.add(Line(pl.name, pl.qty, pl.unit, pl.unitPrice, pl.productId))
                        }
                        parsed = emptyList(); unmatched = emptyList(); pasteText = ""
                    }) { Text("Lis\u00e4\u00e4 tilaukseen") }
                }
            }
            if (aiError.isNotBlank()) {
                Text(
                    aiError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            parsed.forEach { pl ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val col = if (pl.confidence == "medium")
                        MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
                    val extra = if (pl.confidence == "medium") "  (tarkista)" else ""
                    Text(
                        "${trimNum(pl.qty)} \u00d7 ${pl.name}$extra",
                        modifier = Modifier.weight(1f), color = col,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(Reko.money(pl.unitPrice, state.currency),
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            if (unmatched.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Ei tunnistettu \u2013 opeta tuotteelle (tallentuu t\u00e4h\u00e4n puhelimeen):",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
                unmatched.forEach { frag ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "\u00ab$frag\u00bb",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Box(Modifier.width(190.dp)) {
                            DropdownField(
                                label = "Opeta tuotteelle",
                                value = "",
                                options = catalogProducts.map { it.name },
                                editable = false,
                                onSelectIndex = { idx ->
                                    val prod = catalogProducts[idx]
                                    val alias = OrderParser.productPart(frag)
                                    if (alias.isNotBlank()) {
                                        onLearnAlias(prod.key(), alias)
                                        val newLocal = state.localAliases +
                                            (prod.key() to ((state.localAliases[prod.key()] ?: emptyList()) + alias).distinct())
                                        val merged = OrderParser.mergeAliases(state.products, newLocal)
                                        val res = OrderParser.parse(pasteText, merged)
                                        parsed = res.lines
                                        unmatched = res.unmatched
                                        Toast.makeText(context, "Opittu: \u00ab$alias\u00bb \u2192 ${prod.name}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(paid, { paid = it }); Text("Maksettu")
                Spacer(Modifier.width(16.dp))
                Checkbox(picked, { picked = it }); Text("Noudettu")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = cancelled,
                    onCheckedChange = { cancelled = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.error,
                        uncheckedColor = MaterialTheme.colorScheme.error,
                        checkmarkColor = MaterialTheme.colorScheme.onError
                    )
                )
                Text(
                    "Peruttu (asiakas perui tilauksen)",
                    color = if (cancelled) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
                )
            }
            if (cancelled) {
                Text(
                    "Peruttu tilaus s\u00e4ilyy ja siirtyy ty\u00f6p\u00f6yt\u00e4ohjelmaan perumistietona, " +
                        "mutta ei lasketa summiin eik\u00e4 ker\u00e4ilyyn.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(note, { note = it }, label = { Text("Huomiot (valinnainen)") },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp))

            Spacer(Modifier.height(12.dp))
            val liveTotal = lines.sumOf { it.qty * it.unit_price } +
                (if (pName.isNotBlank()) pQty * pPrice else 0.0)
            Text(
                "Tilauksen summa: ${Reko.money(liveTotal, state.currency)}",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(16.dp))
            Row {
                Button(
                    onClick = {
                        if (customer.isBlank()) {
                            Toast.makeText(context, "Sy\u00f6t\u00e4 asiakkaan nimi", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (isDuplicate()) showDupWarn = true else doSave()
                    }
                ) { Text("Tallenna") }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = onBack) { Text("Peruuta") }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (showDupWarn) {
        AlertDialog(
            onDismissRequest = { showDupWarn = false },
            title = { Text("Mahdollinen tuplatilaus") },
            text = {
                Text(
                    "Asiakkaalla \u201c${customer.trim()}\u201d on jo tilaus t\u00e4lle jakelulle " +
                        "(${dateHeader(date)}). Luodaanko silti uusi erillinen tilaus?\n\n" +
                        "Jos halusit muuttaa aiempaa tilausta, peruuta ja avaa se muokattavaksi."
                )
            },
            confirmButton = {
                TextButton(onClick = { showDupWarn = false; doSave() }) { Text("Luo silti") }
            },
            dismissButton = {
                TextButton(onClick = { showDupWarn = false }) { Text("Peruuta") }
            }
        )
    }
}

/** Quantity stepper: − [value] +  — no keyboard needed. */
@Composable
private fun QtyStepper(qty: Double, unit: String, onChange: (Double) -> Unit) {
    val step = if (unit == "kg" || unit == "g" || unit == "l" || unit == "dl") 0.5 else 1.0
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { onChange(maxOf(step, qty - step)) }) {
            Icon(Icons.Filled.Remove, "V\u00e4henn\u00e4")
        }
        Text(
            "${trimNum(qty)} $unit",
            modifier = Modifier.widthIn(min = 64.dp).padding(horizontal = 8.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        FilledTonalIconButton(onClick = { onChange(qty + step) }) {
            Icon(Icons.Filled.Add, "Lis\u00e4\u00e4")
        }
    }
}

/** v36: edit one order/delivery line in place. Re-picking a product fixes the
 *  name, unit, price and product id together (e.g. a mis-detected "FavaDo 1kg"
 *  swapped to "FavaDo 250g"); the unit dropdown and quantity stepper handle the
 *  cases where only the package size or amount is wrong. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LineEditDialog(
    line: Line,
    products: List<Product>,
    currency: String,
    onSave: (Line) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(line.name) }
    var unit by remember { mutableStateOf(Reko.unitFor(line, products)) }
    var price by remember { mutableStateOf(line.unit_price) }
    var qty by remember { mutableStateOf(line.qty) }
    var productId by remember { mutableStateOf(line.product_id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Muokkaa rivi\u00e4") },
        text = {
            Column {
                Text(
                    "Alkuper\u00e4inen: ${line.name} ${trimNum(line.qty)} ${line.unit}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                DropdownField(
                    label = "Vaihda tuote (korjaa my\u00f6s pakkauskoon ja hinnan)",
                    value = if (name.isBlank()) "" else "$name  (${Reko.money(price, currency)} / $unit)",
                    options = products.map { "${it.name}  \u2013  ${Reko.money(it.price, currency)} / ${it.unit}" },
                    editable = false,
                    onSelectIndex = { idx ->
                        val pr = products[idx]
                        name = pr.name
                        unit = pr.unit
                        price = pr.price
                        productId = pr.id
                    }
                )
                Spacer(Modifier.height(10.dp))
                DropdownField(
                    label = "Yksikk\u00f6 / pakkauskoko",
                    value = unit,
                    options = REKO_UNITS,
                    editable = false,
                    onSelectIndex = { idx -> unit = REKO_UNITS[idx] }
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("M\u00e4\u00e4r\u00e4:", modifier = Modifier.padding(end = 8.dp))
                    QtyStepper(qty, unit) { qty = it }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Rivin summa: ${Reko.money(qty * price, currency)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        line.copy(
                            name = name,
                            qty = if (qty <= 0) 1.0 else qty,
                            unit = unit,
                            unit_price = price,
                            product_id = productId
                        )
                    )
                },
                enabled = name.isNotBlank()
            ) { Text("Tallenna") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Peruuta") }
        }
    )
}


/** Reusable dropdown; when [editable] the value can also be typed freely. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownField(
    label: String,
    value: String,
    options: List<String>,
    editable: Boolean = false,
    onValueChange: (String) -> Unit = {},
    onSelectIndex: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = !editable,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        if (options.isNotEmpty()) {
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEachIndexed { idx, opt ->
                    DropdownMenuItem(
                        text = { Text(opt) },
                        onClick = { onSelectIndex(idx); expanded = false }
                    )
                }
            }
        }
    }
}

private fun dateHeader(iso: String): String {
    return try {
        val d = LocalDate.parse(iso)
        "${Reko.weekdayLabel(d.dayOfWeek.value - 1)} ${d.dayOfMonth}.${d.monthValue}.${d.year}"
    } catch (e: Exception) {
        iso
    }
}

/** v33: format an ISO timestamp like "2026-08-03T14:30:00" as "3.8.2026 klo 14:30"
 *  for the catalog freshness line. Falls back to the date part, then the raw
 *  string, so a date-only or odd value never crashes the UI. */
private fun catalogStamp(iso: String): String {
    if (iso.isBlank()) return ""
    return try {
        val dt = java.time.LocalDateTime.parse(iso)
        "%d.%d.%d klo %02d:%02d".format(dt.dayOfMonth, dt.monthValue, dt.year, dt.hour, dt.minute)
    } catch (e: Exception) {
        try {
            val d = LocalDate.parse(iso.take(10))
            "${d.dayOfMonth}.${d.monthValue}.${d.year}"
        } catch (e2: Exception) {
            iso
        }
    }
}


/**
 * Settings hub. Appearance (Ulkoasu) lives at the top so the new theme/font/size
 * controls are one tap from the gear; AI settings sit below as their own section.
 * Changing any appearance option re-themes the whole app live, because [UiPrefs]
 * is hoisted above [KasvilaTheme] in onCreate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsView(
    state: AppState,
    prefs: UiPrefs,
    onPrefs: (UiPrefs) -> Unit,
    onBack: () -> Unit,
    onOpenSeller: () -> Unit,
    onOpenPrinter: () -> Unit,
    onOpenReceiptLog: () -> Unit,
    onOpenOperators: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenIntro: () -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenData: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Asetukset") },
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
            Text("Ulkoasu", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Muutokset näkyvät heti koko sovelluksessa. Asetukset tallennetaan vain " +
                    "tähän puhelimeen — ne eivät siirry työpöytäohjelmaan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))

            DropdownField(
                label = "Teema",
                value = UiThemes.labelOf(UiThemes.PRESETS, prefs.preset),
                options = UiThemes.PRESETS.map { it.second },
                editable = false,
                onSelectIndex = { idx -> onPrefs(prefs.copy(preset = UiThemes.PRESETS[idx].first)) }
            )
            Spacer(Modifier.height(6.dp))
            Text(
                UiThemes.presetDesc(prefs.preset),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))

            DropdownField(
                label = "Tila (vaalea/tumma)",
                value = UiThemes.labelOf(UiThemes.MODES, prefs.mode),
                options = UiThemes.MODES.map { it.second },
                editable = false,
                onSelectIndex = { idx -> onPrefs(prefs.copy(mode = UiThemes.MODES[idx].first)) }
            )
            Spacer(Modifier.height(14.dp))

            DropdownField(
                label = "Fontti",
                value = UiThemes.labelOf(UiThemes.FONTS, prefs.font),
                options = UiThemes.FONTS.map { it.second },
                editable = false,
                onSelectIndex = { idx -> onPrefs(prefs.copy(font = UiThemes.FONTS[idx].first)) }
            )
            Spacer(Modifier.height(14.dp))

            DropdownField(
                label = "Tekstikoko",
                value = UiThemes.sizeLabel(prefs.textScale),
                options = UiThemes.SIZES.map { it.second },
                editable = false,
                onSelectIndex = { idx -> onPrefs(prefs.copy(textScale = UiThemes.SIZES[idx].first)) }
            )

            Spacer(Modifier.height(22.dp))
            HorizontalDivider()
            Spacer(Modifier.height(22.dp))

            Text("Tekoäly", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Tilausten tunnistuksen tekoälymalli ja API-avain.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpenAi, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Tekoälyasetukset")
            }

            Spacer(Modifier.height(22.dp))
            HorizontalDivider()
            Spacer(Modifier.height(22.dp))

            Text("Myyjän tiedot ja kuitti", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Yrityksen tiedot, logo ja maksutiedot, jotka tulostuvat asiakkaan kuittiin. " +
                    "Tallennetaan tähän puhelimeen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpenSeller, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Myyjän tiedot")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenPrinter, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Kuitti ja tulostin")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenReceiptLog, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Myyntiloki (kuitit)")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenOperators, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Käyttäjät (palvelijat)")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenIntro, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Tuottajaesittely (tekstipohja)")
            }

            Spacer(Modifier.height(22.dp))
            HorizontalDivider()
            Spacer(Modifier.height(22.dp))

            Text("Tili ja turvallisuus", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Kirjautuminen, salasanan vaihto ja palautuskoodi. Tiedot on salattu tässä " +
                    "puhelimessa.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpenSecurity, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Tili ja turvallisuus")
            }

            Spacer(Modifier.height(22.dp))
            HorizontalDivider()
            Spacer(Modifier.height(22.dp))

            Text("Sovelluksen päivitykset", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Tarkista uusi versio ja lataa se. Nykyinen versio ${BuildConfig.VERSION_NAME}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpenUpdates, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Sovelluksen päivitykset")
            }

            Spacer(Modifier.height(22.dp))
            HorizontalDivider()
            Spacer(Modifier.height(22.dp))

            Text("Tiedot ja synkronointi", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Tuotteet ja hinnat, ruokapiirit, REKO-reitit, laiteyhteys ja roskakori. " +
                    "Täällä tehdyt muutokset siirtyvät työpöytäohjelmaan synkronoinnissa.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpenData, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Avaa tiedot ja synkronointi")
            }
            val last = state.syncLog.lastOrNull()
            Text(
                if (last == null) "Ei vielä synkronoitu työpöytäohjelman kanssa."
                else "Viimeksi synkronoitu ${stampShort(last.at)} · ${last.peerName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiSettingsView(
    config: AiConfig,
    state: AppState,
    onRequestNotifPermission: () -> Unit,
    onBack: () -> Unit,
    onSave: (AiConfig) -> Unit,
    onOpenAliases: () -> Unit,
    onPersist: (AiConfig) -> Unit = {},
    canRestore: Boolean = false,
    onRestore: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(config.provider) }
    var model by remember { mutableStateOf(config.model) }
    var key by remember { mutableStateOf(config.keys[config.provider] ?: "") }
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var confirmRestore by remember { mutableStateOf(false) }   // v34
    val prov = AiProviders.byId(provider) ?: AiProviders.list.first()

    // v39: live model lists per provider (fetched from the provider, cached on
    // device). Seeded from the saved config so the picker shows the latest known
    // models even before the first fetch of this session and while offline.
    var fetchedModels by remember { mutableStateOf(config.fetchedModels) }
    var fetchBusy by remember { mutableStateOf(false) }
    var fetchMsg by remember { mutableStateOf("") }

    // Models to offer right now: freshly fetched (if any) for this provider,
    // otherwise the built-in fallback list baked into the app.
    val fetchedForProvider = fetchedModels[provider].orEmpty()
    val modelOptions = fetchedForProvider.ifEmpty { prov.models }
    // v40: the saved model is no longer offered by the provider's live list.
    // Only flagged when we actually have a fetched list to compare against.
    val modelOutdated = fetchedForProvider.isNotEmpty() &&
        model.isNotBlank() && model !in fetchedForProvider

    // Build the config as currently edited, keeping the cached model lists.
    fun currentConfig(): AiConfig {
        val newKeys = config.keys.toMutableMap()
        newKeys[provider] = key.trim()
        return AiConfig(
            provider = provider,
            model = model.trim(),
            keys = newKeys,
            baseUrl = baseUrl.trim(),
            fetchedModels = fetchedModels
        )
    }

    // Fetch the current provider's live model list and cache it. [auto] = silent
    // best-effort refresh (skips when there is no key and never nags on failure).
    fun refreshModels(auto: Boolean) {
        val prov0 = provider
        if (fetchBusy) return
        val hasKey = (config.keys[prov0]?.isNotBlank() == true) || key.isNotBlank()
        if (!hasKey) { if (!auto) fetchMsg = "Anna ensin API-avain, niin voin hakea mallit."; return }
        if (prov0 == "openai_compatible" && baseUrl.isBlank()) {
            if (!auto) fetchMsg = "Anna ensin palvelun osoite."; return
        }
        fetchBusy = true
        if (!auto) fetchMsg = "Haetaan malleja…"
        scope.launch {
            val cfg = currentConfig()
            val result = withContext(Dispatchers.IO) { AiClient.listModels(cfg) }
            fetchBusy = false
            when (result) {
                is ModelsResult.Ok -> {
                    fetchedModels = fetchedModels + (prov0 to result.models)
                    // Only fill in a blank model automatically; never silently replace
                    // a model the user has chosen — the "outdated" notice guides them.
                    if (model.isBlank()) model = result.models.firstOrNull() ?: model
                    fetchMsg = "Päivitetty: ${result.models.size} mallia."
                    onPersist(currentConfig())   // cache to disk without leaving the screen
                }
                is ModelsResult.Err ->
                    if (!auto) fetchMsg = "Mallien haku ei onnistunut (${result.code})."
            }
        }
    }

    // Auto-refresh once when the screen opens / provider changes, if we can.
    LaunchedEffect(provider) { refreshModels(auto = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Teko\u00e4lyasetukset") },
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
                "Valitse teko\u00e4lymalli tilausten tunnistukseen. Avain tallennetaan vain t\u00e4h\u00e4n puhelimeen.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            DropdownField(
                label = "Palveluntarjoaja",
                value = AiProviders.byId(provider)?.label ?: provider,
                options = AiProviders.list.map { it.label },
                editable = false,
                onSelectIndex = { idx ->
                    val p = AiProviders.list[idx]
                    provider = p.id
                    key = config.keys[p.id] ?: ""
                    model = fetchedModels[p.id]?.firstOrNull() ?: p.models.firstOrNull() ?: model
                    fetchMsg = ""
                }
            )
            if (prov.customUrl) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    baseUrl, { baseUrl = it },
                    label = { Text("Palvelun osoite (esim. https://api.openai.com/v1)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(8.dp))
            // Model picker — editable so you can pick a fetched model OR type any id.
            DropdownField(
                label = "Malli",
                value = model,
                options = modelOptions,
                editable = true,
                onValueChange = { model = it },
                onSelectIndex = { idx -> model = modelOptions[idx] }
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { refreshModels(auto = false) }, enabled = !fetchBusy) {
                    if (fetchBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Hae uusimmat mallit")
                }
                if (fetchMsg.isNotBlank()) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        fetchMsg,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "Lista haetaan suoraan palveluntarjoajalta API-avaimella ja tallennetaan puhelimeen. " +
                    "Voit myös kirjoittaa mallin nimen itse.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (modelOutdated) {
                Text(
                    "⚠ Valittu malli \"$model\" ei ole enää tarjoajan listalla. " +
                        "Valitse uusi malli valikosta.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                key, { key = it },
                label = { Text("API-avain") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onSave(currentConfig()) }) { Text("Tallenna") }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("Jaa tilaukset", fontWeight = FontWeight.Bold)
            Text(
                "L\u00e4het\u00e4 viety tilaustiedosto toiseen puhelimeen (Quick Share, Bluetooth) tai " +
                    "ty\u00f6p\u00f6yt\u00e4ohjelmaan (Drive, s\u00e4hk\u00f6posti).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            OutlinedButton(
                onClick = {
                    if (state.liveOrders.isEmpty())
                        Toast.makeText(context, "Ei REKO-tilauksia jaettavaksi", Toast.LENGTH_SHORT).show()
                    else
                        shareJson(
                            context, "kasvila_reko_tilaukset.json",
                            SyncJson.buildOrders(state.liveOrders), "Jaa REKO-tilaukset"
                        )
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Icon(Icons.Filled.Share, null)
                Spacer(Modifier.width(8.dp))
                Text("Jaa REKO-tilaukset (${state.liveOrders.size})")
            }
            OutlinedButton(
                onClick = {
                    if (state.liveDeliveries.isEmpty())
                        Toast.makeText(context, "Ei kuljetustilauksia jaettavaksi", Toast.LENGTH_SHORT).show()
                    else
                        shareJson(
                            context, "kasvila_kuljetukset.json",
                            SyncJson.buildDeliveries(state.liveDeliveries), "Jaa kuljetukset"
                        )
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Icon(Icons.Filled.Share, null)
                Spacer(Modifier.width(8.dp))
                Text("Jaa kuljetukset (${state.liveDeliveries.size})")
            }
            OutlinedButton(
                onClick = {
                    if (state.liveOrders.isEmpty() && state.liveDeliveries.isEmpty())
                        Toast.makeText(context, "Ei mit\u00e4\u00e4n jaettavaa", Toast.LENGTH_SHORT).show()
                    else
                        shareJson(
                            context, "kasvila_reko_kaikki.json",
                            SyncJson.buildBundle(state.liveOrders, state.liveDeliveries, state.localAliases),
                            "Jaa kaikki (tilaukset + kuljetukset)"
                        )
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Icon(Icons.Filled.Share, null)
                Spacer(Modifier.width(8.dp))
                Text("Vie kaikki: tilaukset + kuljetukset (${state.liveOrders.size + state.liveDeliveries.size})")
            }
            Text(
                "Yhdistetty tiedosto: REKO-tilaukset, kotiinkuljetukset ja opitut tuotenimet. " +
                    "Ty\u00f6p\u00f6yt\u00e4ohjelma tuo kaiken yhdell\u00e4 kertaa.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("Muistutukset", fontWeight = FontWeight.Bold)
            Text(
                "Kuljetustilauksille voi asettaa muistutuksen, joka her\u00e4tt\u00e4\u00e4 puhelimen \u00e4\u00e4nell\u00e4 " +
                    "ja ponnahdusikkunalla ennen toimitusta. Salli ilmoitukset, jotta muistutukset n\u00e4kyv\u00e4t.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            OutlinedButton(
                onClick = {
                    onRequestNotifPermission()
                    if (Reminders.hasNotifPermission(context)) {
                        Reminders.showTest(context)
                        Toast.makeText(context, "Testimuistutus l\u00e4hetetty", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Salli ilmoitukset ja paina uudelleen", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Testaa muistutusilmoitus") }
            OutlinedButton(
                onClick = {
                    try {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            )
                        )
                    } catch (e: Exception) {
                        Toast.makeText(context, "Asetuksia ei voitu avata", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text("Avaa sovelluksen asetukset (akku)") }
            Text(
                "Luotettavimmat muistutukset: poista sovellus akun optimoinnista sovelluksen asetuksista.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpenAliases, modifier = Modifier.fillMaxWidth()) {
                Text("Paikalliset aliakset (opitut nimet)")
            }

            if (canRestore) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("Palauta edellinen katalogi", fontWeight = FontWeight.Bold)
                Text(
                    "Kumoaa viimeisimm\u00e4n katalogin tuonnin: palauttaa piirit, tuotteet ja " +
                        "hinnat siihen, mit\u00e4 ne olivat ennen tuontia. Tilauksiisi ei kosketa.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
                OutlinedButton(
                    onClick = { confirmRestore = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Palauta edellinen katalogi") }
            }
        }
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Palauta edellinen katalogi") },
            text = {
                Text(
                    "Palautetaan piirit, tuotteet ja hinnat siihen, mit\u00e4 ne olivat ennen " +
                        "viimeisint\u00e4 tuontia. Tilaukset ja kuljetukset s\u00e4ilyv\u00e4t ennallaan."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    onRestore()
                }) { Text("Palauta") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) { Text("Peruuta") }
            }
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalAliasesView(
    state: AppState,
    onBack: () -> Unit,
    onAddAlias: (String, String) -> Unit,
    onDelete: (String, String) -> Unit,
    onClearProduct: (String) -> Unit
) {
    val nameById = state.products.associate { it.key() to it.name }
    val catalogProducts = state.activeProducts()
    // Only products that actually have local aliases, sorted by product name.
    val entries = state.localAliases.entries
        .filter { it.value.isNotEmpty() }
        .sortedBy { (nameById[it.key] ?: it.key).lowercase() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paikalliset aliakset") },
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
                "N\u00e4m\u00e4 tilausnimet on opetettu vain t\u00e4h\u00e4n puhelimeen. Ne eiv\u00e4t muuta ty\u00f6p\u00f6yt\u00e4ohjelman katalogia.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))

            // ── Add an alias manually ──
            Text("Lis\u00e4\u00e4 alias k\u00e4sin", fontWeight = FontWeight.Bold)
            var selIdx by remember { mutableStateOf(-1) }
            var aliasText by remember { mutableStateOf("") }
            DropdownField(
                label = "Tuote",
                value = if (selIdx in catalogProducts.indices) catalogProducts[selIdx].name else "",
                options = catalogProducts.map { it.name },
                editable = false,
                onSelectIndex = { selIdx = it }
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    aliasText, { aliasText = it },
                    label = { Text("Uusi alias / tilausnimi") },
                    singleLine = true, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val a = aliasText.trim()
                        if (selIdx in catalogProducts.indices && a.isNotBlank()) {
                            onAddAlias(catalogProducts[selIdx].key(), a)
                            aliasText = ""
                        }
                    },
                    enabled = selIdx in catalogProducts.indices && aliasText.isNotBlank()
                ) { Text("Lis\u00e4\u00e4") }
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            if (entries.isEmpty()) {
                Text(
                    "Ei opittuja aliaksia. Kun liit\u00e4t tilauksen ja jokin rivi j\u00e4\u00e4 tunnistamatta, voit opettaa sen tuotteelle \u2013 opitut nimet n\u00e4kyv\u00e4t t\u00e4ss\u00e4.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }
            entries.forEach { entry ->
                val pid = entry.key
                val prodName = nameById[pid] ?: "(poistettu tuote)"
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(prodName, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    TextButton(onClick = { onClearProduct(pid) }) { Text("Poista kaikki") }
                }
                entry.value.forEach { alias ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("\u2022 $alias", modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { onDelete(pid, alias) }) {
                            Icon(Icons.Filled.Delete, "Poista", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(top = 6.dp))
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaySummaryView(
    state: AppState,
    ringId: String,
    date: String,
    onBack: () -> Unit,
    onReceipt: (Order) -> Unit,
    onAnnounce: () -> Unit
) {
    val d = remember(state, ringId, date) { Summaries.day(state, ringId, date) }
    val context = LocalContext.current
    var printMenu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Jakop\u00e4iv\u00e4n kooste")
                        Text("${d.ringName} \u00b7 ${dateHeader(date)}",
                            style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin") }
                },
                actions = {
                    IconButton(onClick = { printMenu = true }) {
                        Icon(Icons.Filled.Print, "Tulosteet")
                    }
                    DropdownMenu(expanded = printMenu, onDismissRequest = { printMenu = false }) {
                        val ring = state.rings.firstOrNull { it.id == ringId }?.name ?: ""
                        val dayDeliveries = Summaries.deliveriesForDay(state, date)
                        DropdownMenuItem(
                            text = { Text("Keräilylista") },
                            onClick = {
                                printMenu = false
                                RekoPdf.deliver(
                                    context,
                                    RekoPdf.pickingList(
                                        context, state, "$ring  ·  $date", d.orders, dayDeliveries),
                                    "Keräilylista", share = false)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Jakelun koonti") },
                            onClick = {
                                printMenu = false
                                RekoPdf.deliver(
                                    context,
                                    RekoPdf.distributionSummary(context, state, ring, date, d.orders),
                                    "Jakelun koonti", share = false)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Asiakkaiden noutoliput") },
                            onClick = {
                                printMenu = false
                                RekoPdf.deliver(
                                    context,
                                    RekoPdf.customerSlips(context, state, ring, date, d.orders),
                                    "Noutoliput", share = false)
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Jaa keräilylista PDF:nä") },
                            onClick = {
                                printMenu = false
                                RekoPdf.deliver(
                                    context,
                                    RekoPdf.pickingList(
                                        context, state, "$ring  ·  $date", d.orders, dayDeliveries),
                                    "Keräilylista", share = true)
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Luo REKO-ilmoitus (Facebook)") },
                            onClick = {
                                printMenu = false
                                onAnnounce()
                            }
                        )
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text("${d.orderCount} tilausta \u00b7 ${d.customerCount} asiakasta",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Kokonaishinta: ${Reko.money(d.totalAmount, state.currency)}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(14.dp))
            Text("Tuotteet yhteens\u00e4", fontWeight = FontWeight.Bold)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (d.productTotals.isEmpty())
                Text("\u2013", color = MaterialTheme.colorScheme.onSurfaceVariant)
            d.productTotals.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("${trimNum(p.qty)} ${p.unit}", modifier = Modifier.width(84.dp),
                        fontWeight = FontWeight.Bold)
                    Text(p.name, modifier = Modifier.weight(1f))
                    Text(Reko.money(p.amount, state.currency), color = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Tilaukset", fontWeight = FontWeight.Bold)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (d.orders.isEmpty())
                Text("Ei tilauksia t\u00e4lle jakop\u00e4iv\u00e4lle.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            d.orders.forEach { o ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(o.customer.ifBlank { "(nimet\u00f6n)" },
                            modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Text(Reko.money(o.total, state.currency), color = MaterialTheme.colorScheme.primary)
                    }
                    o.lines.forEach { l ->
                        Text("   \u2022 ${trimNum(l.qty)} ${Reko.unitFor(l, state.products)}  ${l.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onReceipt(o) }) {
                        Icon(Icons.Filled.Print, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Tulosta kuitti")
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeekSummaryView(
    state: AppState,
    monday: LocalDate,
    onBack: () -> Unit,
    onWeek: (LocalDate) -> Unit,
    onDay: (String, String) -> Unit
) {
    val w = remember(state, monday) { Summaries.week(state, monday) }
    val weekOrders = remember(state, monday) { Summaries.ordersForWeek(state, monday) }
    val weekDeliveries = remember(state, monday) { Summaries.deliveriesForWeek(state, monday) }
    val weekDeliveryTotals = remember(state, monday) { Summaries.deliveryTotalsFor(weekDeliveries, state.products) }
    val weekCombinedTotals = remember(state, monday) { Summaries.combinedTotalsFor(weekOrders, weekDeliveries, state.products) }
    val sunday = monday.plusDays(6)
    val context = LocalContext.current
    var printMenu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Viikkokooste")
                        Text("${monday.dayOfMonth}.${monday.monthValue}. \u2013 ${sunday.dayOfMonth}.${sunday.monthValue}.${sunday.year}",
                            style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin") }
                },
                actions = {
                    IconButton(onClick = { printMenu = true }) {
                        Icon(Icons.Filled.Print, "Tulosteet")
                    }
                    DropdownMenu(expanded = printMenu, onDismissRequest = { printMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Viikkokoonti") },
                            onClick = {
                                printMenu = false
                                RekoPdf.deliver(context, RekoPdf.weekSummary(context, state, monday),
                                    "Viikkokoonti", share = false)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Koko viikon keräilylista") },
                            onClick = {
                                printMenu = false
                                RekoPdf.deliver(
                                    context,
                                    RekoPdf.pickingList(
                                        context, state,
                                        "Viikko $monday \u2013 $sunday", weekOrders, weekDeliveries),
                                    "Keräilylista", share = false)
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Jaa viikkokoonti PDF:nä") },
                            onClick = {
                                printMenu = false
                                RekoPdf.deliver(context, RekoPdf.weekSummary(context, state, monday),
                                    "Viikkokoonti", share = true)
                            }
                        )
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onWeek(monday.minusWeeks(1)) }) { Text("\u2190 Edellinen") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onWeek(monday.plusWeeks(1)) }) { Text("Seuraava \u2192") }
            }
            Text("${w.orderCount} tilausta \u00b7 ${w.distributions.size} jakoa" +
                (if (weekDeliveries.isNotEmpty()) " \u00b7 ${weekDeliveries.size} kuljetusta" else ""),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text((if (weekDeliveries.isEmpty()) "Kokonaishinta: " else "Kokonaishinta (reko): ") +
                Reko.money(w.totalAmount, state.currency),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(14.dp))
            Text("Jaot t\u00e4ll\u00e4 viikolla", fontWeight = FontWeight.Bold)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (w.distributions.isEmpty())
                Text("Ei jakoja t\u00e4ll\u00e4 viikolla.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            w.distributions.forEach { dref ->
                Card(onClick = { onDay(dref.ringId, dref.date) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(dref.ringName, fontWeight = FontWeight.Bold)
                            Text(dateHeader(dref.date), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${dref.orderCount} tilausta", style = MaterialTheme.typography.bodySmall)
                            Text(Reko.money(dref.totalAmount, state.currency),
                                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (weekDeliveries.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Kotiinkuljetukset t\u00e4ll\u00e4 viikolla", fontWeight = FontWeight.Bold)
                Text("${weekDeliveries.size} kuljetusta \u00b7 ${Reko.money(weekDeliveries.sumOf { it.total }, state.currency)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                weekDeliveries.forEach { d -> KoosteDeliveryCard(d, state.currency, state.products, showDate = true) }
            }

            Spacer(Modifier.height(16.dp))
            if (weekDeliveries.isEmpty()) {
                // No home deliveries this week: unchanged single total over REKO orders.
                Text("Tuotteet yhteens\u00e4 (koko viikko)", fontWeight = FontWeight.Bold)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ProductTotalsList(w.productTotals, state.currency)
            } else {
                Text("Tuotteet \u2014 reko-jakelut", fontWeight = FontWeight.Bold)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ProductTotalsList(w.productTotals, state.currency)

                Spacer(Modifier.height(16.dp))
                Text("Tuotteet \u2014 kotiinkuljetukset", fontWeight = FontWeight.Bold)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ProductTotalsList(weekDeliveryTotals, state.currency)

                Spacer(Modifier.height(16.dp))
                Text("Kaikki yhteens\u00e4 (reko + kuljetukset)", fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ProductTotalsList(weekCombinedTotals, state.currency)
            }
        }
    }
}


/** One aggregated product-totals table (qty · name · amount). Shared by every
 *  kooste so REKO, kuljetus and combined blocks render identically. */
@Composable
private fun ProductTotalsList(totals: List<ProductTotal>, currency: String) {
    if (totals.isEmpty())
        Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant)
    totals.forEach { p ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text("${trimNum(p.qty)} ${p.unit}", modifier = Modifier.width(84.dp),
                fontWeight = FontWeight.Bold)
            Text(p.name, modifier = Modifier.weight(1f))
            Text(Reko.money(p.amount, currency), color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Read-only card for one home delivery (kuljetustilaus) inside a kooste.
 *  [showDate] adds the delivery date to the status line for the weekly view,
 *  where deliveries span several days; the daily view leaves it off. */
@Composable
private fun KoosteDeliveryCard(d: Delivery, currency: String, products: List<Product>, showDate: Boolean) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(d.customer.ifBlank { "(nimetön)" }, modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold)
                Text(Reko.money(d.total, currency), color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold)
            }
            val addr = d.deliveryAddress.ifBlank { d.address }
            if (addr.isNotBlank()) {
                Text(addr, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            d.lines.forEach { l ->
                Text("   • ${trimNum(l.qty)} ${Reko.unitFor(l, products)}  ${l.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val bits = buildList {
                if (showDate && d.deliveryDate.isNotBlank()) add("Toimitus ${dateHeader(d.deliveryDate)}")
                if (d.deliveryTime.isNotBlank()) add("klo ${d.deliveryTime.replace('.', ':')}")
                add(Reko.paymentLabel(d.paymentMethod))
                if (d.paid) add("Maksettu")
                if (d.delivered) add("Toimitettu")
            }
            if (bits.isNotEmpty()) {
                Text(bits.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeliverySummaryView(
    state: AppState,
    date: String,
    onBack: () -> Unit,
    onDay: (String) -> Unit,
    onUpdateOrder: (Order) -> Unit,
    onDeleteDay: () -> Unit
) {
    val dayOrders = state.liveOrders.filter { it.date == date && !it.cancelled }
    val byRing = dayOrders.groupBy { it.group_id }
    val notPicked = dayOrders.filter { it.notPicked }
    val dayDeliveries = remember(state, date) { Summaries.deliveriesForDay(state, date) }
    val dayTotals = remember(state, date) { Summaries.totalsFor(dayOrders, state.products) }
    val deliveryTotals = remember(state, date) { Summaries.deliveryTotalsFor(dayDeliveries, state.products) }
    val combinedTotals = remember(state, date) { Summaries.combinedTotalsFor(dayOrders, dayDeliveries, state.products) }
    val theDate = try { LocalDate.parse(date) } catch (e: Exception) { LocalDate.now() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var printMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Jakelukooste")
                        Text(dateHeader(date), style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin") }
                },
                actions = {
                    IconButton(onClick = { printMenu = true }) {
                        Icon(Icons.Filled.Print, "Tulosteet")
                    }
                    DropdownMenu(expanded = printMenu, onDismissRequest = { printMenu = false }) {
                        // The whole day across every ring — the list you load the
                        // car from when two pickups share an evening.
                        DropdownMenuItem(
                            text = { Text("Koko päivän keräilylista") },
                            onClick = {
                                printMenu = false
                                RekoPdf.deliver(
                                    context,
                                    RekoPdf.pickingList(
                                        context, state, dateHeader(date), dayOrders, dayDeliveries),
                                    "Keräilylista", share = false)
                            }
                        )
                        byRing.forEach { (rid, orders) ->
                            val ringName = state.rings.firstOrNull { it.id == rid }?.name ?: rid
                            DropdownMenuItem(
                                text = { Text("Noutoliput: $ringName") },
                                onClick = {
                                    printMenu = false
                                    RekoPdf.deliver(
                                        context,
                                        RekoPdf.customerSlips(context, state, ringName, date, orders),
                                        "Noutoliput", share = false)
                                }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Jaa keräilylista PDF:nä") },
                            onClick = {
                                printMenu = false
                                RekoPdf.deliver(
                                    context,
                                    RekoPdf.pickingList(
                                        context, state, dateHeader(date), dayOrders, dayDeliveries),
                                    "Keräilylista", share = true)
                            }
                        )
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onDay(theDate.minusDays(1).toString()) }) { Text("\u2190 Edellinen") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onDay(theDate.plusDays(1).toString()) }) { Text("Seuraava \u2192") }
            }
            Text("${byRing.size} reko-jakelua t\u00e4n\u00e4 p\u00e4iv\u00e4n\u00e4 \u00b7 ${dayOrders.size} tilausta" +
                (if (dayDeliveries.isNotEmpty()) " \u00b7 ${dayDeliveries.size} kuljetusta" else ""),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            if (dayOrders.isEmpty() && dayDeliveries.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Ei tilauksia eik\u00e4 kuljetuksia t\u00e4lle p\u00e4iv\u00e4lle.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            byRing.forEach { (ringId, orders) ->
                val ringName = state.rings.firstOrNull { it.id == ringId }?.name ?: ringId
                Spacer(Modifier.height(16.dp))
                Text(ringName, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                Text("${orders.size} tilausta \u00b7 ${Reko.money(orders.sumOf { it.total }, state.currency)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                orders.forEach { o -> DeliveryOrderCard(o, state.currency, state.products, onUpdateOrder) }
            }

            if (dayDeliveries.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Kotiinkuljetukset", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                Text("${dayDeliveries.size} kuljetusta · ${Reko.money(dayDeliveries.sumOf { it.total }, state.currency)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                dayDeliveries.forEach { d -> KoosteDeliveryCard(d, state.currency, state.products, showDate = false) }
            }

            Spacer(Modifier.height(20.dp))
            Text("Noutamattomat tilaukset", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (notPicked.isEmpty())
                Text("Ei noutamattomia.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            notPicked.forEach { o ->
                val rn = state.rings.firstOrNull { it.id == o.group_id }?.name ?: ""
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(o.customer.ifBlank { "(nimet\u00f6n)" } + (if (rn.isNotBlank()) "  \u2013 $rn" else ""),
                        modifier = Modifier.weight(1f))
                    Text(Reko.money(o.total, state.currency), color = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(Modifier.height(20.dp))
            if (dayDeliveries.isEmpty()) {
                // No home deliveries this day: unchanged single total over REKO orders.
                Text("Tuotteet yhteens\u00e4 (koko jakelu)", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ProductTotalsList(dayTotals, state.currency)
            } else {
                // REKO orders and home deliveries share this day: show each source, then the
                // combined figure the grower actually prepares from.
                Text("Tuotteet \u2014 reko-jakelut", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ProductTotalsList(dayTotals, state.currency)

                Spacer(Modifier.height(16.dp))
                Text("Tuotteet \u2014 kotiinkuljetukset", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ProductTotalsList(deliveryTotals, state.currency)

                Spacer(Modifier.height(16.dp))
                Text("Kaikki yhteens\u00e4 (reko + kuljetukset)", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ProductTotalsList(combinedTotals, state.currency)
            }
            Spacer(Modifier.height(40.dp))

            if (dayOrders.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Filled.Delete, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Poista koko jakelu (t\u00e4m\u00e4 p\u00e4iv\u00e4)")
                }
                Text(
                    "K\u00e4yt\u00e4 vasta kun jakelu on p\u00e4\u00e4ttynyt, tuotteet jaettu ja tilaukset maksettu. " +
                        "Poistetut tilaukset s\u00e4ilyv\u00e4t ty\u00f6p\u00f6yt\u00e4ohjelmassa, jos ne on jo viety sinne.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(40.dp))
            }
        }
    }

    if (showDeleteConfirm) {
        val unpaid = dayOrders.count { !it.paid }
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Poista koko jakelu?") },
            text = {
                Text(
                    "Poistetaanko pysyv\u00e4sti ${dateHeader(date)} jakelu?\n\n" +
                        "${dayOrders.size} tilausta" +
                        (if (unpaid > 0) ", joista $unpaid maksamatta." else ", kaikki maksettu.") +
                        "\n\nTilaukset s\u00e4ilyv\u00e4t ty\u00f6p\u00f6yt\u00e4ohjelmassa, jos ne on jo viety sinne. " +
                        "T\u00e4t\u00e4 ei voi perua puhelimessa."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDeleteDay()
                }) {
                    Text("Poista", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Peruuta") }
            }
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeliveryOrderCard(o: Order, currency: String, products: List<Product>, onUpdateOrder: (Order) -> Unit) {
    var noteText by remember(o.id) { mutableStateOf(o.note) }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(o.customer.ifBlank { "(nimet\u00f6n)" }, modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold)
                Text(Reko.money(o.total, currency), color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold)
            }
            o.lines.forEachIndexed { idx, l ->
                Row(Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = l.given,
                        onCheckedChange = { chk ->
                            onUpdateOrder(o.copy(
                                lines = o.lines.mapIndexed { i, ln -> if (i == idx) ln.copy(given = chk) else ln },
                                updated_at = Reko.nowIso()
                            ))
                        },
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("${trimNum(l.qty)} ${Reko.unitFor(l, products)}", modifier = Modifier.width(64.dp))
                    Text(l.name, modifier = Modifier.weight(1f))
                    Text(Reko.money(l.qty * l.unit_price, currency),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(
                noteText,
                { noteText = it; onUpdateOrder(o.copy(note = it, updated_at = Reko.nowIso())) },
                label = { Text("Huomio") },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                singleLine = true
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = o.paid,
                    onCheckedChange = { onUpdateOrder(o.copy(paid = it, updated_at = Reko.nowIso())) }
                )
                Text("Maksettu")
                Spacer(Modifier.weight(1f))
                Checkbox(
                    checked = o.notPicked,
                    onCheckedChange = { onUpdateOrder(o.copy(notPicked = it, updated_at = Reko.nowIso())) },
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.error,
                        uncheckedColor = MaterialTheme.colorScheme.error,
                        checkmarkColor = MaterialTheme.colorScheme.onError
                    )
                )
                Text("Ei noudettu", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}


// ── kotiinkuljetukset: list of home-delivery orders ──────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KuljetusView(
    state: AppState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Delivery) -> Unit
) {
    val context = LocalContext.current
    val deliveries = state.liveDeliveries.sortedWith(
        compareBy<Delivery>({ it.deliveryDate.ifBlank { "9999-99-99" } }, { it.customer.lowercase() })
    )

    // Export (transfer to desktop). exportList holds the current selection so the
    // one launcher handles both single-order and batch exports.
    var exportList by remember { mutableStateOf<List<Delivery>>(emptyList()) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(SyncJson.buildDeliveries(exportList).toByteArray())
            }
        }
        Toast.makeText(context, "Vietiin ${exportList.size} kuljetustilausta", Toast.LENGTH_LONG).show()
    }
    fun exportThese(list: List<Delivery>, filename: String) {
        if (list.isEmpty()) {
            Toast.makeText(context, "Ei viet\u00e4vi\u00e4 kuljetustilauksia", Toast.LENGTH_SHORT).show()
            return
        }
        exportList = list
        exportLauncher.launch(filename)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Kotiinkuljetukset")
                        Text("kuljetus", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin") }
                },
                actions = {
                    IconButton(onClick = { exportThese(deliveries, "kasvila_kuljetukset.json") }) {
                        Icon(Icons.Filled.Upload, "Vie kaikki ty\u00f6p\u00f6yt\u00e4ohjelmaan")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize()) {
            Button(onClick = onAdd) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("Uusi kuljetustilaus")
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Yhteens\u00e4 ${deliveries.size} tilausta  \u00b7  ${Reko.money(deliveries.sumOf { it.total }, state.currency)}",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))

            if (deliveries.isEmpty()) {
                Text(
                    "Ei kuljetustilauksia. Paina 'Uusi kuljetustilaus' luodaksesi ensimm\u00e4isen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    deliveries.forEach { d ->
                        DeliveryCard(
                            d = d,
                            currency = state.currency,
                            products = state.products,
                            onClick = { onEdit(d) },
                            onExport = { exportThese(listOf(d), "kasvila_kuljetus_${d.customer.trim().ifBlank { "tilaus" }}.json") }
                        )
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeliveryCard(
    d: Delivery,
    currency: String,
    products: List<Product>,
    onClick: () -> Unit,
    onExport: () -> Unit
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(d.customer.ifBlank { "(nimet\u00f6n)" }, modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold)
                Text(Reko.money(d.total, currency), color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold)
            }
            val addr = d.deliveryAddress.ifBlank { d.address }
            if (addr.isNotBlank()) {
                Text(addr, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (d.lines.isNotEmpty()) {
                Text(
                    d.lines.joinToString("  \u00b7  ") { "${it.name} ${trimNum(it.qty)} ${Reko.unitFor(it, products)}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val bits = buildList {
                    if (d.deliveryDate.isNotBlank()) add("Toimitus ${dateHeader(d.deliveryDate)}")
                    if (d.deliveryTime.isNotBlank()) add("klo ${d.deliveryTime.replace('.', ':')}")
                    add(Reko.paymentLabel(d.paymentMethod))
                    d.reminderHoursBefore?.let { add("Muistutus ${it} h") }
                    if (d.paid) add("Maksettu")
                    if (d.delivered) add("Toimitettu")
                }
                Text(bits.joinToString(" \u00b7 "), modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary)
                TextButton(onClick = onExport) { Text("Vie") }
            }
        }
    }
}

// ── kotiinkuljetus: create / edit one delivery ───────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeliveryEditView(
    state: AppState,
    initial: Delivery?,
    onRequestNotifPermission: () -> Unit,
    onBack: () -> Unit,
    onDelete: (Delivery) -> Unit,
    onSave: (Delivery) -> Unit
) {
    val context = LocalContext.current
    // Only products in use can be added to a new delivery; existing lines still
    // resolve their name and unit against the full catalog.
    val deliveryCatalog = state.activeProducts()
    var customer by remember { mutableStateOf(initial?.customer ?: "") }
    var contact by remember { mutableStateOf(initial?.contact ?: "") }
    var address by remember { mutableStateOf(initial?.address ?: "") }
    var deliveryAddress by remember { mutableStateOf(initial?.deliveryAddress ?: "") }
    var orderDate by remember { mutableStateOf(initial?.orderDate ?: LocalDate.now().toString()) }
    var deliveryDate by remember { mutableStateOf(initial?.deliveryDate ?: "") }
    var deliveryTime by remember { mutableStateOf(initial?.deliveryTime ?: "") }
    var payment by remember { mutableStateOf(initial?.paymentMethod ?: "cash") }
    var paid by remember { mutableStateOf(initial?.paid ?: false) }
    var delivered by remember { mutableStateOf(initial?.delivered ?: false) }
    var reminder by remember { mutableStateOf(initial?.reminderHoursBefore) }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    val lines = remember { mutableStateListOf<Line>().apply { initial?.lines?.let { addAll(it) } } }
    var editLineIndex by remember { mutableStateOf<Int?>(null) }   // v36

    // draft product line — picked from the catalog, quantity via stepper
    var pName by remember { mutableStateOf("") }
    var pUnit by remember { mutableStateOf("kpl") }
    var pPrice by remember { mutableStateOf(0.0) }
    var pQty by remember { mutableStateOf(1.0) }
    var pProductId by remember { mutableStateOf("") }   // v37: keep the catalog id on manual lines

    fun addDraftLine() {
        if (pName.isBlank()) return
        lines.add(Line(pName.trim(), if (pQty <= 0) 1.0 else pQty, pUnit, pPrice, pProductId))
        pName = ""; pUnit = "kpl"; pPrice = 0.0; pQty = 1.0; pProductId = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "Uusi kuljetustilaus" else "Muokkaa kuljetusta") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Takaisin") }
                },
                actions = {
                    if (initial != null) {
                        IconButton(onClick = { onDelete(initial) }) {
                            Icon(Icons.Filled.Delete, "Poista", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(customer, { customer = it }, label = { Text("Tilaajan nimi") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(contact, { contact = it }, label = { Text("Puhelin / s\u00e4hk\u00f6posti (valinnainen)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(address, { address = it }, label = { Text("Osoite") },
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(deliveryAddress, { deliveryAddress = it },
                label = { Text("Toimitusosoite (valinnainen, jos eri kuin osoite)") },
                modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(orderDate, { orderDate = it },
                    label = { Text("Tilausp\u00e4iv\u00e4") }, placeholder = { Text("VVVV-KK-PP") },
                    singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(deliveryDate, { deliveryDate = it },
                    label = { Text("Toimitusp\u00e4iv\u00e4") }, placeholder = { Text("VVVV-KK-PP") },
                    singleLine = true, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(deliveryTime, { deliveryTime = it },
                label = { Text("Toimitusaika (valinnainen, esim. 14:00)") }, placeholder = { Text("TT:MM") },
                singleLine = true, modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(12.dp))
            val reminderValue = if (reminder == null) "Ei muistutusta" else "${reminder} h ennen"
            DropdownField(
                label = "Muistutus ennen toimitusta",
                value = reminderValue,
                options = listOf("Ei muistutusta") + REMINDER_HOURS.map { "$it h ennen" },
                editable = false,
                onSelectIndex = { idx ->
                    reminder = if (idx == 0) null else REMINDER_HOURS[idx - 1]
                    if (reminder != null) onRequestNotifPermission()
                }
            )
            if (reminder != null) {
                val at = Reminders.reminderTime(deliveryDate, deliveryTime, reminder)
                if (at != null) {
                    val timeless = Reminders.parseTimeOrNull(deliveryTime) == null
                    Text(
                        "Muistutus: ${at.dayOfMonth}.${at.monthValue}.${at.year} klo " +
                            "%02d:%02d".format(at.hour, at.minute) +
                            (if (timeless) "  (oletusaika, aseta toimitusaika tarkentaaksesi)" else ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    Text(
                        "Aseta toimitusp\u00e4iv\u00e4, jotta muistutus voidaan ajastaa.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            DropdownField(
                label = "Maksutapa",
                value = Reko.paymentLabel(payment),
                options = PAYMENT_METHODS.map { Reko.paymentLabel(it) },
                editable = false,
                onSelectIndex = { idx -> payment = PAYMENT_METHODS[idx] }
            )

            Spacer(Modifier.height(16.dp))
            Text("Tuoterivit", fontWeight = FontWeight.Bold)
            lines.forEachIndexed { i, ln ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${ln.name}  ${trimNum(ln.qty)} ${Reko.unitFor(ln, state.products)}  \u00d7 ${Reko.money(ln.unit_price, state.currency)}",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(Reko.money(ln.qty * ln.unit_price, state.currency),
                        color = MaterialTheme.colorScheme.primary)
                    IconButton(onClick = { editLineIndex = i }) {
                        Icon(Icons.Filled.Edit, "Muokkaa rivi\u00e4")
                    }
                    IconButton(onClick = { lines.removeAt(i) }) {
                        Icon(Icons.Filled.Delete, "Poista rivi")
                    }
                }
            }
            editLineIndex?.let { idx ->
                if (idx < lines.size) {
                    LineEditDialog(
                        line = lines[idx],
                        products = state.products,
                        currency = state.currency,
                        onSave = { updated -> lines[idx] = updated; editLineIndex = null },
                        onDismiss = { editLineIndex = null }
                    )
                } else {
                    editLineIndex = null
                }
            }

            Spacer(Modifier.height(10.dp))
            DropdownField(
                label = "Valitse tuote",
                value = if (pName.isBlank()) "" else "$pName  (${Reko.money(pPrice, state.currency)} / $pUnit)",
                options = deliveryCatalog.map { "${it.name}  \u2013  ${Reko.money(it.price, state.currency)} / ${it.unit}" },
                editable = false,
                onSelectIndex = { idx ->
                    val pr = deliveryCatalog[idx]
                    pName = pr.name; pUnit = pr.unit; pPrice = pr.price; pProductId = pr.id
                }
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("M\u00e4\u00e4r\u00e4:", modifier = Modifier.padding(end = 8.dp))
                QtyStepper(pQty, pUnit) { pQty = it }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { addDraftLine() }, enabled = pName.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) { Text("Lis\u00e4\u00e4", maxLines = 1, softWrap = false) }
            }
            if (pName.isNotBlank()) {
                Text(
                    "Voit painaa suoraan Tallenna \u2013 valittu tuote lis\u00e4t\u00e4\u00e4n automaattisesti.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(paid, { paid = it }); Text("Maksettu")
                Spacer(Modifier.width(16.dp))
                Checkbox(delivered, { delivered = it }); Text("Toimitettu")
            }
            OutlinedTextField(note, { note = it }, label = { Text("Huomiot (valinnainen)") },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp))

            Spacer(Modifier.height(12.dp))
            val liveTotal = lines.sumOf { it.qty * it.unit_price } +
                (if (pName.isNotBlank()) pQty * pPrice else 0.0)
            Text(
                "Tilauksen summa: ${Reko.money(liveTotal, state.currency)}",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(16.dp))
            Row {
                Button(
                    onClick = {
                        if (customer.isBlank()) {
                            Toast.makeText(context, "Sy\u00f6t\u00e4 tilaajan nimi", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        addDraftLine()   // commit any pending product automatically
                        val d = Delivery(
                            id = initial?.id ?: Reko.newDeliveryId(),
                            customer = customer.trim(),
                            contact = contact.trim(),
                            address = address.trim(),
                            deliveryAddress = deliveryAddress.trim(),
                            orderDate = orderDate.trim(),
                            deliveryDate = deliveryDate.trim(),
                            deliveryTime = deliveryTime.trim(),
                            paymentMethod = payment,
                            paid = paid,
                            delivered = delivered,
                            reminderHoursBefore = reminder,
                            note = note.trim(),
                            updated_at = Reko.nowIso(),
                            lines = lines.toList()
                        )
                        onSave(d)
                    }
                ) { Text("Tallenna") }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = onBack) { Text("Peruuta") }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}
