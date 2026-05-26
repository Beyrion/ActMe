package com.actme.app.data.local

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

object RecurrenceCalculator {
    fun normalizeEpochMillis(raw: Long?): Long {
        val value = raw ?: 0L
        return if (value in 1L until 10_000_000_000L) value * 1000 else value
    }

    fun parseWeekdays(raw: String): List<Int> {
        return raw.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..7 }
            .distinct()
            .sorted()
    }

    fun encodeWeekdays(days: List<Int>): String {
        return days
            .filter { it in 1..7 }
            .distinct()
            .sorted()
            .joinToString(",")
    }

    fun computeNextReminderFromSchedule(
        entity: ScheduleEntity,
        fromMillis: Long
    ): Long? {
        val zone = toZone(entity.timezoneId)
        val type = RepeatType.fromRaw(entity.repeatType)
        val normalizedReminder = normalizeEpochMillis(entity.reminderAt)
        val reminderMinutes = if (entity.reminderTimeMinutes in 0..1439) {
            entity.reminderTimeMinutes
        } else {
            val local = Instant.ofEpochMilli(normalizedReminder).atZone(zone)
            local.hour * 60 + local.minute
        }

        return when (type) {
            RepeatType.NONE -> normalizedReminder.takeIf { it > fromMillis }
            RepeatType.DAILY -> computeDaily(reminderMinutes, zone, fromMillis)
            RepeatType.WEEKLY -> {
                val days = parseWeekdays(entity.repeatDaysOfWeek)
                computeWeekly(reminderMinutes, days, zone, fromMillis)
            }
            RepeatType.MONTHLY -> {
                val dayOfMonth = entity.repeatDayOfMonth?.coerceIn(1, 31)
                computeMonthly(reminderMinutes, dayOfMonth, zone, fromMillis)
            }
        }
    }

    fun computeNextRecurringReminder(
        repeatType: RepeatType,
        reminderTimeMinutes: Int,
        repeatDaysOfWeek: List<Int>,
        repeatDayOfMonth: Int?,
        timezoneId: String,
        fromMillis: Long
    ): Long? {
        val zone = toZone(timezoneId)
        return when (repeatType) {
            RepeatType.NONE -> null
            RepeatType.DAILY -> computeDaily(reminderTimeMinutes, zone, fromMillis)
            RepeatType.WEEKLY -> computeWeekly(reminderTimeMinutes, repeatDaysOfWeek, zone, fromMillis)
            RepeatType.MONTHLY -> computeMonthly(reminderTimeMinutes, repeatDayOfMonth, zone, fromMillis)
        }
    }

    private fun computeDaily(reminderMinutes: Int, zone: ZoneId, fromMillis: Long): Long {
        val from = Instant.ofEpochMilli(fromMillis + 1_000).atZone(zone).toLocalDateTime()
        var candidate = atDateTime(from.toLocalDate(), reminderMinutes, zone)
        if (candidate <= fromMillis) {
            candidate = atDateTime(from.toLocalDate().plusDays(1), reminderMinutes, zone)
        }
        return candidate
    }

    private fun computeWeekly(
        reminderMinutes: Int,
        rawDays: List<Int>,
        zone: ZoneId,
        fromMillis: Long
    ): Long {
        val days = rawDays.filter { it in 1..7 }.distinct().ifEmpty { listOf(1) }.sorted()
        val fromDateTime = Instant.ofEpochMilli(fromMillis + 1_000).atZone(zone).toLocalDateTime()
        val fromDate = fromDateTime.toLocalDate()

        for (offset in 0..14L) {
            val date = fromDate.plusDays(offset)
            if (date.dayOfWeek.value !in days) continue
            val candidate = atDateTime(date, reminderMinutes, zone)
            if (candidate > fromMillis) return candidate
        }
        // 理论上不会走到这里，兜底返回一周后同一时间。
        val fallback = fromDate.plusWeeks(1)
        return atDateTime(fallback, reminderMinutes, zone)
    }

    private fun computeMonthly(
        reminderMinutes: Int,
        dayOfMonth: Int?,
        zone: ZoneId,
        fromMillis: Long
    ): Long {
        val validDay = (dayOfMonth ?: 1).coerceIn(1, 31)
        val from = Instant.ofEpochMilli(fromMillis + 1_000).atZone(zone).toLocalDateTime()
        var yearMonth = YearMonth.from(from)
        repeat(24) {
            val day = validDay.coerceAtMost(yearMonth.lengthOfMonth())
            val date = yearMonth.atDay(day)
            val candidate = atDateTime(date, reminderMinutes, zone)
            if (candidate > fromMillis) return candidate
            yearMonth = yearMonth.plusMonths(1)
        }
        return atDateTime(YearMonth.from(from).plusMonths(1).atDay(validDay.coerceAtMost(28)), reminderMinutes, zone)
    }

    private fun atDateTime(date: LocalDate, reminderMinutes: Int, zone: ZoneId): Long {
        val minutes = reminderMinutes.coerceIn(0, 23 * 60 + 59)
        val hour = minutes / 60
        val minute = minutes % 60
        return LocalDateTime.of(date, LocalTime.of(hour, minute))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }

    private fun toZone(id: String): ZoneId {
        return runCatching { ZoneId.of(id) }.getOrElse { ZoneId.systemDefault() }
    }
}
