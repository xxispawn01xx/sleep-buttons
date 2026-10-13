package app.sleepbuttons.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class NapLogicTest {
    @Test fun defaultsAre35And40() {
        assertEquals(35, NapLogic.DEFAULT_FIRST_MIN)
        assertEquals(40, NapLogic.DEFAULT_SECOND_MIN)
        assertNull(NapLogic.validateOffsets(35, 40))
    }

    @Test fun offsetsMeasuredFromTap() {
        val tap = 1_000_000L
        val p = NapLogic.plan(tap, 35, 40)
        assertEquals(tap, p.startedAtEpochMs)
        assertEquals(tap + 35 * 60_000L, p.getUpAtEpochMs)
        assertEquals(tap + 40 * 60_000L, p.backupAtEpochMs)
    }

    @Test fun rejectsNonPositiveAndMisordered() {
        assertNotNull(NapLogic.validateOffsets(0, 40))
        assertNotNull(NapLogic.validateOffsets(-5, 40))
        assertNotNull(NapLogic.validateOffsets(35, 0))
        assertNotNull(NapLogic.validateOffsets(40, 40))   // must be strictly later
        assertNotNull(NapLogic.validateOffsets(45, 40))
        assertNotNull(NapLogic.validateOffsets(35, 241))
        assertNull(NapLogic.validateOffsets(40, 45))      // earlier-discussed pair still valid
    }

    @Test(expected = IllegalArgumentException::class)
    fun planRefusesInvalidOffsets() { NapLogic.plan(0L, 40, 35) }

    @Test fun lateNapWarningIsAtOrAfterCutoff() {
        val cutoff = LocalTime.of(14, 0)
        assertFalse(NapLogic.isLateNap(LocalTime.of(13, 59), cutoff))
        assertTrue(NapLogic.isLateNap(LocalTime.of(14, 0), cutoff))
        assertTrue(NapLogic.isLateNap(LocalTime.of(16, 30), cutoff))
        assertFalse(NapLogic.isLateNap(LocalTime.of(11, 30), cutoff))
    }
}
