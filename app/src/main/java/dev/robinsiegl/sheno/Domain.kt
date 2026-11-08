package dev.robinsiegl.sheno

import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.Serializable

// Quantities use thousandths of a unit. Strength is display text, never a conversion rule.
const val SCALE = 1000L
const val MAX_QUANTITY = 1_000_000_000L
fun quantity(text: String): Long? = runCatching {
    BigDecimal(text.trim().replace(',', '.')).movePointRight(3).longValueExact()
}.getOrNull()?.takeIf { it in 0..MAX_QUANTITY }
fun quantityText(value: Long): String = BigDecimal.valueOf(value, 3).stripTrailingZeros().toPlainString()

@Serializable enum class Form(val label: String, val unit: String) {
    ROUND("Round tablet", "tablets"), OVAL("Oval tablet", "tablets"),
    OBLONG("Oblong tablet", "tablets"), CAPSULE("Capsule", "capsules"),
    LIQUID("Liquid", "mL"), DROPS("Drops", "drops"), INJECTION("Injection", "doses"),
    INHALER("Inhaler", "puffs"), PATCH("Patch", "patches"), OTHER("Other", "units")
}
@Serializable enum class Frequency(val label: String) {
    DAILY("Every day"), WEEKDAYS("Selected weekdays"), EVERY_N_DAYS("Every few days"), AS_NEEDED("As needed")
}
@Serializable data class Medication(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val strength: String = "",
    val form: Form = Form.ROUND,
    val color: Long = 0xFF6750A4,
    val secondColor: Long = 0xFFEADDFF,
    val stock: Long = 0,
    val dose: Long = SCALE,
    val frequency: Frequency = Frequency.DAILY,
    val times: List<String> = listOf("08:00"),
    val weekdays: List<Int> = (1..7).toList(),
    val intervalDays: Int = 2,
    val startDate: String = LocalDate.now().toString(),
    val endDate: String? = null,
    val reminders: Boolean = true,
    val lowStock: Long = 7 * SCALE,
    val lowDays: Int = 0,
    val reorderUrl: String = "",
    val orderText: String = "",
    val notes: String = "",
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun validate(): String? = when {
        id.isBlank() || id.length > 100 -> "Invalid medication identifier."
        name.isBlank() || name.length > 100 -> "Enter a medication name of up to 100 characters."
        strength.length > 100 || orderText.length > 200 || notes.length > 2000 -> "One of the details is too long."
        dose !in 1..MAX_QUANTITY || stock !in -MAX_QUANTITY..MAX_QUANTITY -> "Check the quantities."
        lowStock !in 0..MAX_QUANTITY || lowDays !in 0..90 -> "Check the low-stock threshold."
        runCatching { LocalDate.parse(startDate) }.isFailure -> "Choose a start date."
        endDate != null && runCatching { LocalDate.parse(endDate) >= LocalDate.parse(startDate) }.getOrDefault(false).not() -> "End date must be on or after the start date."
        frequency != Frequency.AS_NEEDED && (times.isEmpty() || times.size > 12 || times.distinct().size != times.size || times.any { !Regex("[0-2][0-9]:[0-5][0-9]").matches(it) || runCatching { LocalTime.parse(it) }.isFailure }) -> "Choose between 1 and 12 different reminder times."
        weekdays.any { it !in 1..7 } || frequency == Frequency.WEEKDAYS && weekdays.isEmpty() -> "Choose at least one weekday."
        intervalDays !in 1..365 -> "Choose an interval from 1 to 365 days."
        reorderUrl.isNotBlank() && !validOrderUrl(reorderUrl) -> "Enter a full http:// or https:// website address."
        else -> null
    }
}

fun validOrderUrl(text: String): Boolean = runCatching {
    val uri = URI(text)
    uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null && text.length <= 2048
}.getOrDefault(false)

@Serializable enum class DoseStatus { TAKEN, SKIPPED }
@Serializable data class DoseLog(
    val id: String,
    val medicationId: String,
    val name: String,
    val strength: String,
    val unit: String,
    val amount: Long,
    val status: DoseStatus,
    val recordedAt: Long,
    val scheduledAt: Long? = null,
)
data class Occurrence(val medication: Medication, val date: LocalDate, val time: LocalTime, val at: Instant) {
    val key: String get() = "${medication.id}|$date|$time"
}
object Schedule {
    fun activeOn(med: Medication, date: LocalDate): Boolean {
        if (med.archived || med.frequency == Frequency.AS_NEEDED) return false
        val start = LocalDate.parse(med.startDate)
        if (date < start || med.endDate?.let { date > LocalDate.parse(it) } == true) return false
        return when (med.frequency) {
            Frequency.DAILY -> true
            Frequency.WEEKDAYS -> date.dayOfWeek.value in med.weekdays
            Frequency.EVERY_N_DAYS -> ChronoUnit.DAYS.between(start, date) % med.intervalDays == 0L
            Frequency.AS_NEEDED -> false
        }
    }
    fun on(med: Medication, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Occurrence> =
        if (!activeOn(med, date)) emptyList() else med.times.sorted().map {
            val time = LocalTime.parse(it)
            Occurrence(med, date, time, date.atTime(time).atZone(zone).toInstant())
        }
    fun next(med: Medication, now: Instant, zone: ZoneId = ZoneId.systemDefault()): Occurrence? {
        val today = now.atZone(zone).toLocalDate()
        for (day in 0L..366L) {
            on(med, today.plusDays(day), zone).firstOrNull { it.at > now }?.let { return it }
        }
        return null
    }
    fun daysRemaining(med: Medication, now: Instant, zone: ZoneId = ZoneId.systemDefault()): Int? {
        if (med.archived || med.frequency == Frequency.AS_NEEDED) return null
        if (med.stock <= 0) return 0
        var remaining = med.stock
        val today = now.atZone(zone).toLocalDate()
        for (day in 0..730) {
            for (slot in on(med, today.plusDays(day.toLong()), zone).filter { it.at > now }) {
                remaining -= med.dose
                if (remaining <= 0) return day
            }
        }
        return null
    }
    fun low(med: Medication, now: Instant = Instant.now()): Boolean = !med.archived &&
        (med.stock <= med.lowStock || med.lowDays > 0 && daysRemaining(med, now)?.let { it <= med.lowDays } == true)
}

@Serializable data class Preferences(val theme: String = "System", val dynamicColor: Boolean = true, val discreet: Boolean = false)
@Serializable data class Backup(
    val format: String = "sheno", val version: Int = 1,
    val exportedAt: String = Instant.now().toString(),
    val medications: List<Medication>, val history: List<DoseLog>,
    val preferences: Preferences = Preferences(),
) {
    fun validate() {
        require(format == "sheno" && version == 1) { "This file is not a supported Sheno backup." }
        require(medications.size <= 1000 && history.size <= 50000) { "The backup is too large." }
        val medicationIds = medications.map { it.id }.toSet()
        require(medicationIds.size == medications.size) { "Duplicate medication identifiers." }
        medications.forEach { require(it.validate() == null) { it.validate() ?: "Invalid medication." } }
        require(history.map { it.id }.distinct().size == history.size) { "Duplicate history identifiers." }
        history.forEach {
            require(it.id.isNotBlank() && it.id.length <= 256 && it.medicationId in medicationIds && it.amount in 1..MAX_QUANTITY && it.name.length <= 100 && it.strength.length <= 100 && it.unit.length <= 30 && it.recordedAt >= 0) { "Invalid history record." }
        }
    }
}
