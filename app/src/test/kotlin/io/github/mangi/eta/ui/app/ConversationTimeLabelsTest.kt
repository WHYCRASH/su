package io.github.mangi.eta.ui.app

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationTimeLabelsTest {
    private val timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    private val locale = Locale.US

    @Test
    fun labelUsesClockTimeForToday() {
        val now = millis(2026, Calendar.JULY, 4, 19, 32)
        val timestamp = millis(2026, Calendar.JULY, 4, 9, 5)

        assertEquals("09:05", label(timestamp, now))
    }

    @Test
    fun labelUsesRelativeDayForRecentHistory() {
        val now = millis(2026, Calendar.JULY, 4, 19, 32)

        assertEquals("Yesterday", label(millis(2026, Calendar.JULY, 3, 23, 59), now))
        assertEquals("Mon", label(millis(2026, Calendar.JUNE, 29, 8, 0), now))
    }

    @Test
    fun labelUsesDateForOlderHistory() {
        val now = millis(2026, Calendar.JULY, 4, 19, 32)

        assertEquals("Jun 20", label(millis(2026, Calendar.JUNE, 20, 8, 0), now))
        assertEquals("Dec 31, 2025", label(millis(2025, Calendar.DECEMBER, 31, 8, 0), now))
    }

    @Test
    fun labelFallsBackForInvalidTimestamp() {
        assertEquals("Recent", label(0L, millis(2026, Calendar.JULY, 4, 19, 32)))
    }

    @Test
    fun labelSupportsEnglishAndTwelveHourClock() {
        val now = millis(2026, Calendar.JULY, 4, 19, 32)

        assertEquals(
            "9:05 AM",
            ConversationTimeLabels.label(
                timestampMillis = millis(2026, Calendar.JULY, 4, 9, 5),
                nowMillis = now,
                locale = Locale.US,
                timeZone = timeZone,
                use24HourClock = false,
            ),
        )
        assertEquals(
            "Yesterday",
            ConversationTimeLabels.label(
                timestampMillis = millis(2026, Calendar.JULY, 3, 23, 59),
                nowMillis = now,
                locale = Locale.US,
                timeZone = timeZone,
                use24HourClock = false,
            ),
        )
    }

    @Test
    fun relativeDaysRemainCorrectAcrossDaylightSavingChanges() {
        val losAngeles = TimeZone.getTimeZone("America/Los_Angeles")
        val now = millis(2026, Calendar.MARCH, 9, 0, 30, losAngeles, Locale.US)
        val yesterday = millis(2026, Calendar.MARCH, 8, 0, 30, losAngeles, Locale.US)

        assertEquals(
            "Yesterday",
            ConversationTimeLabels.label(
                timestampMillis = yesterday,
                nowMillis = now,
                locale = Locale.US,
                timeZone = losAngeles,
            ),
        )
    }

    private fun label(timestamp: Long, now: Long): String =
        ConversationTimeLabels.label(
            timestampMillis = timestamp,
            nowMillis = now,
            locale = locale,
            timeZone = timeZone,
            use24HourClock = true,
            yesterdayLabel = "Yesterday",
            recentLabel = "Recent",
        )

    private fun millis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long = millis(year, month, day, hour, minute, timeZone, locale)

    private fun millis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        zone: TimeZone,
        valueLocale: Locale,
    ): Long =
        Calendar.getInstance(zone, valueLocale).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis
}
