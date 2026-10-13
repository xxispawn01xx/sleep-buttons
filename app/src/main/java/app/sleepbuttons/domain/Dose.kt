package app.sleepbuttons.domain

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

enum class DoseUnit(val short: String) { MICROGRAMS("mcg"), MILLIGRAMS("mg") }

/** Dose stored exactly as integer micrograms. 50 mcg == 0.05 mg. */
data class Dose(val micrograms: Int) {
    init {
        require(micrograms > 0) { "dose must be positive" }
    }

    val milligramsText: String
        get() = BigDecimal(micrograms).movePointLeft(3).stripTrailingZeros().toPlainString()

    val microgramsText: String
        get() = NumberFormat.getIntegerInstance(Locale.US).format(micrograms)

    /** e.g. "50 mcg (0.05 mg)" */
    fun display(): String = "$microgramsText mcg ($milligramsText mg)"

    companion object {
        /** Historical context only — never used to choose a dose. */
        const val HISTORICAL_LOW_MCG = 50
        const val HISTORICAL_HIGH_MCG = 100

        /** Anything at/above this is treated as a units mistake, not a plan. */
        const val UNITS_SANITY_LIMIT_MCG = 100_000 // 100 mg

        fun parse(raw: String, unit: DoseUnit): DoseParse {
            val text = raw.trim()
            if (text.isEmpty()) return DoseParse.Error("Enter a dose.")
            val value = try {
                BigDecimal(text)
            } catch (e: NumberFormatException) {
                return DoseParse.Error("Enter a number, like 0.05 (mg) or 50 (mcg).")
            }
            val mcg = if (unit == DoseUnit.MILLIGRAMS) value.movePointRight(3) else value
            if (mcg.signum() <= 0) return DoseParse.Error("Dose must be greater than zero.")
            if (mcg.stripTrailingZeros().scale() > 0) {
                return DoseParse.Error("Use a whole number of micrograms (0.05 mg = 50 mcg).")
            }
            if (mcg >= BigDecimal(UNITS_SANITY_LIMIT_MCG)) {
                return DoseParse.Error("That is 100 mg or more — this looks like a units mistake. Check mg vs mcg.")
            }
            val dose = Dose(mcg.intValueExact())
            val outside = dose.micrograms < HISTORICAL_LOW_MCG || dose.micrograms > HISTORICAL_HIGH_MCG
            return DoseParse.Ok(dose, outsideHistoricalRange = outside)
        }
    }
}

sealed interface DoseParse {
    data class Ok(val dose: Dose, val outsideHistoricalRange: Boolean) : DoseParse
    data class Error(val message: String) : DoseParse
}
