@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.robinsiegl.sheno

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.serialization.encodeToString

@Composable fun MedicationEditor(original: Medication?, busy: Boolean, onClose: () -> Unit, onSave: (Medication) -> Unit) {
    val saver = remember { Saver<Medication, String>(save = { backupJson.encodeToString(it) }, restore = { backupJson.decodeFromString(it) }) }
    var draft by rememberSaveable(original?.id, stateSaver = saver) { mutableStateOf(original ?: Medication()) }
    var stockText by rememberSaveable { mutableStateOf(quantityText(draft.stock)) }
    var doseText by rememberSaveable { mutableStateOf(quantityText(draft.dose)) }
    var lowText by rememberSaveable { mutableStateOf(quantityText(draft.lowStock)) }
    var daysText by rememberSaveable { mutableStateOf(draft.lowDays.toString()) }
    var intervalText by rememberSaveable { mutableStateOf(draft.intervalDays.toString()) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var dateDialog by remember { mutableStateOf<String?>(null) }
    var timeDialog by remember { mutableStateOf(false) }
    val quantitiesValid = (original != null || quantity(stockText) != null) && quantity(doseText)?.let { it > 0 } == true && quantity(lowText) != null && daysText.toIntOrNull()?.let { it in 0..90 } == true && intervalText.toIntOrNull()?.let { it in 1..365 } == true
    val value = draft.copy(stock = if (original != null) draft.stock else quantity(stockText) ?: -1, dose = quantity(doseText) ?: 0, lowStock = quantity(lowText) ?: -1,
        lowDays = daysText.toIntOrNull() ?: -1, intervalDays = intervalText.toIntOrNull() ?: 0)
    val error = if (!quantitiesValid) "Check the amounts. Use up to 3 decimal places." else value.validate()
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (original == null) "Add medication" else "Edit medication") },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close editor") } },
            actions = { TextButton(onClick = { attempted = true; if (error == null) onSave(value.copy(name = value.name.trim(), strength = value.strength.trim(), reorderUrl = value.reorderUrl.trim(), times = value.times.sorted())) }, enabled = !busy, modifier = Modifier.testTag("saveMedication")) { Text("Save") } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MedicationIcon(draft, Modifier.size(72.dp))
                Column { Text("Make it easy to recognize", style = MaterialTheme.typography.titleMedium); Text("Choose a shape and colors below.", style = MaterialTheme.typography.bodyMedium) }
            } }
            item { OutlinedTextField(draft.name, { draft = draft.copy(name = it.take(100)) }, Modifier.fillMaxWidth().testTag("medicationName"), label = { Text("Medication name") }, singleLine = true, isError = attempted && draft.name.isBlank()) }
            item { OutlinedTextField(draft.strength, { draft = draft.copy(strength = it.take(100)) }, Modifier.fillMaxWidth(), label = { Text("Strength") }, supportingText = { Text("As written on the package, for example 20 mg.") }, singleLine = true) }
            item { Text("Appearance", style = MaterialTheme.typography.titleLarge)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Form.entries.forEach { form -> FilterChip(selected = draft.form == form, onClick = { draft = draft.copy(form = form) }, label = { Text(form.label) }, leadingIcon = { MedicationIcon(draft.copy(form = form), Modifier.size(26.dp)) }) }
                }
            }
            item { ColorPicker("Medication color", draft.color) { draft = draft.copy(color = it) } }
            if (draft.form == Form.CAPSULE) item { ColorPicker("Second capsule color", draft.secondColor) { draft = draft.copy(secondColor = it) } }
            item { HorizontalDivider(); Text("Amounts", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp)) }
            item { AmountField(doseText, { doseText = it }, "Amount per intake", draft.form.unit) }
            if (original == null) item { AmountField(stockText, { stockText = it }, "Initial stock", draft.form.unit) }
            item { Text("Schedule", style = MaterialTheme.typography.titleLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Frequency.entries.forEach { frequency -> FilterChip(selected = draft.frequency == frequency, onClick = { draft = draft.copy(frequency = frequency) }, label = { Text(frequency.label) }) }
                }
            }
            if (draft.frequency == Frequency.WEEKDAYS) item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..7).forEach { day -> FilterChip(selected = day in draft.weekdays, onClick = { draft = draft.copy(weekdays = if (day in draft.weekdays) draft.weekdays - day else draft.weekdays + day) },
                    label = { Text(java.time.DayOfWeek.of(day).getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)) }) }
            } }
            if (draft.frequency == Frequency.EVERY_N_DAYS) item { OutlinedTextField(intervalText, { intervalText = it }, Modifier.fillMaxWidth(), label = { Text("Repeat every") }, suffix = { Text("days") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), supportingText = { Text("Counted from the start date.") }) }
            if (draft.frequency != Frequency.AS_NEEDED) {
                item { Text("Times", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        draft.times.sorted().forEach { time -> InputChip(selected = false, onClick = { draft = draft.copy(times = draft.times - time) }, label = { Text(time) }, trailingIcon = { Icon(Icons.Default.Close, "Remove $time", Modifier.size(18.dp)) }) }
                        AssistChip(onClick = { timeDialog = true }, label = { Text("Add time") }, leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) }, enabled = draft.times.size < 12)
                    }
                    Text("Times follow your phone’s local time, including after a time zone change.", style = MaterialTheme.typography.bodyMedium)
                }
                item { SettingSwitch("Reminders", "Show a notification at the times you enter.", draft.reminders) { draft = draft.copy(reminders = it) } }
            }
            item { OutlinedButton(onClick = { dateDialog = "start" }, Modifier.fillMaxWidth()) { Text("Start date: ${draft.startDate}") } }
            item { Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { dateDialog = "end" }, Modifier.weight(1f)) { Text(draft.endDate?.let { "End date: $it" } ?: "Add end date") }
                if (draft.endDate != null) IconButton(onClick = { draft = draft.copy(endDate = null) }) { Icon(Icons.Default.Close, "Remove end date") }
            } }
            item { HorizontalDivider(); Text("Low-stock reminder", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp)) }
            item { AmountField(lowText, { lowText = it }, "Notify at or below", draft.form.unit) }
            if (draft.frequency != Frequency.AS_NEEDED) item { OutlinedTextField(daysText, { daysText = it }, Modifier.fillMaxWidth(), label = { Text("Or when estimated supply reaches") }, suffix = { Text("days") }, supportingText = { Text("0 turns off the days-based threshold. Estimates use your schedule.") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) }
            item { Text("Ordering", style = MaterialTheme.typography.titleLarge) }
            item { OutlinedTextField(draft.reorderUrl, { draft = draft.copy(reorderUrl = it.take(2048)) }, Modifier.fillMaxWidth(), label = { Text("Order website (optional)") }, placeholder = { Text("https://") }, supportingText = { Text("Opens your usual ordering process in your browser.") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true,
                isError = draft.reorderUrl.isNotBlank() && !validOrderUrl(draft.reorderUrl)) }
            if (draft.reorderUrl.isNotBlank()) item { OutlinedTextField(draft.orderText, { draft = draft.copy(orderText = it.take(200)) }, Modifier.fillMaxWidth(), label = { Text("Dosage text for ordering (optional)") }, supportingText = { Text("The copy button uses this text, or the strength above if left empty.") }) }
            item { OutlinedTextField(draft.notes, { draft = draft.copy(notes = it.take(2000)) }, Modifier.fillMaxWidth(), label = { Text("Your notes (optional)") }, minLines = 3) }
            if (attempted && error != null) item { Text(error, color = MaterialTheme.colorScheme.error) }
            item { Text("Sheno records the details and schedule you enter. It does not provide medical advice.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    dateDialog?.let { which ->
        val current = if (which == "start") draft.startDate else draft.endDate ?: draft.startDate
        val picker = rememberDatePickerState(initialSelectedDateMillis = LocalDate.parse(current).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(onDismissRequest = { dateDialog = null }, confirmButton = { TextButton(onClick = {
            picker.selectedDateMillis?.let { millis ->
                val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                draft = if (which == "start") draft.copy(startDate = date) else draft.copy(endDate = date)
            }; dateDialog = null
        }) { Text("Set date") } }, dismissButton = { TextButton(onClick = { dateDialog = null }) { Text("Cancel") } }) { DatePicker(picker) }
    }
    if (timeDialog) {
        val picker = rememberTimePickerState(initialHour = 8, initialMinute = 0, is24Hour = true)
        AlertDialog(onDismissRequest = { timeDialog = false }, title = { Text("Add reminder time") }, text = { TimeInput(picker) },
            confirmButton = { TextButton(onClick = { val time = "%02d:%02d".format(Locale.ROOT, picker.hour, picker.minute); draft = draft.copy(times = (draft.times + time).distinct().sorted()); timeDialog = false }) { Text("Add time") } },
            dismissButton = { TextButton(onClick = { timeDialog = false }) { Text("Cancel") } })
    }
}
@Composable fun AmountField(value: String, onChange: (String) -> Unit, label: String, unit: String) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, suffix = { Text(unit) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = value.isNotBlank() && quantity(value) == null)
}
@Composable fun ColorPicker(label: String, selected: Long, onChange: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MedicationColors.forEach { (name, color) ->
                Surface(onClick = { onChange(color) }, shape = CircleShape, color = Color(color),
                    border = BorderStroke(if (selected == color) 3.dp else 1.dp, if (selected == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "$name${if (selected == color) ", selected" else ""}" }) {
                    if (selected == color) Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, null, tint = if (name == "White") Color.Black else Color.White) }
                }
            }
        }
    }
}
@Composable fun SettingSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, onChange)
    }
}
