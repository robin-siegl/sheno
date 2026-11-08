@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.robinsiegl.sheno

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.Reader
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun readLimited(reader: Reader): String {
    val result = StringBuilder()
    val buffer = CharArray(4096)
    while (true) {
        val count = reader.read(buffer)
        if (count < 0) break
        require(result.length + count <= 5_000_000) { "Choose a backup smaller than 5 MB." }
        result.append(buffer, 0, count)
    }
    return result.toString()
}
@Composable fun SettingsScreen(model: Model, vm: ShenoViewModel, permissionTick: Int, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allowed = remember(permissionTick) { ReminderEngine.notificationsAllowed(context) }
    val exact = remember(permissionTick) { ReminderEngine.exactAllowed(context) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.accept(AppIntent.Refresh) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching { val text = vm.export(); withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(text) }
            } }.onSuccess { vm.message("Backup exported.") }.onFailure { vm.message("Could not export the backup. ${it.message.orEmpty()}") }
        }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openInputStream(uri)).bufferedReader().use(::readLimited) } }
                .onSuccess { vm.accept(AppIntent.PreviewImport(it)) }.onFailure { vm.message("Could not read the backup. ${it.message.orEmpty()}") }
        }
    }
    LazyColumn(modifier, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Reminders", style = MaterialTheme.typography.titleLarge) }
        item { Text(if (allowed) "Notifications are enabled" else "Notifications need permission", style = MaterialTheme.typography.titleMedium)
            Text("Delivery also follows your channel, sound, Do Not Disturb and battery settings.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            OutlinedButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && !allowed) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text("Notification settings") }
        }
        item { Text(if (exact) "Precise timing is enabled" else "Allow precise reminder timing", style = MaterialTheme.typography.titleMedium)
            Text(if (exact) "Reminders are scheduled using Android alarms." else "Without this access Android may delay reminders. Your schedules are still saved.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            if (Build.VERSION.SDK_INT >= 31) OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }) { Text("Alarms and reminders access") }
        }
        item { FilledTonalButton(onClick = { ReminderEngine.test(context); if (!allowed) vm.message("Enable notifications before sending a test.") }) { Text("Send test notification") } }
        item { SettingSwitch("Discreet notifications and widget", "Hide medication names and dosage details.", model.preferences.discreet) { vm.accept(AppIntent.Settings(model.preferences.copy(discreet = it))) } }
        item { HorizontalDivider(); Text("Appearance", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp)) }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("System", "Light", "Dark").forEach { theme -> FilterChip(selected = model.preferences.theme == theme, onClick = { vm.accept(AppIntent.Settings(model.preferences.copy(theme = theme))) }, label = { Text(theme) }) }
        } }
        item { SettingSwitch("Use device colors", "Use your phone’s Material You palette.", model.preferences.dynamicColor) { vm.accept(AppIntent.Settings(model.preferences.copy(dynamicColor = it))) } }
        item { HorizontalDivider(); Text("Backup", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
            Text("Your data is stored on this device. Android can include it in encrypted system backups when device backup is enabled. Backup timing is managed by Android.", Modifier.padding(top = 8.dp))
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_PRIVACY_SETTINGS)) }) { Text("Android backup settings") }
        }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { export.launch("sheno-backup-${LocalDate.now()}.json") }, enabled = !model.busy) { Text("Export backup") }
            OutlinedButton(onClick = { importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !model.busy) { Text("Import backup") }
        } }
        item { Text("Import adds new medications and their history. Existing medications and settings are kept. Exported files contain your medication information.", style = MaterialTheme.typography.bodyMedium) }
        item { HorizontalDivider(); Text("About Sheno", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
            Text("Version ${BuildConfig.VERSION_NAME}", Modifier.padding(top = 8.dp))
            Text("Medication schedules, records and stock in one place. Sheno does not provide medical advice.", Modifier.padding(top = 8.dp))
            OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/robin-siegl/sheno/releases"))) }) { Text("GitHub releases") }
            Text("Schedules follow your phone’s local time. After a force stop, open Sheno again to restore reminder scheduling. A powered-off phone cannot deliver reminders.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
