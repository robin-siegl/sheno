package dev.robinsiegl.sheno

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Page { TODAY, MEDICATIONS, HISTORY, SETTINGS }
data class Model(
    val medications: List<Medication> = emptyList(), val history: List<DoseLog> = emptyList(),
    val preferences: Preferences = Preferences(), val loaded: Boolean = false,
    val page: Page = Page.TODAY, val selectedId: String? = null,
    val editor: Medication? = null, val editing: Boolean = false,
    val busy: Boolean = false, val importPreview: Backup? = null,
)
sealed interface AppIntent {
    data class Navigate(val page: Page) : AppIntent
    data class Open(val id: String?) : AppIntent
    data class Edit(val medication: Medication?) : AppIntent
    data object CloseEditor : AppIntent
    data class Save(val medication: Medication) : AppIntent
    data class Record(val slot: Occurrence, val status: DoseStatus) : AppIntent
    data class Manual(val id: String, val amount: Long) : AppIntent
    data class Stock(val id: String, val amount: Long, val add: Boolean) : AppIntent
    data class Undo(val logId: String) : AppIntent
    data class Archive(val id: String, val archived: Boolean) : AppIntent
    data class Delete(val id: String) : AppIntent
    data class Settings(val value: Preferences) : AppIntent
    data class PreviewImport(val text: String) : AppIntent
    data object CancelImport : AppIntent
    data object Import : AppIntent
    data object Refresh : AppIntent
}
class ShenoViewModel(private val app: ShenoApplication) : ViewModel() {
    private val mutable = MutableStateFlow(Model())
    val model = mutable.asStateFlow()
    private val messagesChannel = Channel<String>(Channel.BUFFERED)
    val messages = messagesChannel.receiveAsFlow()
    init {
        viewModelScope.launch { app.repository.snapshot.collect { (meds, logs) -> mutable.update { it.copy(medications = meds, history = logs, loaded = true) } } }
        viewModelScope.launch { app.settings.state.collect { prefs -> mutable.update { it.copy(preferences = prefs) } } }
    }
    fun message(text: String) { viewModelScope.launch { messagesChannel.send(text) } }
    fun accept(intent: AppIntent) {
        when (intent) {
            is AppIntent.Navigate -> mutable.update { it.copy(page = intent.page, selectedId = null) }
            is AppIntent.Open -> mutable.update { it.copy(selectedId = intent.id) }
            is AppIntent.Edit -> mutable.update { it.copy(editor = intent.medication, editing = true) }
            AppIntent.CloseEditor -> mutable.update { it.copy(editing = false) }
            AppIntent.CancelImport -> mutable.update { it.copy(importPreview = null) }
            is AppIntent.PreviewImport -> viewModelScope.launch {
                runCatching { app.repository.readBackup(intent.text) }
                    .onSuccess { backup -> mutable.update { it.copy(importPreview = backup) } }
                    .onFailure { message(it.message ?: "Could not read this backup.") }
            }
            is AppIntent.Settings -> {
                app.settings.set(intent.value)
                perform { }
            }
            else -> perform {
                when (intent) {
                    is AppIntent.Save -> {
                        app.repository.save(intent.medication)
                        mutable.update { it.copy(editing = false, selectedId = intent.medication.id) }
                    }
                    is AppIntent.Record -> app.repository.record(intent.slot, intent.status)
                    is AppIntent.Manual -> { app.repository.manual(intent.id, intent.amount); message("Intake recorded.") }
                    is AppIntent.Stock -> app.repository.stock(intent.id, intent.amount, intent.add)
                    is AppIntent.Undo -> app.repository.undo(intent.logId)
                    is AppIntent.Archive -> app.repository.archive(intent.id, intent.archived)
                    is AppIntent.Delete -> { app.repository.delete(intent.id); mutable.update { it.copy(selectedId = null) } }
                    AppIntent.Import -> {
                        val backup = mutable.value.importPreview ?: return@perform
                        val count = app.repository.importBackup(backup)
                        mutable.update { it.copy(importPreview = null) }
                        message("Imported $count medication${if (count == 1) "" else "s"}.")
                    }
                    else -> Unit
                }
            }
        }
    }
    private fun perform(block: suspend () -> Unit) = viewModelScope.launch {
        mutable.update { it.copy(busy = true) }
        try {
            block()
            ReminderEngine.refresh(app)
            ShenoWidget.update(app)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            message(error.message ?: "The change could not be saved.")
        } finally { mutable.update { it.copy(busy = false) } }
    }
    suspend fun export(): String = app.repository.export(mutable.value.preferences)
}
