package app.sleepbuttons.domain

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object ReminderLogic {
    val DEFAULT_REMINDER_TIME: LocalTime = LocalTime.of(20, 30)

    data class Occurrence(val at: ZonedDateTime, val isLaterDate: Boolean)

    /**
     * Next occurrence of [time] strictly after [now], in now's zone.
     * If today's time has already passed (or is exactly now) it moves to the next
     * calendar day — never an immediate catch-up. A wall time that falls in a DST
     * spring-forward gap resolves to the first valid instant after the gap.
     */
    fun nextOccurrence(now: ZonedDateTime, time: LocalTime): Occurrence {
        val today = ZonedDateTime.of(now.toLocalDate(), time, now.zone)
        if (today.isAfter(now)) return Occurrence(today, isLaterDate = false)
        val next = ZonedDateTime.of(now.toLocalDate().plusDays(1), time, now.zone)
        return Occurrence(next, isLaterDate = true)
    }

    /**
     * Melatonin reminders follow saved local wall-clock time: after a time-zone
     * change, the same local date+time is re-resolved in the new zone.
     */
    fun resolveWallClock(local: LocalDateTime, zone: ZoneId): Instant =
        ZonedDateTime.of(local, zone).toInstant()
}

data class DoseWindow(val start: ZonedDateTime, val end: ZonedDateTime) {
    fun contains(instant: Instant): Boolean =
        !instant.isBefore(start.toInstant()) && instant.isBefore(end.toInstant())
}

/**
 * Evening-to-next-morning duplicate-dose window, default 18:00 → 12:00 local.
 *
 * Cross-midnight definition: the window that STARTS on local date D runs from
 * D at [start] to D+1 at [end] (when end <= start). An instant belongs to at
 * most one window. Times between [end] and [start] (default 12:00–18:00)
 * belong to no window. Bounds: start inclusive, end exclusive.
 */
object DoseWindows {
    val DEFAULT_START: LocalTime = LocalTime.of(18, 0)
    val DEFAULT_END: LocalTime = LocalTime.of(12, 0)

    fun validate(start: LocalTime, end: LocalTime): String? =
        if (start == end) "Window start and end can't be the same time." else null

    fun windowContaining(at: ZonedDateTime, start: LocalTime, end: LocalTime): DoseWindow? {
        require(start != end)
        val crossesMidnight = !end.isAfter(start)
        val date = at.toLocalDate()
        return listOf(date.minusDays(1), date).map { d ->
            val endDate = if (crossesMidnight) d.plusDays(1) else d
            DoseWindow(
                ZonedDateTime.of(d, start, at.zone),
                ZonedDateTime.of(endDate, end, at.zone),
            )
        }.firstOrNull { it.contains(at.toInstant()) }
    }

    /** Is a reminder at local [time] inside some window? (Settings validation.) */
    fun timeIsInsideWindow(time: LocalTime, start: LocalTime, end: LocalTime): Boolean {
        if (start == end) return false
        return if (end.isAfter(start)) !time.isBefore(start) && time.isBefore(end)
        else !time.isBefore(start) || time.isBefore(end)
    }

    fun dosesIn(window: DoseWindow, doseTimes: List<Instant>): List<Instant> =
        doseTimes.filter { window.contains(it) }
}
