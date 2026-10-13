package app.sleepbuttons.domain

import java.time.Duration
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class SleepType { MAIN, NAP }

/**
 * One manually logged record. Times carry their own zone so clock-of-day is read
 * in the zone where it happened. For MAIN, onset and wake are required. For NAP
 * every time is optional: a nap attempt is not counted as sleep unless the user
 * enters an onset.
 */
data class SleepRecord(
    val type: SleepType,
    val inBed: ZonedDateTime?,
    val onset: ZonedDateTime?,
    val wake: ZonedDateTime?,
    val awakeMinutes: Int?,
)

object SleepMath {
    const val MAX_PERIOD_HOURS = 24L

    /** Onset→final wake in minutes. Uses instants, so midnight and DST are handled. */
    fun periodMinutes(onset: ZonedDateTime, wake: ZonedDateTime): Long =
        Duration.between(onset.toInstant(), wake.toInstant()).toMinutes()

    fun validate(r: SleepRecord): String? {
        if (r.type == SleepType.MAIN && (r.onset == null || r.wake == null)) {
            return "Main sleep needs an estimated sleep onset and a final wake time."
        }
        if (r.onset != null && r.wake != null) {
            val p = periodMinutes(r.onset, r.wake)
            if (p <= 0) return "Wake time must be after sleep onset (check the date)."
            if (p > MAX_PERIOD_HOURS * 60) return "Sleep period is over 24 hours — check the dates."
            if (r.awakeMinutes != null && (r.awakeMinutes < 0 || r.awakeMinutes >= p)) {
                return "Awake minutes must be at least 0 and less than the sleep period."
            }
        }
        if (r.inBed != null && r.onset != null && r.onset.isBefore(r.inBed)) {
            return "Sleep onset can't be before the time you lay down."
        }
        if (r.inBed != null && r.wake != null && !r.wake.isAfter(r.inBed)) {
            return "Wake time must be after the time you lay down."
        }
        if (r.type == SleepType.NAP && r.inBed == null && r.onset == null) {
            return "For a nap, enter at least when you lay down or an estimated onset."
        }
        return null
    }
}

enum class Consistency { CONSISTENT, SOMEWHAT_VARIABLE, VARIABLE }

sealed interface TypicalTime {
    data class NotEnoughData(val n: Int) : TypicalTime
    data class TooDispersed(val n: Int, val spreadMinutes: Int) : TypicalTime
    data class Typical(
        val minuteOfDay: Int,
        val spreadMinutes: Int,
        val n: Int,
        val consistency: Consistency,
    ) : TypicalTime
}

sealed interface TypicalDuration {
    data class NotEnoughData(val n: Int) : TypicalDuration
    data class Median(val minutes: Long, val n: Int, val subtractsAwakeTime: Boolean) : TypicalDuration
}

/**
 * Clock times are circular (23:30 and 00:30 are an hour apart, not 23 hours).
 * Method: map minute-of-day to an angle, take the circular (vector) mean, and
 * use the circular standard deviation sqrt(-2 ln R) as the spread.
 * Limitation: with few points, or with two clusters (e.g. 22:00 and 04:00), the
 * mean can sit between clusters where nothing happened — so a wide spread is
 * reported as "too spread out" instead of a typical time.
 */
object ClockStats {
    const val MIN_ENTRIES = 3
    const val CONSISTENT_MAX = 30
    const val SOMEWHAT_MAX = 60
    const val USEFUL_MAX = 120

    data class Circular(val meanMinuteOfDay: Int, val spreadMinutes: Double)

