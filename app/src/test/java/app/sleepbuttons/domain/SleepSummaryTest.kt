package app.sleepbuttons.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SleepSummaryTest {
    private val ny = ZoneId.of("America/New_York")
    private fun at(mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(2026, mo, d, h, mi, 0, 0, ny)
    private fun main(onset: ZonedDateTime, wake: ZonedDateTime, awake: Int? = null) =
        SleepRecord(SleepType.MAIN, null, onset, wake, awake)

    @Test fun intervalCrossingMidnight() =
        assertEquals(7 * 60L + 30, SleepMath.periodMinutes(at(10, 11, 23, 30), at(10, 12, 7, 0)))

    @Test fun intervalAcrossDstFallBackGainsAnHour() =
        // 2026-11-01 clocks fall back 02:00 -> 01:00 in New York.
        assertEquals(10 * 60L, SleepMath.periodMinutes(at(10, 31, 23, 0), at(11, 1, 8, 0)))

    @Test fun wakeBeforeOnsetIsRejected() =
        assertNotNull(SleepMath.validate(main(at(10, 12, 23, 30), at(10, 12, 7, 0))))

    @Test fun validMainPasses() = assertNull(SleepMath.validate(main(at(10, 11, 23, 30), at(10, 12, 7, 0), 20)))

    @Test fun napAttemptWithoutOnsetIsAllowedButNotCountedAsSleep() {
        val nap = SleepRecord(SleepType.NAP, at(10, 12, 12, 0), null, null, null)
        assertNull(SleepMath.validate(nap))
    }

    @Test fun clockMeanNearMidnightIsMidnightNotNoon() {
        val c = ClockStats.circular(listOf(23 * 60 + 30, 30))!!
        assertTrue(c.meanMinuteOfDay < 2 || c.meanMinuteOfDay > 1438)
        val naive = (23 * 60 + 30 + 30) / 2
        assertEquals(720, naive) // the bug we avoid
    }

    @Test fun typicalTimeNeedsThreeEntries() {
        assertTrue(ClockStats.typical(listOf(1410, 30)) is TypicalTime.NotEnoughData)
        val t = ClockStats.typical(listOf(1410, 0, 30)) as TypicalTime.Typical
        assertTrue(t.minuteOfDay < 2 || t.minuteOfDay > 1438)
        assertEquals(Consistency.CONSISTENT, t.consistency) // circular SD ~25 min
    }

    @Test fun widelySpreadTimesAreReportedAsTooDispersed() =
        assertTrue(ClockStats.typical(listOf(21 * 60, 3 * 60, 9 * 60, 15 * 60)) is TypicalTime.TooDispersed)

    @Test fun tightTimesAreConsistent() {
        val t = ClockStats.typical(listOf(1420, 1430, 1435)) as TypicalTime.Typical
        assertEquals(Consistency.CONSISTENT, t.consistency)
    }

    @Test fun summaryUsesPeriodEstimateWithoutAwakeData() {
        val now = at(10, 12, 19, 0)
        val recs = listOf(
            main(at(10, 8, 23, 30), at(10, 9, 9, 0)),
            main(at(10, 10, 0, 15), at(10, 10, 9, 15)),
            main(at(10, 10, 23, 45), at(10, 11, 8, 45)),
            main(at(9, 20, 23, 0), at(9, 21, 7, 0)), // outside 14 days
        )
        val s = SleepSummarizer.summarize(recs, now, 14)
        assertEquals(3, s.mainSleepCount)
        val d = s.duration as TypicalDuration.Median
        assertEquals(9 * 60L, d.minutes)
        assertEquals(false, d.subtractsAwakeTime)
        val onset = s.onset as TypicalTime.Typical
        assertTrue(onset.minuteOfDay > 1380 || onset.minuteOfDay < 60)
    }

    @Test fun summarySubtractsAwakeOnlyWhenAllEntriesHaveIt() {
        val now = at(10, 12, 19, 0)
        val recs = listOf(
            main(at(10, 8, 23, 0), at(10, 9, 7, 0), 30),
            main(at(10, 9, 23, 0), at(10, 10, 7, 0), 30),
            main(at(10, 10, 23, 0), at(10, 11, 7, 0), 30),
        )
        val d = SleepSummarizer.summarize(recs, now, 7).duration as TypicalDuration.Median
        assertEquals(7 * 60L + 30, d.minutes)
        assertTrue(d.subtractsAwakeTime)
    }

    @Test fun napLatencyOnlyFromActualEstimates() {
        val now = at(10, 12, 19, 0)
        val naps = listOf(
            SleepRecord(SleepType.NAP, at(10, 9, 12, 0), at(10, 9, 12, 25), at(10, 9, 12, 35), null),
            SleepRecord(SleepType.NAP, at(10, 10, 12, 0), at(10, 10, 12, 20), null, null),
            SleepRecord(SleepType.NAP, at(10, 11, 12, 0), null, null, null), // attempt only
        )
        assertTrue(SleepSummarizer.summarize(naps, now, 7).napLatency is TypicalDuration.NotEnoughData)
        val more = naps + SleepRecord(SleepType.NAP, at(10, 12, 11, 30), at(10, 12, 12, 0), null, null)
        val lat = SleepSummarizer.summarize(more, now, 7).napLatency as TypicalDuration.Median
        assertEquals(25L, lat.minutes)
    }
}
