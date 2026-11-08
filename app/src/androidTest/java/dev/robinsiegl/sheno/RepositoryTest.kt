package dev.robinsiegl.sheno

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RepositoryTest {
    private lateinit var db: ShenoDatabase
    private lateinit var repo: ShenoRepository
    private lateinit var med: Medication
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, ShenoDatabase::class.java).build()
        repo = ShenoRepository(db)
        med = Medication(name = "Sample", stock = 30 * SCALE)
        repo.save(med)
    }
    @After fun close() { db.close() }
    @Test fun repeatedNotificationActionDeductsOnlyOnce() = runBlocking {
        val slot = Schedule.on(med, LocalDate.now()).single()
        assertTrue(repo.record(slot, DoseStatus.TAKEN))
        assertFalse(repo.record(slot, DoseStatus.TAKEN))
        assertEquals(29 * SCALE, repo.medication(med.id)!!.stock)
        assertEquals(1, repo.dao.logs().size)
        repo.undo(slot.key)
        assertEquals(30 * SCALE, repo.medication(med.id)!!.stock)
    }
    @Test fun skippingDoesNotDeductAndUndoIsIdempotent() = runBlocking {
        val slot = Schedule.on(med, LocalDate.now()).single()
        repo.record(slot, DoseStatus.SKIPPED)
        assertEquals(30 * SCALE, repo.medication(med.id)!!.stock)
        repo.undo(slot.key); repo.undo(slot.key)
        assertEquals(30 * SCALE, repo.medication(med.id)!!.stock)
    }
    @Test fun editingDoesNotOverwriteConcurrentIntakeStock() = runBlocking {
        repo.record(Schedule.on(med, LocalDate.now()).single(), DoseStatus.TAKEN)
        repo.save(med.copy(name = "Renamed"))
        assertEquals(29 * SCALE, repo.medication(med.id)!!.stock)
        assertEquals("Renamed", repo.medication(med.id)!!.name)
    }
    @Test fun exportAndImportPreserveStockAndDoNotReplayIntakes() = runBlocking {
        repo.record(Schedule.on(med, LocalDate.now()).single(), DoseStatus.TAKEN)
        val backup = repo.readBackup(repo.export(Preferences()))
        repo.delete(med.id)
        assertEquals(1, repo.importBackup(backup))
        assertEquals(29 * SCALE, repo.medication(med.id)!!.stock)
        assertEquals(1, repo.dao.logs().size)
        assertEquals(0, repo.importBackup(backup))
        assertEquals(29 * SCALE, repo.medication(med.id)!!.stock)
    }
    @Test fun changedLocalMedicationIsKeptDuringMerge() = runBlocking {
        val backup = Backup(medications = listOf(med), history = emptyList())
        repo.stock(med.id, 10 * SCALE, true)
        repo.importBackup(backup)
        assertEquals(40 * SCALE, repo.medication(med.id)!!.stock)
    }
    @Test fun recordCanBeCorrectedWhenEstimatedInventoryWasEmpty() = runBlocking {
        repo.stock(med.id, 0, false)
        val slot = Schedule.on(med, LocalDate.now()).single()
        repo.record(slot, DoseStatus.TAKEN)
        assertEquals(-SCALE, repo.medication(med.id)!!.stock)
        repo.undo(slot.key)
        assertEquals(0L, repo.medication(med.id)!!.stock)
    }
}
