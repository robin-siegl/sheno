package dev.robinsiegl.sheno

import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ReminderDeliveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @After fun clear() = runBlocking(Dispatchers.IO) {
        context.sheno.database.clearAllTables()
        context.sheno.settings.set(Preferences())
        context.getSystemService(NotificationManager::class.java).cancelAll()
        ReminderEngine.refresh(context)
    }
    @Test fun scheduledNotificationCanRecordAnIntakeWithoutOpeningAnActivity() = runBlocking(Dispatchers.IO) {
        context.sheno.database.clearAllTables()
        if (Build.VERSION.SDK_INT >= 33) android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        ).use { it.readBytes() }
        val scheduled = ZonedDateTime.now().minusMinutes(1).withSecond(0).withNano(0)
        val med = Medication(name = "Example medication", stock = 30 * SCALE,
            times = listOf(scheduled.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))),
            startDate = scheduled.toLocalDate().toString(), createdAt = scheduled.toInstant().toEpochMilli() - 1000)
        val repo = context.sheno.repository
        repo.save(med)
        val slot = Schedule.on(med, scheduled.toLocalDate()).single()
        ReminderEngine.refresh(context)
        assertTrue(repo.dao.receipts().any { it.id == slot.key })
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag == slot.key })
        context.sheno.settings.set(Preferences(discreet = true))
        ReminderEngine.refresh(context)
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single { it.tag == slot.key }.notification
        assertEquals("Medication reminder", notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
        context.sendBroadcast(Intent(context, DoseActionReceiver::class.java).setAction("TAKEN").putExtra("key", slot.key))
        withTimeout(10000) { while (repo.dao.log(slot.key) == null) delay(50) }
        assertEquals(29 * SCALE, repo.medication(med.id)!!.stock)
        assertEquals(DoseStatus.TAKEN, repo.dao.log(slot.key)!!.decode().status)
    }
}
