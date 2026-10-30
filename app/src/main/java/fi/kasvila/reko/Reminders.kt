package fi.kasvila.reko

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Local, offline delivery reminders. One high-importance notification channel gives
 * sound + vibration + heads-up popup in one go. Scheduling uses WorkManager, which
 * survives reboots on its own and needs no exact-alarm or boot permission. Exact
 * minute timing isn't important for 12/24/48/96 h lead times, so this is the
 * lowest-friction, most reliable approach.
 */
object Reminders {
    const val CHANNEL_ID = "delivery_reminders"
    const val DEFAULT_HOUR = 9            // assumed delivery hour when none is set
    private const val WORK_PREFIX = "delivery_reminder_"
    private const val TEST_NOTIF_ID = 1

    /** Create the notification channel once (required on API 26+). */
    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Kuljetusmuistutukset",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Muistutukset kotiinkuljetusten toimituksista"
            enableVibration(true)
        }
        mgr.createNotificationChannel(channel)
    }

    fun hasNotifPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Tolerant time parser: accepts "14:00", "14.00", "9:5", "14". Null if blank/invalid. */
    fun parseTimeOrNull(s: String): LocalTime? {
        val t = s.trim().replace('.', ':')
        if (t.isBlank()) return null
        val parts = t.split(":")
        return try {
            val h = parts[0].toInt()
            val m = if (parts.size > 1 && parts[1].isNotBlank()) parts[1].toInt() else 0
            if (h in 0..23 && m in 0..59) LocalTime.of(h, m) else null
        } catch (e: Exception) {
            null
        }
    }

    /** When a delivery's reminder should fire, or null if no reminder / bad date. */
    fun reminderTime(dateStr: String, timeStr: String, hours: Int?): LocalDateTime? {
        val hrs = hours ?: return null
        val date = try { LocalDate.parse(dateStr) } catch (e: Exception) { return null }
        val time = parseTimeOrNull(timeStr) ?: LocalTime.of(DEFAULT_HOUR, 0)
        return LocalDateTime.of(date, time).minusHours(hrs.toLong())
    }

    fun reminderTime(d: Delivery): LocalDateTime? =
        reminderTime(d.deliveryDate, d.deliveryTime, d.reminderHoursBefore)

    private fun workName(id: String) = WORK_PREFIX + id

    /**
     * (Re)schedule one delivery's reminder. Cancels the old one and skips past times.
     *
     * [replace] = true (an edit or a new delivery) REPLACEs any existing schedule so
     * a changed date/time takes effect. [replace] = false (the launch re-sync) uses
     * KEEP: an already-scheduled reminder is left untouched — WorkManager persists it
     * across reboots — and only a missing one is created. That makes opening the app
     * essentially free instead of cancelling and re-enqueueing every reminder each time.
     */
    fun schedule(context: Context, d: Delivery, replace: Boolean = true) {
        val wm = WorkManager.getInstance(context)
        val at = reminderTime(d)
        if (at == null) {
            wm.cancelUniqueWork(workName(d.id)); return
        }
        val atMs = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val delay = atMs - System.currentTimeMillis()
        if (delay <= 0L) {
            wm.cancelUniqueWork(workName(d.id)); return   // reminder time already passed
        }
        val data = workDataOf(
            "deliveryId" to d.id,
            "customer" to d.customer,
            "deliveryDate" to d.deliveryDate,
            "deliveryTime" to d.deliveryTime,
            "hours" to (d.reminderHoursBefore ?: 0)
        )
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .addTag("delivery_reminder")
            .build()
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        wm.enqueueUniqueWork(workName(d.id), policy, req)
    }

    fun cancel(context: Context, deliveryId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(deliveryId))
    }

    /**
     * Re-sync all reminders to the current delivery list (idempotent, safe on start).
     * Uses KEEP (replace = false): only fills in reminders WorkManager may have lost
     * (after a reboot or reinstall) and leaves existing ones alone, so a normal launch
     * does no work. Edits go through [schedule] directly, which REPLACEs.
     */
    fun syncAll(context: Context, deliveries: List<Delivery>) {
        for (d in deliveries) schedule(context, d, replace = false)
    }

    /** Fire a test notification immediately so the user can confirm sound/popup work. */
    fun showTest(context: Context) {
        ensureChannel(context)
        if (!hasNotifPermission(context)) return
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Testimuistutus")
            .setContentText("N\u00e4in muistutus n\u00e4kyy \u2013 \u00e4\u00e4ni, v\u00e4rin\u00e4 ja ponnahdusikkuna.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(TEST_NOTIF_ID, notif)
    }

    fun hoursLabel(h: Int): String = when (h) {
        12 -> "12 tunnin"
        24 -> "vuorokauden"
        48 -> "kahden vuorokauden"
        96 -> "nelj\u00e4n vuorokauden"
        else -> "$h tunnin"
    }
}

/** Posts the reminder notification when its scheduled time arrives. */
class ReminderWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val id = inputData.getString("deliveryId") ?: return Result.success()
        val customer = inputData.getString("customer") ?: ""
        val date = inputData.getString("deliveryDate") ?: ""
        val time = inputData.getString("deliveryTime") ?: ""
        val hours = inputData.getInt("hours", 0)

        Reminders.ensureChannel(ctx)
        if (!Reminders.hasNotifPermission(ctx)) return Result.success()

        val openIntent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_delivery_id", id)
        }
        val pi = PendingIntent.getActivity(
            ctx, id.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val whenTxt = buildString {
            if (date.isNotBlank()) append(date)
            if (time.isNotBlank()) append(" klo ").append(time.replace('.', ':'))
        }.trim()
        val text = buildString {
            append(if (customer.isBlank()) "Kuljetustilaus" else customer)
            append(" \u2013 toimitus ").append(Reminders.hoursLabel(hours)).append(" kuluttua")
            if (whenTxt.isNotBlank()) append(" (").append(whenTxt).append(")")
        }

        val notif = NotificationCompat.Builder(ctx, Reminders.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Kuljetus l\u00e4hestyy")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        NotificationManagerCompat.from(ctx).notify(id.hashCode(), notif)
        return Result.success()
    }
}
