package fi.kasvila.reko

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The two things the app should do on its own: remind you about money, and keep
 * a copy of itself somewhere that survives losing the phone.
 *
 * Both are per-device settings, so they live in their own little file rather
 * than in [AppState] — a backup folder chosen on this phone is meaningless on
 * the desktop, and syncing it would be noise at best.
 */

@Serializable
data class MaintenancePrefs(
    /** Weekly nag about unpaid orders. */
    val unpaidEnabled: Boolean = false,
    /** 1 = Monday … 7 = Sunday (java.time.DayOfWeek numbering). */
    val unpaidWeekday: Int = 1,
    val unpaidHour: Int = 18,

    /** Nightly copy of the whole state into a folder the user picked. */
    val backupEnabled: Boolean = false,
    /** Persisted SAF tree uri, or "" when no folder has been chosen. */
    val backupFolderUri: String = "",
    /** Shown in settings so the user knows where it is going. */
    val backupFolderLabel: String = "",
    val backupHour: Int = 2,
    val backupKeep: Int = 14,
    val lastBackupAt: String = "",
    val lastBackupError: String = ""
)

class MaintenanceStore(context: Context) {
    private val file = File(context.filesDir, "kasvila_maintenance.json")
    private val json = Json {
        ignoreUnknownKeys = true; encodeDefaults = true
        prettyPrint = true; coerceInputValues = true
    }

    fun load(): MaintenancePrefs = try {
        if (file.exists()) json.decodeFromString(file.readText()) else MaintenancePrefs()
    } catch (e: Exception) {
        MaintenancePrefs()
    }

    fun save(p: MaintenancePrefs) {
        try {
            file.writeText(json.encodeToString(p))
        } catch (_: Exception) {
        }
    }
}

object Maintenance {

    const val CHANNEL_UNPAID = "unpaid_reminders"
    private const val WORK_UNPAID = "unpaid_weekly_check"
    private const val WORK_BACKUP = "auto_backup_daily"

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_UNPAID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_UNPAID,
                "Maksumuistutukset",
                // Deliberately lower than the delivery channel: a weekly note
                // about money should not interrupt anything, and a nag that
                // buzzes gets the whole app muted.
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Viikoittainen muistutus maksamattomista tilauksista" }
        )
    }

    /** Milliseconds from now until the next occurrence of a weekday at an hour. */
    private fun delayToNext(weekday: Int, hour: Int): Long {
        val now = LocalDateTime.now()
        var target = LocalDateTime.of(
            LocalDate.now().with(DayOfWeek.of(weekday.coerceIn(1, 7))),
            LocalTime.of(hour.coerceIn(0, 23), 0)
        )
        if (!target.isAfter(now)) target = target.plusWeeks(1)
        return target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() -
            System.currentTimeMillis()
    }

    /** Milliseconds from now until the next occurrence of an hour of the day. */
    private fun delayToNextDaily(hour: Int): Long {
        val now = LocalDateTime.now()
        var target = LocalDateTime.of(LocalDate.now(), LocalTime.of(hour.coerceIn(0, 23), 0))
        if (!target.isAfter(now)) target = target.plusDays(1)
        return target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() -
            System.currentTimeMillis()
    }

    /**
     * Apply the current settings. Idempotent — safe to call on every launch and
     * after every settings change, which is exactly when it is called.
     */
    fun apply(context: Context, prefs: MaintenancePrefs) {
        val wm = WorkManager.getInstance(context)

        // v57: don't run background maintenance when the battery is critically
        // low — a weekly nag and a nightly backup are never urgent enough to
        // drain the last few percent. WorkManager simply defers them until the
        // battery is no longer low, then runs them normally.
        val batteryFriendly = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        if (prefs.unpaidEnabled) {
            val req = PeriodicWorkRequestBuilder<UnpaidWorker>(7, TimeUnit.DAYS)
                .setInitialDelay(delayToNext(prefs.unpaidWeekday, prefs.unpaidHour), TimeUnit.MILLISECONDS)
                .setConstraints(batteryFriendly)
                .addTag("unpaid_check")
                .build()
            // UPDATE rather than REPLACE: REPLACE cancels and re-enqueues, which
            // resets the schedule every time the app starts and means the check
            // could never actually fire on a phone opened daily.
            wm.enqueueUniquePeriodicWork(WORK_UNPAID, ExistingPeriodicWorkPolicy.UPDATE, req)
        } else {
            wm.cancelUniqueWork(WORK_UNPAID)
        }

        if (prefs.backupEnabled && prefs.backupFolderUri.isNotBlank()) {
            val req = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delayToNextDaily(prefs.backupHour), TimeUnit.MILLISECONDS)
                .setConstraints(batteryFriendly)
                .addTag("auto_backup")
                .build()
            wm.enqueueUniquePeriodicWork(WORK_BACKUP, ExistingPeriodicWorkPolicy.UPDATE, req)
        } else {
            wm.cancelUniqueWork(WORK_BACKUP)
        }
    }

    fun weekdayLabel(d: Int): String = when (d) {
        1 -> "maanantaisin"; 2 -> "tiistaisin"; 3 -> "keskiviikkoisin"; 4 -> "torstaisin"
        5 -> "perjantaisin"; 6 -> "lauantaisin"; else -> "sunnuntaisin"
    }

    // ── backup to a user-chosen folder ──────────────────────────────────────

    /**
     * Write one dated backup into the chosen folder and prune old ones.
     *
     * Uses [DocumentsContract] directly rather than the documentfile library, so
     * no dependency is added for what amounts to three calls. Returns an error
     * string, or "" on success — the caller records it so a folder that has
     * silently stopped working (card removed, permission revoked, Drive signed
     * out) shows up in settings instead of failing invisibly every night.
     */
    fun runBackup(context: Context, prefs: MaintenancePrefs): String {
        val treeUriStr = prefs.backupFolderUri
        if (treeUriStr.isBlank()) return "Kansiota ei ole valittu"
        // v48/v50: when encrypted, get the key from the persistent session
        // (Keystore). Without "stay logged in", a background task has no key and a
        // folder backup would be unencrypted anyway, so skip rather than write an
        // empty/plaintext file.
        val dek = if (SecurityStore(context).hasAccount()) {
            SessionStore(context).restore()
                ?: return "Kansiovarmuuskopio vaatii, että \"Pysy kirjautuneena\" on päällä"
        } else null
        return try {
            val treeUri = Uri.parse(treeUriStr)
            val resolver = context.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            val state = Store(context, dek).load()
            val stamp = Reko.nowIso().replace(":", "-").replace("T", "_")
            val name = "kasvila_reko_$stamp.json"

            val created = DocumentsContract.createDocument(
                resolver, parent, "application/json", name
            ) ?: return "Tiedostoa ei voitu luoda kansioon"

            resolver.openOutputStream(created)?.use { out ->
                out.write(SyncFiles.buildText(state, "backup").toByteArray(Charsets.UTF_8))
            } ?: return "Tiedostoa ei voitu kirjoittaa"

            prune(resolver, treeUri, prefs.backupKeep)
            ""
        } catch (e: SecurityException) {
            "Kansion käyttöoikeus on menetetty — valitse kansio uudelleen"
        } catch (e: Exception) {
            e.message ?: "Tuntematon virhe"
        }
    }

    /** Keep the newest [keep] of our own backups; never touch anything else. */
    private fun prune(resolver: ContentResolver, treeUri: Uri, keep: Int) {
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            val mine = ArrayList<Pair<String, String>>()   // name to documentId
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val nm = c.getString(1) ?: continue
                    // Only files this app wrote. A folder the user also keeps
                    // other things in must come out of this untouched.
                    if (nm.startsWith("kasvila_reko_") && nm.endsWith(".json")) mine.add(nm to id)
                }
            }
            mine.sortByDescending { it.first }          // the name is the timestamp
            for ((_, id) in mine.drop(keep.coerceAtLeast(1))) {
                try {
                    DocumentsContract.deleteDocument(
                        resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    )
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }
}

