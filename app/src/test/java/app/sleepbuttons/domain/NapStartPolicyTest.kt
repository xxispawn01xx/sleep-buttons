package app.sleepbuttons.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class NapStartPolicyTest {
    private val t0 = 10_000_000L

    @Test fun firstTapStarts() =
        assertEquals(NapStartDecision.START, NapStartPolicy.decide(false, false, null, t0))

    @Test fun rapidSecondTapIsIgnored() {
        assertEquals(NapStartDecision.IGNORE_DUPLICATE_TAP, NapStartPolicy.decide(true, false, t0, t0 + 150))
        assertEquals(NapStartDecision.IGNORE_DUPLICATE_TAP, NapStartPolicy.decide(true, true, t0, t0 + 1_999))
    }

    @Test fun laterTapWithActiveSessionShowsExistingInsteadOfDuplicating() =
        assertEquals(NapStartDecision.SHOW_EXISTING, NapStartPolicy.decide(true, false, t0, t0 + 60_000))

    @Test fun explicitReplaceStarts() =
        assertEquals(NapStartDecision.START, NapStartPolicy.decide(true, true, t0, t0 + 60_000))

    @Test fun noActiveSessionAfterDebounceStarts() =
        assertEquals(NapStartDecision.START, NapStartPolicy.decide(false, false, t0, t0 + 5_000))

    @Test fun clockMovedBackwardsDoesNotBlockForever() =
        assertEquals(NapStartDecision.START, NapStartPolicy.decide(false, false, t0, t0 - 3_600_000))
}
