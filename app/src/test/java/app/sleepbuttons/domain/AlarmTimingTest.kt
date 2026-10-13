package app.sleepbuttons.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmTimingTest {
    @Test fun staleNapAlarmIsNotSounded() {
        assertFalse(AlarmTiming.isStale(1_000_000, 1_000_000 + 60_000, AlarmTiming.NAP_GRACE_MS))
        assertTrue(AlarmTiming.isStale(1_000_000, 1_000_000 + 3 * 3_600_000L, AlarmTiming.NAP_GRACE_MS))
    }

    @Test fun manualClockChangeKeepsElapsedDuration() {
        // Scheduled 40 min out at elapsed=100_000; user then sets clock 1h forward.
        val target = AlarmTiming.napTriggerEpoch(
            storedEpochMs = 5_000_000, storedElapsedMs = 100_000 + 40 * 60_000L,
            storedBootCount = 7, currentBootCount = 7,
            nowEpochMs = 2_600_000 + 3_600_000L, nowElapsedMs = 100_000 + 10 * 60_000L,
        )
        assertEquals(2_600_000 + 3_600_000L + 30 * 60_000L, target) // still 30 min remaining
    }

    @Test fun afterRebootFallsBackToStoredWallClock() =
        assertEquals(5_000_000L, AlarmTiming.napTriggerEpoch(5_000_000, 999, 7, 8, 1, 1))
}
