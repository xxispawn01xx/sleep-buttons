package app.sleepbuttons.ui

import app.sleepbuttons.domain.Consistency
import app.sleepbuttons.domain.TypicalDuration
import app.sleepbuttons.domain.TypicalTime
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val FULL = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US)

fun fmt(t: LocalTime): String = t.format(TIME)
fun fmtFull(z: ZonedDateTime): String = z.format(FULL)
fun epochToZdt(ms: Long, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
    ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
fun fmtEpochFull(ms: Long) = fmtFull(epochToZdt(ms))
fun fmtEpochTime(ms: Long) = epochToZdt(ms).format(TIME)
fun minuteOfDayText(m: Int): String = fmt(LocalTime.of(m / 60, m % 60))

fun durationText(minutes: Long): String = "${minutes / 60}h ${"%02d".format(minutes % 60)}m"

fun typicalTimeText(t: TypicalTime): String = when (t) {
    is TypicalTime.NotEnoughData -> "Not enough data (${t.n} of 3 entries)"
    is TypicalTime.TooDispersed -> "Too spread out for a useful typical time (${t.n} entries)"
    is TypicalTime.Typical -> "about ${minuteOfDayText(t.minuteOfDay)} — ${consistencyText(t.consistency)}, ±${t.spreadMinutes} min"
}

fun consistencyText(c: Consistency) = when (c) {
    Consistency.CONSISTENT -> "fairly consistent"
    Consistency.SOMEWHAT_VARIABLE -> "somewhat variable"
    Consistency.VARIABLE -> "variable"
}

fun durationSummaryText(d: TypicalDuration, isNapLatency: Boolean = false): String = when (d) {
    is TypicalDuration.NotEnoughData -> "Not enough data (${d.n} of 3 entries)"
    is TypicalDuration.Median -> when {
        isNapLatency -> "median ${d.minutes} min from lying down to estimated onset (${d.n} naps)"
        d.subtractsAwakeTime -> "median ${durationText(d.minutes)} (sleep period minus logged awake time)"
        else -> "median ${durationText(d.minutes)} sleep-period estimate (onset to final wake; not measured sleep)"
    }
}
