@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.robinsiegl.sheno

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val dateLabel: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
val historyLabel: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)

@Composable fun ShenoScreen(model: Model, vm: ShenoViewModel, permissionTick: Int) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val selected = model.medications.firstOrNull { it.id == model.selectedId }
    if (model.editing) {
        MedicationEditor(model.editor, model.busy, onClose = { vm.accept(AppIntent.CloseEditor) }, onSave = { vm.accept(AppIntent.Save(it)) })
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (selected != null) "Medication" else when (model.page) {
                Page.TODAY -> "Sheno"; Page.MEDICATIONS -> "Medications"; Page.HISTORY -> "History"; Page.SETTINGS -> "Settings"
            }) }, navigationIcon = {
                if (selected != null || model.page == Page.SETTINGS) IconButton(onClick = {
                    if (selected != null) vm.accept(AppIntent.Open(null)) else vm.accept(AppIntent.Navigate(Page.TODAY))
                }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            }, actions = {
                if (selected != null) IconButton(onClick = { vm.accept(AppIntent.Edit(selected)) }) { Icon(Icons.Default.Edit, "Edit medication") }
                else if (model.page != Page.SETTINGS) IconButton(onClick = { vm.accept(AppIntent.Navigate(Page.SETTINGS)) }) { Icon(Icons.Default.Settings, "Settings") }
            })
        },
        bottomBar = {
            if (selected == null && model.page != Page.SETTINGS) NavigationBar {
                listOf(Triple(Page.TODAY, "Today", Icons.Default.Today), Triple(Page.MEDICATIONS, "Medications", Icons.Default.Medication), Triple(Page.HISTORY, "History", Icons.Default.History)).forEach { (page, label, icon) ->
                    NavigationBarItem(selected = model.page == page, onClick = { vm.accept(AppIntent.Navigate(page)) }, icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
        },
        floatingActionButton = {
            if (selected == null && model.page in listOf(Page.TODAY, Page.MEDICATIONS)) ExtendedFloatingActionButton(
                onClick = { vm.accept(AppIntent.Edit(null)) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add medication") }, modifier = Modifier.testTag("addMedication"))
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val contentModifier = Modifier.fillMaxSize().padding(padding)
        if (!model.loaded) Box(contentModifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else if (selected != null) MedicationDetail(selected, model, vm, contentModifier)
        else when (model.page) {
            Page.TODAY -> TodayScreen(model, vm, contentModifier)
            Page.MEDICATIONS -> MedicationList(model, vm, contentModifier)
            Page.HISTORY -> HistoryScreen(model.history, { vm.accept(AppIntent.Undo(it)) }, contentModifier)
            Page.SETTINGS -> SettingsScreen(model, vm, permissionTick, contentModifier)
        }
    }
    model.importPreview?.let { backup ->
        val existing = model.medications.map { it.id }.toSet()
        val added = backup.medications.count { it.id !in existing }
        AlertDialog(onDismissRequest = { vm.accept(AppIntent.CancelImport) }, title = { Text("Import backup?") },
            text = { Text("Add $added new medications and their history. ${backup.medications.size - added} existing medications will be kept as they are. Your current settings will be kept.") },
            confirmButton = { TextButton(onClick = { vm.accept(AppIntent.Import) }, enabled = !model.busy) { Text("Import") } },
            dismissButton = { TextButton(onClick = { vm.accept(AppIntent.CancelImport) }) { Text("Cancel") } })
    }
}

@Composable fun TodayScreen(model: Model, vm: ShenoViewModel, modifier: Modifier) {
    val today = LocalDate.now()
    val active = model.medications.filter { !it.archived }
    val slots = active.flatMap { Schedule.on(it, today) }.sortedBy { it.at }
    val logs = model.history.associateBy { it.id }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Today", style = MaterialTheme.typography.headlineLarge)
            Text(today.format(dateLabel), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        }
        if (active.isEmpty()) item { EmptyCard("Your medication, together", "Add a medication to keep its schedule, reminders and stock in one place.", Icons.Default.Medication) }
        else {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("${slots.count { it.key in logs }} of ${slots.size} recorded", style = MaterialTheme.typography.headlineSmall)
                        Text("Your entered schedule for today", Modifier.padding(top = 4.dp))
                    }
                }
            }
            if (!ReminderEngine.notificationsAllowed(LocalContext.current) || !ReminderEngine.exactAllowed(LocalContext.current)) item {
                OutlinedCard(onClick = { vm.accept(AppIntent.Navigate(Page.SETTINGS)) }) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.NotificationsActive, null)
                        Column { Text("Check reminder setup", fontWeight = FontWeight.Medium); Text("Review notification and timing access in Settings.", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            if (slots.isEmpty()) item { EmptyCard("Nothing scheduled today", "Your medications are available in the Medications tab. You can record an as-needed intake from its details.", Icons.Default.EventAvailable) }
            items(slots, key = { it.key }) { slot -> DoseCard(slot, logs[slot.key], model.busy, vm) }
            val low = active.filter { Schedule.low(it) }
            if (low.isNotEmpty()) item { Text("Stock to check", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp)) }
            items(low, key = { "stock-${it.id}" }) { MedicationRow(it, { vm.accept(AppIntent.Open(it.id)) }) }
        }
        item { Spacer(Modifier.height(88.dp)) }
    }
}
@Composable fun DoseCard(slot: Occurrence, log: DoseLog?, busy: Boolean, vm: ShenoViewModel) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(slot.time.toString(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.widthIn(min = 64.dp))
                MedicationIcon(slot.medication)
                Column(Modifier.weight(1f).clickable { vm.accept(AppIntent.Open(slot.medication.id)) }) {
                    Text(slot.medication.name, style = MaterialTheme.typography.titleMedium)
                    Text(listOf(slot.medication.strength, "${quantityText(slot.medication.dose)} ${slot.medication.form.unit}").filter { it.isNotBlank() }.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (log != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (log.status == DoseStatus.TAKEN) Icons.Default.CheckCircle else Icons.Default.RemoveCircleOutline, null, Modifier.size(20.dp))
                Text(if (log.status == DoseStatus.TAKEN) "Taken" else "Skipped", Modifier.padding(start = 8.dp).weight(1f))
                TextButton(onClick = { vm.accept(AppIntent.Undo(log.id)) }, enabled = !busy) { Text("Undo") }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (slot.at < Instant.now()) "Not recorded" else "Scheduled", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.accept(AppIntent.Record(slot, DoseStatus.SKIPPED)) }, enabled = !busy) { Text("Skip") }
                FilledTonalButton(onClick = { vm.accept(AppIntent.Record(slot, DoseStatus.TAKEN)) }, enabled = !busy) { Text("Taken") }
            }
        }
    }
}
@Composable fun MedicationList(model: Model, vm: ShenoViewModel, modifier: Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var archived by rememberSaveable { mutableStateOf(false) }
    val filtered = model.medications.filter { it.archived == archived && (it.name.contains(query, true) || it.strength.contains(query, true)) }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search medications") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !archived, onClick = { archived = false }, label = { Text("Active") })
            FilterChip(selected = archived, onClick = { archived = true }, label = { Text("Archived") })
        } }
        if (filtered.isEmpty()) item { EmptyCard(if (query.isNotBlank()) "No matching medications" else if (archived) "No archived medications" else "Your medication list starts here", "Keep names, strengths, schedules and stock together.", Icons.Default.Medication) }
        items(filtered, key = { it.id }) { med -> MedicationRow(med, { vm.accept(AppIntent.Open(med.id)) }) }
        item { Spacer(Modifier.height(88.dp)) }
    }
}
@Composable fun MedicationRow(med: Medication, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            MedicationIcon(med)
            Column(Modifier.weight(1f)) {
                Text(med.name, style = MaterialTheme.typography.titleMedium)
                if (med.strength.isNotBlank()) Text(med.strength, style = MaterialTheme.typography.bodyMedium)
                Text(if (med.archived) "Archived" else "${quantityText(med.stock.coerceAtLeast(0))} ${med.form.unit} in stock", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}
@Composable fun MedicationDetail(med: Medication, model: Model, vm: ShenoViewModel, modifier: Modifier) {
    val context = LocalContext.current
    var quantityAction by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val days = Schedule.daysRemaining(med, Instant.now())
    LazyColumn(modifier, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MedicationIcon(med, Modifier.size(96.dp))
            Text(med.name, style = MaterialTheme.typography.headlineMedium)
            if (med.strength.isNotBlank()) Text(med.strength, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (med.archived) "Archived" else med.form.label, style = MaterialTheme.typography.bodyLarge)
        } }
        item { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Inventory", style = MaterialTheme.typography.titleMedium)
                Text("${quantityText(med.stock.coerceAtLeast(0))} ${med.form.unit}", style = MaterialTheme.typography.headlineMedium)
                if (med.stock < 0) Text("Inventory needs updating.")
                else if (days != null) Text(if (days == 0) "Estimated to run out today" else "Estimated to last $days days")
                if (days != null) Text("Based on your entered schedule and recorded stock.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { quantityAction = "add" }, enabled = !model.busy) { Text("Add package") }
                    TextButton(onClick = { quantityAction = "adjust" }, enabled = !model.busy) { Text("Adjust stock") }
                }
            }
        } }
        if (!med.archived) item { FilledTonalButton(onClick = { quantityAction = "log" }, Modifier.fillMaxWidth(), enabled = !model.busy) { Icon(Icons.Default.AddTask, null); Spacer(Modifier.width(8.dp)); Text("Record an intake") } }
        item { Text("Schedule", style = MaterialTheme.typography.titleLarge)
            Text(scheduleDescription(med), Modifier.padding(top = 8.dp))
            Text("${quantityText(med.dose)} ${med.form.unit} per intake", Modifier.padding(top = 4.dp))
            Text("Starts ${med.startDate}${med.endDate?.let { ", ends $it" }.orEmpty()}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            Text(if (med.reminders && med.frequency != Frequency.AS_NEEDED) "Reminders enabled" else "Reminders off", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }
        item { Text("Low-stock reminder", style = MaterialTheme.typography.titleLarge)
            Text("At ${quantityText(med.lowStock)} ${med.form.unit} or fewer${if (med.lowDays > 0) ", or ${med.lowDays} estimated days remaining" else ""}", Modifier.padding(top = 8.dp))
        }
        if (med.reorderUrl.isNotBlank()) item { Text("Ordering", style = MaterialTheme.typography.titleLarge)
            Text(med.reorderUrl, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { context.startActivity(OrderActivity.intent(context, med.id)) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.OpenInBrowser, null); Spacer(Modifier.width(8.dp)); Text("Open order page") }
            Text("Copy a detail, then paste it into the website. Toolbar buttons depend on your browser.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copyMedicationText(context, med.name); vm.message("Medication name copied.") }) { Text("Copy name") }
                OutlinedButton(onClick = { copyMedicationText(context, med.orderText.ifBlank { med.strength }); vm.message("Dosage text copied.") }) { Text("Copy dosage") }
            }
        }
        if (med.notes.isNotBlank()) item { Text("Your notes", style = MaterialTheme.typography.titleLarge); Text(med.notes, Modifier.padding(top = 8.dp)) }
        item { HorizontalDivider(); TextButton(onClick = { vm.accept(AppIntent.Archive(med.id, !med.archived)) }, enabled = !model.busy) { Text(if (med.archived) "Restore medication" else "Archive medication") }
            TextButton(onClick = { deleting = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete medication") }
        }
    }
    quantityAction?.let { action ->
        QuantityDialog(med, action, onDismiss = { quantityAction = null }) { amount ->
            if (action == "log") vm.accept(AppIntent.Manual(med.id, amount)) else vm.accept(AppIntent.Stock(med.id, amount, action == "add"))
            quantityAction = null
        }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete ${med.name}?") },
        text = { Text("This permanently deletes the medication, its schedule and its intake history. Archiving keeps that history.") },
        confirmButton = { TextButton(onClick = { deleting = false; vm.accept(AppIntent.Delete(med.id)) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
}
fun scheduleDescription(med: Medication): String = when (med.frequency) {
    Frequency.AS_NEEDED -> "As needed, recorded manually"
    Frequency.DAILY -> "Every day at ${med.times.joinToString(", ")}"
    Frequency.EVERY_N_DAYS -> "Every ${med.intervalDays} days at ${med.times.joinToString(", ")}"
    Frequency.WEEKDAYS -> med.weekdays.sorted().joinToString(", ") { java.time.DayOfWeek.of(it).getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH) } + " at " + med.times.joinToString(", ")
}
@Composable fun QuantityDialog(med: Medication, action: String, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var text by rememberSaveable { mutableStateOf(quantityText(when (action) { "log" -> med.dose; "adjust" -> med.stock.coerceAtLeast(0); else -> 30 * SCALE })) }
    val amount = quantity(text)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(when (action) { "log" -> "Record an intake"; "adjust" -> "Adjust stock"; else -> "Add a package" }) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (action == "log" && med.frequency != Frequency.AS_NEEDED) Text("This adds a separate history entry. Record a scheduled intake from Today.")
            if (action == "add") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(28, 30).forEach { count -> SuggestionChip(onClick = { text = count.toString() }, label = { Text(count.toString()) }) }
                SuggestionChip(onClick = { text = "" }, label = { Text("Custom") })
            }
            OutlinedTextField(text, { text = it }, label = { Text(if (action == "adjust") "Current stock" else "Amount") }, suffix = { Text(med.form.unit) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = amount == null)
        } },
        confirmButton = { TextButton(onClick = { amount?.let(onConfirm) }, enabled = amount != null && (action == "adjust" || amount > 0)) { Text(if (action == "log") "Record taken" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
@Composable fun HistoryScreen(logs: List<DoseLog>, undo: (String) -> Unit, modifier: Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (logs.isEmpty()) item { EmptyCard("Your record starts here", "Intakes you mark as taken or skipped will appear here. You can undo an entry to correct it.", Icons.Default.History) }
        items(logs, key = { it.id }) { log -> Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(log.name, style = MaterialTheme.typography.titleMedium)
                if (log.strength.isNotBlank()) Text(log.strength, style = MaterialTheme.typography.bodyMedium)
                Text("${if (log.status == DoseStatus.TAKEN) "Taken" else "Skipped"}: ${quantityText(log.amount)} ${log.unit}")
                Text("Recorded ${Instant.ofEpochMilli(log.recordedAt).atZone(ZoneId.systemDefault()).format(historyLabel)}", style = MaterialTheme.typography.bodyMedium)
                log.scheduledAt?.let { Text("Scheduled ${Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(historyLabel)}", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { undo(log.id) }) { Text("Undo entry") }
            }
        } }
    }
}
@Composable fun EmptyCard(title: String, body: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(body, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