/** Posts the weekly unpaid notification. */
class UnpaidWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Maintenance.ensureChannel(ctx)
        if (!Reminders.hasNotifPermission(ctx)) return Result.success()

        val dek = if (SecurityStore(ctx).hasAccount()) SessionStore(ctx).restore() else null
        val state = Store(ctx, dek).load()
        val entries = Unpaid.list(state)
        // Nothing outstanding is the normal state. Saying so every week is how a
        // useful reminder becomes one that gets switched off.
        if (entries.isEmpty()) return Result.success()

        val total = entries.sumOf { it.total }
        val text = buildString {
            append("${entries.size} asiakasta, ")
            append(Reko.money(total, state.currency))
            append(" maksamatta.")
        }
        val detail = entries.take(6).joinToString("\n") {
            "• ${it.name}  —  ${Reko.money(it.total, state.currency)}"
        } + if (entries.size > 6) "\n…" else ""

        val openIntent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_unpaid", true)
        }
        val pi = PendingIntent.getActivity(
            ctx, NOTIF_UNPAID_REQ, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(ctx, Maintenance.CHANNEL_UNPAID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Maksamattomia tilauksia")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n\n$detail"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        NotificationManagerCompat.from(ctx).notify(NOTIF_UNPAID_REQ, notif)
        return Result.success()
    }

    companion object {
        const val NOTIF_UNPAID_REQ = 4201
    }
}

/** Writes the nightly backup into the chosen folder. */
class BackupWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val store = MaintenanceStore(ctx)
        val prefs = store.load()
        if (!prefs.backupEnabled || prefs.backupFolderUri.isBlank()) return Result.success()

        val err = Maintenance.runBackup(ctx, prefs)
        store.save(
            prefs.copy(
                lastBackupAt = if (err.isEmpty()) Reko.nowIso() else prefs.lastBackupAt,
                lastBackupError = err
            )
        )
        // Retry rather than fail: a folder on a card that is briefly unmounted,
        // or a cloud app mid-update, is a temporary problem and the backup is
        // worth one more attempt before waiting a whole day.
        return if (err.isEmpty()) Result.success() else Result.retry()
    }
}
