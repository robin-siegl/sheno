package dev.robinsiegl.sheno

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object ReminderEngine {
    private val mutex = Mutex()
    private const val DOSES = "scheduled_medication"
    private const val STOCK = "stock"
    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(DOSES, "Medication reminders", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(STOCK, "Low stock", NotificationManager.IMPORTANCE_DEFAULT))
    }
    fun notificationsAllowed(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    fun exactAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun installMaintenance(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("reminder-maintenance", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReminderMaintenance>(12, TimeUnit.HOURS).build())
    }
    fun open(context: Context, route: String = "today"): PendingIntent = PendingIntent.getActivity(context, route.hashCode(),
        Intent(context, MainActivity::class.java).setData(Uri.parse("sheno://${context.packageName}/$route"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun alarm(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 1,
        Intent(context, ReminderReceiver::class.java).setAction("${context.packageName}.ALARM"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun action(context: Context, key: String, action: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, DoseActionReceiver::class.java).setAction(action).setData(Uri.parse("sheno://${context.packageName}/action/${Uri.encode(key)}/$action"))
            .putExtra("key", key), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    suspend fun slot(context: Context, key: String): Occurrence? = runCatching {
        val parts = key.split('|')
        if (parts.size != 3) return@runCatching null
        val med = context.sheno.repository.medication(parts[0]) ?: return@runCatching null
        Schedule.on(med, LocalDate.parse(parts[1])).firstOrNull { it.time == LocalTime.parse(parts[2]) }
    }.getOrNull()
    suspend fun refresh(context: Context) = mutex.withLock {
        channels(context)
        val repo = context.sheno.repository
        val dao = repo.dao
        val now = Instant.now()
        val meds = repo.medications().filter { !it.archived }
        val logs = dao.logs().map { it.id }.toSet()
        val receipts = dao.receipts().map { it.id }.toMutableSet()
        val snoozes = dao.snoozes().associate { it.id to it.until }
        val manager = context.getSystemService(NotificationManager::class.java)
        // Remove obsolete notifications after logging, deleting, pausing, or changing a schedule.
        manager.activeNotifications.forEach { notification ->
            val key = notification.tag ?: return@forEach
            val valid = if (key.startsWith("stock|")) meds.any { "stock|${it.id}" == key && Schedule.low(it, now) }
                else key == "test" || key !in logs && slot(context, key)?.medication?.reminders == true
            if (!valid) manager.cancel(key, 0)
        }
        val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
        val candidates = mutableListOf<Long>()
        for (med in meds) {
            if (med.reminders) {
                val recent = Schedule.on(med, today.minusDays(1)) + Schedule.on(med, today)
                for (slot in recent) {
                    val trigger = snoozes[slot.key] ?: slot.at.toEpochMilli()
                    if (slot.at.toEpochMilli() < med.createdAt || slot.key in logs || slot.key in receipts) continue
                    if (trigger > now.toEpochMilli()) candidates += trigger
                    else if (now.toEpochMilli() - slot.at.toEpochMilli() < TimeUnit.DAYS.toMillis(1)) {
                        if (showDose(context, slot)) {
                            dao.put(ReceiptRecord(slot.key, med.id, now.toEpochMilli()))
                            dao.deleteSnooze(slot.key)
                            receipts += slot.key
                        }
                    }
                }
                Schedule.next(med, now)?.let { candidates += it.at.toEpochMilli() }
            }
            val stockKey = "stock|${med.id}"
            if (Schedule.low(med, now)) {
                if (stockKey !in receipts) {
                    if (showStock(context, med)) dao.put(ReceiptRecord(stockKey, med.id, now.toEpochMilli()))
                }
            } else {
                dao.deleteReceipt(stockKey)
                manager.cancel(stockKey, 0)
            }
        }
        // A snoozed occurrence can belong to the previous day.
        dao.snoozes().forEach { snooze ->
            val occurrence = slot(context, snooze.id)
            if (occurrence == null || snooze.id in logs || !occurrence.medication.reminders) dao.deleteSnooze(snooze.id)
            else if (snooze.until > now.toEpochMilli()) candidates += snooze.until
            else if (snooze.id !in receipts) {
                if (showDose(context, occurrence)) {
                    dao.put(ReceiptRecord(snooze.id, occurrence.medication.id, now.toEpochMilli()))
                    dao.deleteSnooze(snooze.id)
                }
            }
        }
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = alarm(context)
        alarms.cancel(pending)
        candidates.filter { it > now.toEpochMilli() }.minOrNull()?.let { at ->
            try {
                if (exactAllowed(context)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } catch (_: SecurityException) { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending) }
        }
    }
    private fun post(context: Context, key: String, builder: NotificationCompat.Builder): Boolean {
        if (notificationsAllowed(context)) {
            try {
                val notification = builder.build()
                if (context.getSystemService(NotificationManager::class.java).getNotificationChannel(notification.channelId)?.importance == NotificationManager.IMPORTANCE_NONE) return false
                NotificationManagerCompat.from(context).notify(key, 0, notification)
                return true
            }
            catch (_: SecurityException) { /* Permission can be revoked between check and delivery. */ }
        }
        return false
    }
    private fun showDose(context: Context, slot: Occurrence): Boolean {
        val discreet = context.sheno.settings.state.value.discreet
        val med = slot.medication
        val title = if (discreet) "Medication reminder" else med.name
        val body = if (discreet) "Scheduled for ${slot.time}. Open Sheno for details." else
            "${med.strength.takeIf { it.isNotBlank() }?.plus(", ").orEmpty()}${quantityText(med.dose)} ${med.form.unit}, scheduled ${slot.time}"
        return post(context, slot.key, NotificationCompat.Builder(context, DOSES)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(open(context))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true)
            .addAction(0, "Taken", action(context, slot.key, "TAKEN"))
            .addAction(0, "Snooze 10 min", action(context, slot.key, "SNOOZE"))
            .addAction(0, "Skip", action(context, slot.key, "SKIP")))
    }
    private fun showStock(context: Context, med: Medication): Boolean {
        val discreet = context.sheno.settings.state.value.discreet
        val builder = NotificationCompat.Builder(context, STOCK).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (discreet) "Medication stock is low" else "${med.name}: low stock")
            .setContentText(if (discreet) "Open Sheno to check your inventory." else "${quantityText(med.stock.coerceAtLeast(0))} ${med.form.unit} recorded in stock.")
            .setContentIntent(open(context, "medications")).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true)
        if (validOrderUrl(med.reorderUrl)) builder.addAction(0, "Open order page", OrderActivity.pending(context, med.id))
        return post(context, "stock|${med.id}", builder)
    }
    fun test(context: Context) {
        channels(context)
        post(context, "test", NotificationCompat.Builder(context, DOSES).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sheno reminders are ready").setContentText("This is a test notification.").setAutoCancel(true).setContentIntent(open(context)))
    }
}
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf("${context.packageName}.ALARM", Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) return
        val pending = goAsync()
        context.sheno.scope.launch { try { ReminderEngine.refresh(context); ShenoWidget.update(context) } finally { pending.finish() } }
    }
}
class DoseActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        val action = intent.action ?: return
        if (action !in setOf("TAKEN", "SKIP", "SNOOZE")) return
        val pending = goAsync()
        context.sheno.scope.launch {
            try {
                val repo = context.sheno.repository
                val slot = ReminderEngine.slot(context, key) ?: return@launch
                if (repo.dao.log(key) == null) {
                    if (action == "SNOOZE") {
                        repo.dao.put(SnoozeRecord(key, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10)))
                        repo.dao.deleteReceipt(key)
                    } else repo.record(slot, if (action == "TAKEN") DoseStatus.TAKEN else DoseStatus.SKIPPED)
                }
                context.getSystemService(NotificationManager::class.java).cancel(key, 0)
                ReminderEngine.refresh(context)
                ShenoWidget.update(context)
            } finally { pending.finish() }
        }
    }
}
class ReminderMaintenance(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        ReminderEngine.refresh(applicationContext); ShenoWidget.update(applicationContext); Result.success()
    } catch (_: Exception) { Result.retry() }
}
