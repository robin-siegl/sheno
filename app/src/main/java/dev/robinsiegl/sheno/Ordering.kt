package dev.robinsiegl.sheno

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
import android.widget.RemoteViews
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OrderActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("medicationId") ?: run { finish(); return }
        sheno.scope.launch {
            val med = sheno.repository.medication(id)
            withContext(Dispatchers.Main) {
                if (med == null || !validOrderUrl(med.reorderUrl)) { finish(); return@withContext }
                val toolbar = RemoteViews(packageName, R.layout.order_toolbar)
                val action = Intent(this@OrderActivity, OrderCopyReceiver::class.java).putExtra("medicationId", id)
                    .setData(Uri.parse("sheno://$packageName/order/${Uri.encode(id)}"))
                val callback = PendingIntent.getBroadcast(this@OrderActivity, 0, action, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                val customTab = CustomTabsIntent.Builder().setShowTitle(true)
                    .setSecondaryToolbarViews(toolbar, intArrayOf(R.id.copy_name, R.id.copy_strength, R.id.copy_both), callback)
                    .setShareState(CustomTabsIntent.SHARE_STATE_OFF).build()
                try { customTab.launchUrl(this@OrderActivity, Uri.parse(med.reorderUrl)) }
                catch (_: android.content.ActivityNotFoundException) { Toast.makeText(this@OrderActivity, "Install a browser to open this page.", Toast.LENGTH_LONG).show() }
                finish()
            }
        }
    }
    companion object {
        fun intent(context: Context, id: String) = Intent(context, OrderActivity::class.java).putExtra("medicationId", id)
        fun pending(context: Context, id: String): PendingIntent = PendingIntent.getActivity(context, 0,
            intent(context, id).setData(Uri.parse("sheno://${context.packageName}/order/${Uri.encode(id)}")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
class OrderCopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("medicationId") ?: return
        val viewId = intent.getIntExtra(CustomTabsIntent.EXTRA_REMOTEVIEWS_CLICKED_ID, -1)
        if (viewId !in listOf(R.id.copy_name, R.id.copy_strength, R.id.copy_both)) return
        val pending = goAsync()
        context.sheno.scope.launch {
            try {
                val med = context.sheno.repository.medication(id) ?: return@launch
                val strength = med.orderText.ifBlank { med.strength }
                val text = when (viewId) { R.id.copy_name -> med.name; R.id.copy_strength -> strength; else -> listOf(med.name, strength).filter { it.isNotBlank() }.joinToString(" ") }
                withContext(Dispatchers.Main) { copyMedicationText(context, text) }
            } finally { pending.finish() }
        }
    }
}
fun copyMedicationText(context: Context, text: String) {
    val clip = ClipData.newPlainText("Medication details", text)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}
