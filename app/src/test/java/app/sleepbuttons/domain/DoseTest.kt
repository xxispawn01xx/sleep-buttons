package app.sleepbuttons.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoseTest {
    private fun ok(raw: String, u: DoseUnit) = Dose.parse(raw, u) as DoseParse.Ok

    @Test fun milligramsConvertExactly() {
        assertEquals(50, ok("0.05", DoseUnit.MILLIGRAMS).dose.micrograms)
        assertEquals(100, ok("0.1", DoseUnit.MILLIGRAMS).dose.micrograms)
        assertEquals(75, ok("0.075", DoseUnit.MILLIGRAMS).dose.micrograms)
        assertEquals(3000, ok("3", DoseUnit.MILLIGRAMS).dose.micrograms)
    }

    @Test fun microgramsAreTakenAsIs() = assertEquals(50, ok("50", DoseUnit.MICROGRAMS).dose.micrograms)

    @Test fun displaysBothUnits() {
        assertEquals("50 mcg (0.05 mg)", Dose(50).display())
        assertEquals("100 mcg (0.1 mg)", Dose(100).display())
        assertEquals("1,500 mcg (1.5 mg)", Dose(1500).display())
        assertEquals("3,000 mcg (3 mg)", Dose(3000).display())
    }

    @Test fun rejectsBadInput() {
        assertTrue(Dose.parse("0", DoseUnit.MICROGRAMS) is DoseParse.Error)
        assertTrue(Dose.parse("-0.05", DoseUnit.MILLIGRAMS) is DoseParse.Error)
        assertTrue(Dose.parse("abc", DoseUnit.MILLIGRAMS) is DoseParse.Error)
        assertTrue(Dose.parse("", DoseUnit.MILLIGRAMS) is DoseParse.Error)
        assertTrue(Dose.parse("0.0505", DoseUnit.MILLIGRAMS) is DoseParse.Error) // 50.5 mcg
        assertTrue(Dose.parse("50.5", DoseUnit.MICROGRAMS) is DoseParse.Error)
        assertTrue(Dose.parse("100", DoseUnit.MILLIGRAMS) is DoseParse.Error)    // units typo guard
    }

    @Test fun flagsButAllowsValuesOutsideHistoricalRange() {
        assertFalse(ok("0.05", DoseUnit.MILLIGRAMS).outsideHistoricalRange)
        assertFalse(ok("100", DoseUnit.MICROGRAMS).outsideHistoricalRange)
        assertTrue(ok("50", DoseUnit.MILLIGRAMS).let { it.outsideHistoricalRange && it.dose.micrograms == 50_000 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun doseMustBePositive() { Dose(0) }
}
