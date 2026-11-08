package dev.robinsiegl.sheno

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.launch

class ShenoWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        context.sheno.scope.launch { try { update(context) } finally { pending.finish() } }
    }
    companion object {
        suspend fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ShenoWidget::class.java))
            if (ids.isEmpty()) return
            val repo = context.sheno.repository
            val meds = repo.medications().filter { !it.archived }
            val logs = repo.dao.logs().map { it.id }.toSet()
            val slots = meds.flatMap { Schedule.on(it, LocalDate.now()) }.sortedBy { it.at }
            val remaining = slots.filter { it.key !in logs }
            val discreet = context.sheno.settings.state.value.discreet
            val body = if (meds.isEmpty()) "Add your first medication in Sheno." else if (remaining.isEmpty()) {
                meds.mapNotNull { Schedule.next(it, Instant.now()) }.minByOrNull { it.at }?.let {
                    if (discreet) "Next scheduled: ${it.date}, ${it.time}" else "Next: ${it.medication.name}\n${it.date}, ${it.time}"
                } ?: "Nothing scheduled. Open Sheno for your medication list."
            } else remaining.take(3).joinToString("\n") { if (discreet) "${it.time}  Scheduled medication" else "${it.time}  ${it.medication.name}" }
            val views = RemoteViews(context.packageName, R.layout.widget_sheno)
            views.setTextViewText(R.id.widget_title, "${slots.size - remaining.size} of ${slots.size} recorded")
            views.setTextViewText(R.id.widget_body, body)
            views.setOnClickPendingIntent(R.id.widget_root, ReminderEngine.open(context))
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }
}
