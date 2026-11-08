package dev.robinsiegl.sheno

import android.app.Application
import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val backupJson = Json { ignoreUnknownKeys = true; prettyPrint = true }
@Entity data class MedicationRecord(@PrimaryKey val id: String, val payload: String)
@Entity data class LogRecord(@PrimaryKey val id: String, val medicationId: String, val payload: String)
@Entity data class SnoozeRecord(@PrimaryKey val id: String, val until: Long)
@Entity data class ReceiptRecord(@PrimaryKey val id: String, val medicationId: String, val at: Long)

@Dao interface ShenoDao {
    @Query("SELECT * FROM MedicationRecord") fun medicationsFlow(): kotlinx.coroutines.flow.Flow<List<MedicationRecord>>
    @Query("SELECT * FROM LogRecord") fun logsFlow(): kotlinx.coroutines.flow.Flow<List<LogRecord>>
    @Query("SELECT * FROM MedicationRecord") suspend fun medications(): List<MedicationRecord>
    @Query("SELECT * FROM MedicationRecord WHERE id = :id") suspend fun medication(id: String): MedicationRecord?
    @Query("SELECT * FROM LogRecord") suspend fun logs(): List<LogRecord>
    @Query("SELECT * FROM LogRecord WHERE id = :id") suspend fun log(id: String): LogRecord?
    @Query("SELECT * FROM SnoozeRecord") suspend fun snoozes(): List<SnoozeRecord>
    @Query("SELECT * FROM ReceiptRecord") suspend fun receipts(): List<ReceiptRecord>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(record: MedicationRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(record: LogRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(record: SnoozeRecord)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(record: ReceiptRecord)
    @Query("DELETE FROM LogRecord WHERE id = :id") suspend fun deleteLog(id: String)
    @Query("DELETE FROM SnoozeRecord WHERE id = :id") suspend fun deleteSnooze(id: String)
    @Query("DELETE FROM ReceiptRecord WHERE id = :id") suspend fun deleteReceipt(id: String)
    @Query("DELETE FROM MedicationRecord WHERE id = :id") suspend fun deleteMedication(id: String)
    @Query("DELETE FROM LogRecord WHERE medicationId = :id") suspend fun deleteHistory(id: String)
    @Query("DELETE FROM ReceiptRecord WHERE medicationId = :id") suspend fun deleteReceipts(id: String)
}
@Database(entities = [MedicationRecord::class, LogRecord::class, SnoozeRecord::class, ReceiptRecord::class], version = 1, exportSchema = true)
abstract class ShenoDatabase : RoomDatabase() { abstract fun dao(): ShenoDao }
fun MedicationRecord.decode(): Medication = backupJson.decodeFromString(payload)
fun LogRecord.decode(): DoseLog = backupJson.decodeFromString(payload)
fun Medication.record() = MedicationRecord(id, backupJson.encodeToString(this))
fun DoseLog.record() = LogRecord(id, medicationId, backupJson.encodeToString(this))

class ShenoRepository(val database: ShenoDatabase) {
    val dao = database.dao()
    val snapshot = combine(dao.medicationsFlow(), dao.logsFlow()) { meds, logs ->
        meds.map { it.decode() }.sortedBy { it.name.lowercase() } to logs.map { it.decode() }.sortedByDescending { it.recordedAt }
    }
    suspend fun medications() = dao.medications().map { it.decode() }
    suspend fun medication(id: String) = dao.medication(id)?.decode()
    suspend fun save(med: Medication) = database.withTransaction {
        require(med.validate() == null) { med.validate() ?: "Check medication details." }
        val previous = medication(med.id)
        // Stock changes go through their own transaction, including while an editor is open.
        dao.put(med.copy(stock = previous?.stock ?: med.stock, createdAt = previous?.createdAt ?: med.createdAt).record())
    }
    suspend fun stock(id: String, amount: Long, add: Boolean) = database.withTransaction {
        val med = requireNotNull(medication(id)) { "Medication no longer exists." }
        require(amount in 0..MAX_QUANTITY)
        val newStock = if (add) med.stock + amount else amount
        require(newStock in -MAX_QUANTITY..MAX_QUANTITY) { "Stock quantity is too large." }
        dao.put(med.copy(stock = newStock).record())
    }
    suspend fun archive(id: String, archived: Boolean) = database.withTransaction {
        medication(id)?.let { dao.put(it.copy(archived = archived).record()) }
    }
    suspend fun delete(id: String) = database.withTransaction {
        dao.deleteHistory(id); dao.deleteReceipts(id); dao.deleteMedication(id)
        dao.snoozes().filter { it.id.startsWith("$id|") }.forEach { dao.deleteSnooze(it.id) }
    }
    suspend fun record(slot: Occurrence, status: DoseStatus): Boolean = database.withTransaction {
        if (dao.log(slot.key) != null) return@withTransaction false
        val med = medication(slot.medication.id) ?: return@withTransaction false
        if (med.archived || Schedule.on(med, slot.date).none { it.key == slot.key }) return@withTransaction false
        insertLog(med, slot.key, med.dose, status, slot.at.toEpochMilli())
        true
    }
    suspend fun manual(id: String, amount: Long) = database.withTransaction {
        val med = requireNotNull(medication(id)) { "Medication no longer exists." }
        require(!med.archived && amount in 1..MAX_QUANTITY)
        insertLog(med, UUID.randomUUID().toString(), amount, DoseStatus.TAKEN, null)
    }
    private suspend fun insertLog(med: Medication, key: String, amount: Long, status: DoseStatus, scheduledAt: Long?) {
        if (status == DoseStatus.TAKEN) {
            require(med.stock - amount >= -MAX_QUANTITY) { "Update the inventory quantity first." }
            dao.put(med.copy(stock = med.stock - amount).record())
        }
        dao.put(DoseLog(key, med.id, med.name, med.strength, med.form.unit, amount, status, System.currentTimeMillis(), scheduledAt).record())
        dao.deleteSnooze(key)
    }
    suspend fun undo(id: String) = database.withTransaction {
        val log = dao.log(id)?.decode() ?: return@withTransaction
        if (log.status == DoseStatus.TAKEN) medication(log.medicationId)?.let { med ->
            require(med.stock + log.amount <= MAX_QUANTITY) { "Check the current stock before undoing this entry." }
            dao.put(med.copy(stock = med.stock + log.amount).record())
        }
        dao.deleteLog(id)
        // Keep the delivery receipt: undoing a log must not immediately ring again.
    }
    suspend fun export(preferences: Preferences): String = database.withTransaction {
        backupJson.encodeToString(Backup(medications = medications(), history = dao.logs().map { it.decode() }, preferences = preferences))
    }
    fun readBackup(text: String): Backup {
        require(text.length <= 5_000_000) { "Choose a backup smaller than 5 MB." }
        return backupJson.decodeFromString<Backup>(text).also { it.validate() }
    }
    suspend fun importBackup(backup: Backup): Int = database.withTransaction {
        backup.validate()
        val existing = medications().map { it.id }.toSet()
        val added = backup.medications.filter { it.id !in existing }
        val ids = added.map { it.id }.toSet()
        added.forEach { dao.put(it.record()) }
        // Snapshot stock already includes these intakes; do not deduct again during restore.
        backup.history.filter { it.medicationId in ids }.forEach { dao.put(it.record()) }
        added.size
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("sheno", Context.MODE_PRIVATE)
    val state = MutableStateFlow(Preferences(prefs.getString("theme", "System") ?: "System", prefs.getBoolean("dynamic", true), prefs.getBoolean("discreet", false)))
    fun set(value: Preferences) {
        require(value.theme in listOf("System", "Light", "Dark"))
        prefs.edit().putString("theme", value.theme).putBoolean("dynamic", value.dynamicColor).putBoolean("discreet", value.discreet).apply()
        state.value = value
    }
}
class ShenoApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database by lazy { Room.databaseBuilder(this, ShenoDatabase::class.java, "sheno.db").build() }
    val repository by lazy { ShenoRepository(database) }
    val settings by lazy { SettingsStore(this) }
    override fun onCreate() {
        super.onCreate()
        ReminderEngine.installMaintenance(this)
    }
}
val Context.sheno: ShenoApplication get() = applicationContext as ShenoApplication
