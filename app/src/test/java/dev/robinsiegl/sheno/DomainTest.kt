package dev.robinsiegl.sheno

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    private val zone = ZoneId.of("Europe/Vienna")
    private fun med() = Medication(name = "Example", strength = "20 mg", stock = 30 * SCALE, startDate = "2026-03-01")
    @Test fun quantitiesDoNotAccumulateFloatingPointErrors() {
        assertEquals(500L, quantity("0.5"))
        assertEquals(125L, quantity("0,125"))
        assertEquals("0.125", quantityText(125))
        assertNull(quantity("0.0001")); assertNull(quantity("-1")); assertNull(quantity("1e999"))
        assertEquals(1000L, (1..10).sumOf { quantity("0.1")!! })
    }
    @Test fun selectedWeekdaysAndDateBoundsAreRespected() {
        val med = med().copy(frequency = Frequency.WEEKDAYS, weekdays = listOf(1, 3), endDate = "2026-03-05")
        assertTrue(Schedule.activeOn(med, LocalDate.parse("2026-03-02")))
        assertFalse(Schedule.activeOn(med, LocalDate.parse("2026-03-03")))
        assertFalse(Schedule.activeOn(med, LocalDate.parse("2026-03-09")))
        assertFalse(Schedule.activeOn(med, LocalDate.parse("2026-02-25")))
    }
    @Test fun intervalsAreAnchoredToTheStartDate() {
        val med = med().copy(frequency = Frequency.EVERY_N_DAYS, intervalDays = 3)
        assertTrue(Schedule.activeOn(med, LocalDate.parse("2026-03-04")))
        assertFalse(Schedule.activeOn(med, LocalDate.parse("2026-03-05")))
    }
    @Test fun nextReminderCrossesMidnightAndYearBoundary() {
        val med = med().copy(times = listOf("08:00", "20:00"))
        val next = Schedule.next(med, Instant.parse("2026-12-31T22:00:00Z"), zone)!!
        assertEquals("2027-01-01", next.date.toString())
        assertEquals("08:00", next.time.toString())
    }
    @Test fun daylightSavingGapMovesForwardWithoutDuplicateOccurrences() {
        val med = med().copy(times = listOf("02:30"))
        val slot = Schedule.on(med, LocalDate.parse("2026-03-29"), zone).single()
        assertEquals("2026-03-29T01:30:00Z", slot.at.toString())
        assertTrue(slot.key.endsWith("2026-03-29|02:30"))
    }
    @Test fun repeatedAutumnHourHasOneStableOccurrenceKey() {
        val slots = Schedule.on(med().copy(times = listOf("02:30")), LocalDate.parse("2026-10-25"), zone)
        assertEquals(1, slots.size)
        assertEquals("2026-10-25T00:30:00Z", slots.single().at.toString())
    }
    @Test fun asNeededAndArchivedMedicationsHaveNoAutomaticForecast() {
        val asNeeded = med().copy(frequency = Frequency.AS_NEEDED)
        assertTrue(Schedule.on(asNeeded, LocalDate.now(), zone).isEmpty())
        assertNull(Schedule.daysRemaining(asNeeded, Instant.now(), zone))
        assertNull(Schedule.next(med().copy(archived = true), Instant.now(), zone))
    }
    @Test fun supplyEstimateUsesQuantitiesAndScheduledTimes() {
        val med = med().copy(stock = 3 * SCALE, dose = SCALE / 2, times = listOf("08:00", "20:00"))
        assertEquals(2, Schedule.daysRemaining(med, Instant.parse("2026-03-02T00:00:00Z"), zone))
        assertTrue(Schedule.low(med.copy(stock = 0)))
    }
    @Test fun invalidSchedulesAndLinksCannotBeSaved() {
        assertNotNull(med().copy(times = listOf("29:00")).validate())
        assertNotNull(med().copy(times = listOf("08:00", "08:00")).validate())
        assertNotNull(med().copy(endDate = "2026-02-01").validate())
        assertFalse(validOrderUrl("javascript:alert(1)"))
        assertFalse(validOrderUrl("https://name:password@example.com"))
        assertTrue(validOrderUrl("https://example.com/order"))
        assertNull(med().validate())
    }
    @Test fun backupRejectsUnrelatedAndMalformedData() {
        assertTrue(runCatching { Backup(format = "other", medications = listOf(med()), history = emptyList()).validate() }.isFailure)
        assertTrue(runCatching { Backup(medications = listOf(med().copy(dose = 0)), history = emptyList()).validate() }.isFailure)
    }
}
