package app.sleepbuttons.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderLogicTest {
    private val ny = ZoneId.of("America/New_York")
    private val t = LocalTime.of(20, 30)
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ny)

    @Test fun laterTodayIsToday() {
        val o = ReminderLogic.nextOccurrence(at(2026, 10, 12, 19, 49), t)
        assertEquals(at(2026, 10, 12, 20, 30), o.at)
        assertFalse(o.isLaterDate)
    }

    @Test fun passedTimeMovesToTomorrowWithNoCatchUp() {
        val o = ReminderLogic.nextOccurrence(at(2026, 10, 12, 21, 5), t)
        assertEquals(at(2026, 10, 13, 20, 30), o.at)
        assertTrue(o.isLaterDate)
    }

    @Test fun exactlyNowIsNotImmediate() {
        val o = ReminderLogic.nextOccurrence(at(2026, 10, 12, 20, 30), t)
        assertEquals(at(2026, 10, 13, 20, 30), o.at)
    }

    @Test fun afterMidnightTarget() {
        val o = ReminderLogic.nextOccurrence(at(2026, 10, 12, 23, 0), LocalTime.of(0, 15))
        assertEquals(at(2026, 10, 13, 0, 15), o.at)
        assertTrue(o.isLaterDate)
    }

    @Test fun dstSpringForwardGapResolvesForward() {
        // 2026-03-08 02:30 does not exist in New York.
        val o = ReminderLogic.nextOccurrence(at(2026, 3, 8, 1, 0), LocalTime.of(2, 30))
        assertEquals(3, o.at.hour)
        assertEquals(30, o.at.minute)
    }

    @Test fun wallClockFollowsNewTimeZone() {
        val local = LocalDateTime.of(2026, 10, 12, 20, 30)
        val ny = ReminderLogic.resolveWallClock(local, ZoneId.of("America/New_York"))
        val la = ReminderLogic.resolveWallClock(local, ZoneId.of("America/Los_Angeles"))
        assertEquals(3 * 3600L, la.epochSecond - ny.epochSecond)
    }
}
