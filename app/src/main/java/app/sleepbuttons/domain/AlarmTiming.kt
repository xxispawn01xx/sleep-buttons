package app.sleepbuttons.domain

/**
 * Rules for late/stale alarms and clock changes. Pure functions; the controller
 * supplies System.currentTimeMillis(), SystemClock.elapsedRealtime() and the
 * boot count.
 */
object AlarmTiming {
    /** A nap alarm delivered more than this late is NOT sounded (prevents surprise alarms hours later). */
    const val NAP_GRACE_MS = 2 * 60_000L

    /** A melatonin reminder more than this late is recorded as missed — no catch-up prompt. */
    const val REMINDER_GRACE_MS = 15 * 60_000L

    /** How long a nap alarm rings before auto-silencing. */
    const val RING_TIMEOUT_MS = 10 * 60_000L

    fun graceFor(isNap: Boolean): Long = if (isNap) NAP_GRACE_MS else REMINDER_GRACE_MS

    fun isStale(triggerAtEpochMs: Long, nowEpochMs: Long, graceMs: Long): Boolean =
        nowEpochMs - triggerAtEpochMs > graceMs

    /**
     * Nap alarms keep elapsed-duration semantics. While still on the same boot,
     * the elapsed-realtime target is the truth, so a manual wall-clock change
     * moves the wall-clock trigger to match. After a reboot elapsed time resets,
     * so the stored wall-clock target is the best available estimate.
     */
    fun napTriggerEpoch(
        storedEpochMs: Long,
        storedElapsedMs: Long?,
        storedBootCount: Int?,
        currentBootCount: Int?,
        nowEpochMs: Long,
        nowElapsedMs: Long,
    ): Long {
        val sameBoot = storedElapsedMs != null && storedBootCount != null &&
            currentBootCount != null && storedBootCount == currentBootCount
        return if (sameBoot) nowEpochMs + (storedElapsedMs!! - nowElapsedMs) else storedEpochMs
    }
}