    fun circular(minutesOfDay: List<Int>): Circular? {
        if (minutesOfDay.isEmpty()) return null
        var c = 0.0
        var s = 0.0
        for (m in minutesOfDay) {
            val a = m / 1440.0 * 2 * PI
            c += cos(a)
            s += sin(a)
        }
        c /= minutesOfDay.size
        s /= minutesOfDay.size
        val r = sqrt(c * c + s * s)
        if (r < 1e-9) return Circular(0, Double.POSITIVE_INFINITY)
        var mean = atan2(s, c) / (2 * PI) * 1440.0
        if (mean < 0) mean += 1440.0
        val spread = sqrt(-2 * ln(r.coerceAtMost(1.0))) / (2 * PI) * 1440.0
        return Circular(mean.roundToInt() % 1440, spread)
    }

    fun typical(minutesOfDay: List<Int>): TypicalTime {
        if (minutesOfDay.size < MIN_ENTRIES) return TypicalTime.NotEnoughData(minutesOfDay.size)
        val c = circular(minutesOfDay)!!
        if (c.spreadMinutes > USEFUL_MAX) {
            val spread = if (c.spreadMinutes.isInfinite()) Int.MAX_VALUE else c.spreadMinutes.roundToInt()
            return TypicalTime.TooDispersed(minutesOfDay.size, spread)
        }
        val consistency = when {
            c.spreadMinutes <= CONSISTENT_MAX -> Consistency.CONSISTENT
            c.spreadMinutes <= SOMEWHAT_MAX -> Consistency.SOMEWHAT_VARIABLE
            else -> Consistency.VARIABLE
        }
        return TypicalTime.Typical(c.meanMinuteOfDay, c.spreadMinutes.roundToInt(), minutesOfDay.size, consistency)
    }

    fun minuteOfDay(t: ZonedDateTime): Int = t.hour * 60 + t.minute
}

data class PeriodSummary(
    val days: Int,
    val mainSleepCount: Int,
    val onset: TypicalTime,
    val wake: TypicalTime,
    val duration: TypicalDuration,
    val napLatency: TypicalDuration,
)

object SleepSummarizer {
    fun median(values: List<Long>): Long {
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /** Descriptive only. Never feeds back into dose or reminder settings. */
    fun summarize(records: List<SleepRecord>, now: ZonedDateTime, days: Int): PeriodSummary {
        val from = now.toInstant().minus(Duration.ofDays(days.toLong()))
        val nowI = now.toInstant()

        val mains = records.filter { r ->
            r.type == SleepType.MAIN && r.onset != null && r.wake != null &&
                r.wake.toInstant().isAfter(from) && !r.wake.toInstant().isAfter(nowI) &&
                SleepMath.periodMinutes(r.onset, r.wake) in 1..SleepMath.MAX_PERIOD_HOURS * 60
        }

        val onset = ClockStats.typical(mains.map { ClockStats.minuteOfDay(it.onset!!) })
        val wake = ClockStats.typical(mains.map { ClockStats.minuteOfDay(it.wake!!) })

        val duration = if (mains.size < ClockStats.MIN_ENTRIES) {
            TypicalDuration.NotEnoughData(mains.size)
        } else {
            val allHaveAwake = mains.all { it.awakeMinutes != null }
            val mins = mains.map {
                val p = SleepMath.periodMinutes(it.onset!!, it.wake!!)
                if (allHaveAwake) p - it.awakeMinutes!! else p
            }
            TypicalDuration.Median(median(mins), mains.size, subtractsAwakeTime = allHaveAwake)
        }

        val latencies = records.filter { r ->
            r.type == SleepType.NAP && r.inBed != null && r.onset != null &&
                r.inBed.toInstant().isAfter(from) && !r.inBed.toInstant().isAfter(nowI) &&
                !r.onset.isBefore(r.inBed)
        }.map { Duration.between(it.inBed!!.toInstant(), it.onset!!.toInstant()).toMinutes() }

        val napLatency = if (latencies.size < ClockStats.MIN_ENTRIES) {
            TypicalDuration.NotEnoughData(latencies.size)
        } else {
            TypicalDuration.Median(median(latencies), latencies.size, subtractsAwakeTime = false)
        }

        return PeriodSummary(days, mains.size, onset, wake, duration, napLatency)
    }
}
