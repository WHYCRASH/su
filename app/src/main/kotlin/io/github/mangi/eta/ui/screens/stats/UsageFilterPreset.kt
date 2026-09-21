package io.github.mangi.eta.ui.screens.stats

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

internal data class UsageTimeBound(
    val date: LocalDate? = null,
) {
    val isSet: Boolean get() = date != null

    fun toMillis(endOfBound: Boolean): Long? {
        val selectedDate = date ?: return null
        val time = if (endOfBound) {
            LocalTime.of(23, 59, 59, 999_000_000)
        } else {
            LocalTime.MIN
        }
        return LocalDateTime.of(selectedDate, time)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    companion object {
        fun from(date: LocalDate): UsageTimeBound = UsageTimeBound(date = date)

        fun from(dateTime: LocalDateTime): UsageTimeBound = UsageTimeBound(date = dateTime.toLocalDate())
    }
}

internal enum class UsageFilterPreset {
    Today,
    Last7Days,
    ThisWeek,
    Last30Days,
    ThisMonth,
}

internal fun usageFilterPresetRange(
    preset: UsageFilterPreset,
    today: LocalDate,
    weekStart: DayOfWeek = DayOfWeek.MONDAY,
): Pair<LocalDateTime, LocalDateTime> {
    val end = today.atTime(23, 59)
    val startDate = when (preset) {
        UsageFilterPreset.Today -> today
        // Last 7 days: 00:00 seven days ago → today 23:59. Not "7 calendar days including today".
        UsageFilterPreset.Last7Days -> today.minusDays(7)
        // This week: Monday 00:00 → today 23:59.
        UsageFilterPreset.ThisWeek -> today.with(TemporalAdjusters.previousOrSame(weekStart))
        UsageFilterPreset.Last30Days -> today.minusDays(30)
        UsageFilterPreset.ThisMonth -> today.withDayOfMonth(1)
    }
    return startDate.atTime(0, 0) to end
}

internal fun matchingUsageFilterPreset(
    start: UsageTimeBound,
    end: UsageTimeBound,
    today: LocalDate,
    weekStart: DayOfWeek = DayOfWeek.MONDAY,
    preferred: UsageFilterPreset? = null,
): UsageFilterPreset? {
    if (!start.isSet || !end.isSet) return null
    val matches = UsageFilterPreset.entries.filter { preset ->
        val (expectedStart, expectedEnd) = usageFilterPresetRange(preset, today, weekStart)
        start.date == expectedStart.toLocalDate() && end.date == expectedEnd.toLocalDate()
    }
    if (preferred != null && preferred in matches) return preferred
    return matches.firstOrNull()
}
