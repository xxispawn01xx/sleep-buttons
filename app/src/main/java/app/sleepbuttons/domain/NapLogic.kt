package app.sleepbuttons.domain

import java.time.LocalTime

data class NapPlan(
    val startedAtEpochMs: Long,
    val getUpAtEpochMs: Long,
    val backupAtEpochMs: Long,
)

object NapLogic {
    const val DEFAULT_FIRST_MIN = 35
    const val DEFAULT_SECOND_MIN = 40
    const val MAX_OFFSET_MIN = 240
    val DEFAULT_LATE_CUTOFF: LocalTime = LocalTime.of(14, 0)

    /** Returns null when valid, otherwise a user-facing reason. */
    fun validateOffsets(firstMin: Int, secondMin: Int): String? = when {
        firstMin <= 0 -> "Alarm 1 must be at least 1 minute after tapping Start nap."
        secondMin <= 0 -> "Backup alarm must be at least 1 minute after tapping Start nap."
        secondMin <= firstMin -> "Backup alarm must be later than alarm 1."
        secondMin > MAX_OFFSET_MIN -> "Keep offsets at or under $MAX_OFFSET_MIN minutes."
        else -> null
    }

    /** Offsets are measured from the tap, not from (unknowable) sleep onset. */
    fun plan(tapEpochMs: Long, firstMin: Int, secondMin: Int): NapPlan {
        require(validateOffsets(firstMin, secondMin) == null) { "invalid offsets" }
        return NapPlan(
            startedAtEpochMs = tapEpochMs,
            getUpAtEpochMs = tapEpochMs + firstMin * 60_000L,
            backupAtEpochMs = tapEpochMs + secondMin * 60_000L,
        )
    }

    /** Non-blocking: only used to show a warning. Late = at or after the cutoff, same day. */
    fun isLateNap(localNow: LocalTime, cutoff: LocalTime): Boolean = !localNow.isBefore(cutoff)
}

enum class NapStartDecision { START, SHOW_EXISTING, IGNORE_DUPLICATE_TAP }

/**
 * Guards "Start nap" against rapid double taps and silent duplicate sessions.
 * The controller also serialises calls with a mutex; this is the pure rule.
 */
object NapStartPolicy {
    const val DEBOUNCE_MS = 2_000L

    fun decide(
        sessionActive: Boolean,
        replaceRequested: Boolean,
        lastStartAtMs: Long?,
        nowMs: Long,
    ): NapStartDecision {
        if (lastStartAtMs != null && nowMs >= lastStartAtMs && nowMs - lastStartAtMs < DEBOUNCE_MS) {
            return NapStartDecision.IGNORE_DUPLICATE_TAP
        }
        if (sessionActive && !replaceRequested) return NapStartDecision.SHOW_EXISTING
        return NapStartDecision.START
    }
}
