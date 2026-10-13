package app.sleepbuttons.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class DoseWindowTest {
    private val ny = ZoneId.of("America/New_York")
    private val s = DoseWindows.DEFAULT_START
    private val e = DoseWindows.DEFAULT_END
    private fun at(d: Int, h: Int, mi: Int) = ZonedDateTime.of(2026, 10, d, h, mi, 0, 0, ny)

    @Test fun eveningBelongsToWindowStartingThatEvening() {
        val w = DoseWindows.windowContaining(at(12, 20, 30), s, e)!!
        assertEquals(at(12, 18, 0), w.start)
        assertEquals(at(13, 12, 0), w.end)
    }

    @Test fun afterMidnightBelongsToPreviousEveningsWindow() {
        val w = DoseWindows.windowContaining(at(13, 0, 30), s, e)!!
        assertEquals(at(12, 18, 0), w.start)
    }

    @Test fun boundsAreStartInclusiveEndExclusive() {
        assertEquals(at(12, 18, 0), DoseWindows.windowContaining(at(12, 18, 0), s, e)!!.start)
        assertNull(DoseWindows.windowContaining(at(13, 12, 0), s, e))
        assertNull(DoseWindows.windowContaining(at(13, 15, 0), s, e)) // afternoon gap
    }

    @Test fun doseAt2350BlocksReminderAt0015SameNight() {
        val dose = at(12, 23, 50).toInstant()
        val reminderWindow = DoseWindows.windowContaining(at(13, 0, 15), s, e)!!
        assertEquals(1, DoseWindows.dosesIn(reminderWindow, listOf(dose)).size)
    }

    @Test fun lastNightsDoseDoesNotBlockTonight() {
        val lastNight = at(12, 21, 0).toInstant()
        val tonight = DoseWindows.windowContaining(at(13, 20, 30), s, e)!!
        assertTrue(DoseWindows.dosesIn(tonight, listOf(lastNight)).isEmpty())
    }

    @Test fun morningDoseBeforeNoonCountsForPreviousNight() {
        val morning = at(13, 11, 59).toInstant()
        val prevNight = DoseWindows.windowContaining(at(12, 22, 0), s, e)!!
        assertEquals(1, DoseWindows.dosesIn(prevNight, listOf(morning)).size)
    }

    @Test fun sameDayWindowSupported() {
        val w = DoseWindows.windowContaining(at(12, 20, 0), LocalTime.of(19, 0), LocalTime.of(23, 0))!!
        assertEquals(at(12, 23, 0), w.end)
    }

    @Test fun reminderTimeMustSitInsideWindow() {
        assertTrue(DoseWindows.timeIsInsideWindow(LocalTime.of(20, 30), s, e))
        assertTrue(DoseWindows.timeIsInsideWindow(LocalTime.of(0, 15), s, e))
        assertFalse(DoseWindows.timeIsInsideWindow(LocalTime.of(15, 0), s, e))
    }
}
