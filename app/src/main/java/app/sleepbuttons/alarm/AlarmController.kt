package app.sleepbuttons.alarm

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import app.sleepbuttons.data.AlarmStateEntity
import app.sleepbuttons.data.AppDatabase
import app.sleepbuttons.data.DoseLogEntity
import app.sleepbuttons.data.NapAttemptEntity
import app.sleepbuttons.data.SettingsRepository
import app.sleepbuttons.domain.AlarmSlot
import app.sleepbuttons.domain.AlarmStatus
import app.sleepbuttons.domain.AlarmTiming
import app.sleepbuttons.domain.Dose
import app.sleepbuttons.domain.DoseWindows
import app.sleepbuttons.domain.NapLogic
import app.sleepbuttons.domain.NapPlan
import app.sleepbuttons.domain.NapStartDecision
import app.sleepbuttons.domain.NapStartPolicy
import app.sleepbuttons.domain.ReminderLogic
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed interface NapResult {
    data class Started(val plan: NapPlan, val lateWarning: Boolean) : NapResult
    data class AlreadyActive(val states: List<AlarmStateEntity>) : NapResult
    data object IgnoredDuplicateTap : NapResult
    data class NotScheduled(val reason: String) : NapResult
}

sealed interface ReminderResult {
    data class Scheduled(val at: ZonedDateTime, val laterDate: Boolean, val dose: Dose) : ReminderResult
    data class AlreadyScheduled(val state: AlarmStateEntity) : ReminderResult
    data class DuplicateDose(val loggedAt: ZonedDateTime, val dose: Dose, val windowText: String) : ReminderResult
    data object NeedsPlan : ReminderResult
    data class NotScheduled(val reason: String) : ReminderResult
}

enum class ReconcileReason { APP_START, BOOT, PACKAGE_REPLACED, TIME_CHANGED, TIMEZONE_CHANGED, PERMISSION_GRANTED }

/**
 * Single owner of alarm state. All mutations go through a mutex so rapid taps,
 * receivers and reconciliation can't interleave. DB state is written as
 * SCHEDULED *before* registering with AlarmManager and rolled back to
 * NOT_SCHEDULED on failure, so the UI never claims success that didn't happen
 * and a crash in between is repaired by the next reconcile.
 */
class AlarmController(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val scheduler: AlarmScheduler,
) {
    private val mutex = Mutex()
    @Volatile private var lastNapStartMs: Long? = null

    private fun now() = System.currentTimeMillis()
    private fun elapsed() = SystemClock.elapsedRealtime()
    private fun bootCount(): Int? =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull()

    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    fun fmtTime(ms: Long): String = ZonedDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).format(timeFmt)

    private suspend fun put(slot: AlarmSlot, status: AlarmStatus, base: AlarmStateEntity?, note: String? = base?.note, mutate: (AlarmStateEntity) -> AlarmStateEntity = { it }) {
        val b = base ?: AlarmStateEntity(slot = slot.id, status = status.name, triggerAtEpochMs = 0, updatedAtEpochMs = now())
        db.alarms().upsert(mutate(b.copy(status = status.name, note = note, updatedAtEpochMs = now())))
    }

    private fun readinessReason(): String? {
        val r = Permissions.readiness(context)
        return r.missingReason()
    }

    // ---------------------------------------------------------------- Nap

    private suspend fun activeNapStates(): List<AlarmStateEntity> =
        db.alarms().all().filter { it.alarmSlot.isNap && it.alarmStatus.isActive }

    suspend fun startNap(replace: Boolean): NapResult = mutex.withLock {
        val s = settings.current()
        NapLogic.validateOffsets(s.napFirstMin, s.napSecondMin)?.let { return NapResult.NotScheduled(it) }

        val active = activeNapStates()
        when (NapStartPolicy.decide(active.isNotEmpty(), replace, lastNapStartMs, now())) {
            NapStartDecision.IGNORE_DUPLICATE_TAP -> return NapResult.IgnoredDuplicateTap
            NapStartDecision.SHOW_EXISTING -> return NapResult.AlreadyActive(active)
            NapStartDecision.START -> Unit
        }
        readinessReason()?.let { return NapResult.NotScheduled(it) }

        if (active.isNotEmpty()) endNapLocked("replaced")

        val tap = now()
        val tapElapsed = elapsed()
        val boot = bootCount()
        val plan = NapLogic.plan(tap, s.napFirstMin, s.napSecondMin)
        val attemptId = db.naps().insert(
            NapAttemptEntity(
                startedAtEpochMs = tap, zoneId = ZoneId.systemDefault().id,
                firstOffsetMin = s.napFirstMin, secondOffsetMin = s.napSecondMin,
            )
        )
        val targets = listOf(
            AlarmSlot.NAP_GET_UP to (plan.getUpAtEpochMs to tapElapsed + s.napFirstMin * 60_000L),
            AlarmSlot.NAP_BACKUP to (plan.backupAtEpochMs to tapElapsed + s.napSecondMin * 60_000L),
        )
        for ((slot, t) in targets) {
            db.alarms().upsert(
                AlarmStateEntity(
                    slot = slot.id, status = AlarmStatus.SCHEDULED.name,
                    triggerAtEpochMs = t.first, triggerElapsedMs = t.second, bootCount = boot,
                    napAttemptId = attemptId, updatedAtEpochMs = tap,
                )
            )
        }
        for ((slot, t) in targets) {
            val r = scheduler.schedule(slot, t.first)
            if (r != ScheduleResult.Scheduled) {
                // All-or-nothing: never leave a half-scheduled session.
                for ((sl, _) in targets) {
                    scheduler.cancel(sl)
                    put(sl, AlarmStatus.NOT_SCHEDULED, db.alarms().get(sl.id), note = "Scheduling failed")
                }
                db.naps().end(attemptId, now(), "not_scheduled")
                db.naps().markLogHandled(attemptId)
                return NapResult.NotScheduled(
                    when (r) {
                        ScheduleResult.MissingExactAlarmPermission -> "\"Alarms & reminders\" access is off."
                        is ScheduleResult.Failed -> "Android refused the alarm: ${r.reason}"
                        else -> "Unknown error"
                    }
                )
            }
        }
        lastNapStartMs = tap
        val localNow = ZonedDateTime.ofInstant(Instant.ofEpochMilli(tap), ZoneId.systemDefault()).toLocalTime()
        NapResult.Started(plan, NapLogic.isLateNap(localNow, s.lateNapCutoff))
    }

    suspend fun endNap() = mutex.withLock { endNapLocked("ended_by_user") }

    private suspend fun endNapLocked(reason: String) {
        var attemptId: Long? = null
        for (slot in AlarmSlot.napSlots) {
            val st = db.alarms().get(slot.id) ?: continue
            attemptId = attemptId ?: st.napAttemptId
            if (st.alarmStatus.isActive) {
                scheduler.cancel(slot)
                put(slot, AlarmStatus.CANCELLED, st, note = reason)
            }
        }
        AlarmService.stopRinging(context)
        attemptId?.let { db.naps().end(it, now(), reason) }
    }

    /**
     * Cancels one scheduled nap alarm and leaves the other untouched — e.g. still awake,
     * so skip "get up" and keep the backup. Returns the remaining active alarm, if any.
     */
    suspend fun cancelNapSlot(slot: AlarmSlot): AlarmStateEntity? = mutex.withLock {
        require(slot.isNap)
        val st = db.alarms().get(slot.id)
        if (st != null && st.alarmStatus == AlarmStatus.SCHEDULED) {
            scheduler.cancel(slot)
            put(slot, AlarmStatus.CANCELLED, st, note = "Cancelled by user; other nap alarm kept")
        }
        maybeFinishNapSession()
        AlarmSlot.napSlots.filter { it != slot }
            .mapNotNull { db.alarms().get(it.id) }
            .firstOrNull { it.alarmStatus.isActive }
    }

    private suspend fun maybeFinishNapSession() {
        val states = AlarmSlot.napSlots.mapNotNull { db.alarms().get(it.id) }
        if (states.none { it.alarmStatus.isActive }) {
            states.firstNotNullOfOrNull { it.napAttemptId }?.let { db.naps().end(it, now(), "completed") }
        }
    }

    // ---------------------------------------------------------- Melatonin

    suspend fun setMelatoninReminder(): ReminderResult = mutex.withLock {
        val s = settings.current()
        val dose = s.confirmedDose ?: return ReminderResult.NeedsPlan

        val existing = db.alarms().get(AlarmSlot.MELATONIN.id)
        if (existing != null && existing.alarmStatus == AlarmStatus.SCHEDULED) {
            return ReminderResult.AlreadyScheduled(existing)
        }
        readinessReason()?.let { return ReminderResult.NotScheduled(it) }

        val zone = ZoneId.systemDefault()
        val nowZ = ZonedDateTime.now(zone)
        val occ = ReminderLogic.nextOccurrence(nowZ, s.reminderTime)

        if (DoseWindows.validate(s.doseWindowStart, s.doseWindowEnd) != null) {
            return ReminderResult.NotScheduled("Dose-check window start and end are the same. Fix it in Settings.")
        }
        val window = DoseWindows.windowContaining(occ.at, s.doseWindowStart, s.doseWindowEnd)
            ?: return ReminderResult.NotScheduled("Reminder time is outside the dose-check window. Adjust it in Settings.")

        val logged = db.doses().between(window.start.toInstant().toEpochMilli(), window.end.toInstant().toEpochMilli())
        if (logged.isNotEmpty()) {
            val first = logged.first()
            return ReminderResult.DuplicateDose(
                loggedAt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(first.takenAtEpochMs), zone),
                dose = Dose(first.doseMicrograms),
                windowText = "${window.start.format(timeFmt)} ${window.start.format(DateTimeFormatter.ofPattern("EEE", Locale.US))} – " +
                    "${window.end.format(timeFmt)} ${window.end.format(DateTimeFormatter.ofPattern("EEE", Locale.US))}",
            )
        }

        val trigger = occ.at.toInstant().toEpochMilli()
        db.alarms().upsert(
            AlarmStateEntity(
                slot = AlarmSlot.MELATONIN.id, status = AlarmStatus.SCHEDULED.name,
                triggerAtEpochMs = trigger, localDateTime = occ.at.toLocalDateTime().toString(),
                doseMicrograms = dose.micrograms, updatedAtEpochMs = now(),
            )
        )
        when (val r = scheduler.schedule(AlarmSlot.MELATONIN, trigger)) {
            ScheduleResult.Scheduled -> ReminderResult.Scheduled(occ.at, occ.isLaterDate, dose)
            else -> {
                put(AlarmSlot.MELATONIN, AlarmStatus.NOT_SCHEDULED, db.alarms().get(AlarmSlot.MELATONIN.id), note = "Scheduling failed")
                ReminderResult.NotScheduled(
                    if (r is ScheduleResult.Failed) "Android refused the reminder: ${r.reason}" else "\"Alarms & reminders\" access is off."
                )
            }
        }
    }

    suspend fun cancelMelatonin() = mutex.withLock {
        val st = db.alarms().get(AlarmSlot.MELATONIN.id) ?: return@withLock
        scheduler.cancel(AlarmSlot.MELATONIN)
        if (st.alarmStatus == AlarmStatus.SCHEDULED || st.alarmStatus == AlarmStatus.POSTED) {
            put(AlarmSlot.MELATONIN, AlarmStatus.CANCELLED, st, note = "Cancelled by user")
        }
        Notifications.cancel(context, AlarmSlot.MELATONIN.notificationId)
    }

    /** Taken is recorded ONLY from this explicit action, and only once per posted reminder. */
    suspend fun markTaken(): Boolean = mutex.withLock {
        val st = db.alarms().get(AlarmSlot.MELATONIN.id) ?: return@withLock false
        if (st.alarmStatus != AlarmStatus.POSTED) return@withLock false
        val mcg = st.doseMicrograms ?: settings.current().doseMicrograms ?: return@withLock false
        db.doses().upsert(
            DoseLogEntity(takenAtEpochMs = now(), doseMicrograms = mcg, source = "reminder", zoneId = ZoneId.systemDefault().id)
        )
        put(AlarmSlot.MELATONIN, AlarmStatus.TAKEN, st)
        Notifications.cancel(context, AlarmSlot.MELATONIN.notificationId)
        true
    }

    suspend fun markSkipped() = mutex.withLock {
        val st = db.alarms().get(AlarmSlot.MELATONIN.id) ?: return@withLock
        if (st.alarmStatus == AlarmStatus.POSTED) put(AlarmSlot.MELATONIN, AlarmStatus.SKIPPED, st)
        Notifications.cancel(context, AlarmSlot.MELATONIN.notificationId)
    }

    // -------------------------------------------------------- Alarm fired

    /** Called by AlarmReceiver. Returns true if a nap alarm should start ringing. */
    suspend fun onFired(slot: AlarmSlot): Boolean = mutex.withLock {
        val st = db.alarms().get(slot.id)
        if (st == null || st.alarmStatus != AlarmStatus.SCHEDULED) return@withLock false // cancelled/stale intent

        val trigger = st.triggerAtEpochMs
        if (AlarmTiming.isStale(trigger, now(), AlarmTiming.graceFor(slot.isNap))) {
            put(slot, AlarmStatus.MISSED, st, note = "Delivered late; not sounded")
            Notifications.postStatus(
                context,
                if (slot.isNap) "${slot.label} (${fmtTime(trigger)}) was delivered late and was not sounded. Stale nap alarms are skipped on purpose."
                else "Melatonin reminder for ${fmtTime(trigger)} was delivered late and recorded as missed. No catch-up reminder is created.",
            )
            if (slot.isNap) maybeFinishNapSession()
            return@withLock false
        }

        if (slot.isNap) {
            put(slot, AlarmStatus.RINGING, st)
            return@withLock true
        }

        // Melatonin: re-check the duplicate-dose window at fire time (a dose may have been logged manually).
        val s = settings.current()
        val zone = ZoneId.systemDefault()
        val mcg = st.doseMicrograms ?: s.doseMicrograms
        val firedAt = ZonedDateTime.now(zone)
        val window = if (DoseWindows.validate(s.doseWindowStart, s.doseWindowEnd) == null)
            DoseWindows.windowContaining(firedAt, s.doseWindowStart, s.doseWindowEnd) else null
        val already = window?.let {
            db.doses().between(it.start.toInstant().toEpochMilli(), it.end.toInstant().toEpochMilli())
        }.orEmpty()
        if (already.isNotEmpty()) {
            val t = fmtTime(already.first().takenAtEpochMs)
            Notifications.post(
                context, slot.notificationId,
                Notifications.melatonin(
                    context,
                    "You already logged a dose at $t in this evening window. Your saved plan is a single dose; this is not a prompt to take another.",
                    withActions = false,
                ),
            )
            put(slot, AlarmStatus.DUPLICATE_SUPPRESSED, st)
            return@withLock false
        }
        val doseText = mcg?.let { Dose(it).display() } ?: "(no saved dose)"
        Notifications.post(
            context, slot.notificationId,
            Notifications.melatonin(
                context,
                "Melatonin reminder: your saved plan is $doseText immediate-release. This reminder does not mean you have taken it.",
                withActions = true,
            ),
        )
        put(slot, AlarmStatus.POSTED, st)
        false
    }

    suspend fun onDismissed(slots: Collection<AlarmSlot>, timedOut: Boolean) = mutex.withLock {
        for (slot in slots) {
            val st = db.alarms().get(slot.id) ?: continue
            if (st.alarmStatus == AlarmStatus.RINGING) {
                put(slot, if (timedOut) AlarmStatus.TIMED_OUT else AlarmStatus.DISMISSED, st)
            }
        }
        maybeFinishNapSession()
    }

    suspend fun markRingFailed(slot: AlarmSlot, why: String) = mutex.withLock {
        val st = db.alarms().get(slot.id) ?: return@withLock
        put(slot, AlarmStatus.RINGING, st, note = "Fallback notification: $why")
    }

    // ------------------------------------------------------- Reconcile

    /**
     * Re-registers future alarms, marks stale ones missed (never sounds them late),
     * honours elapsed time for naps and wall-clock time for the reminder, and
     * reports honestly when permission is gone. Idempotent.
     */
    suspend fun reconcile(reason: ReconcileReason) = mutex.withLock {
        val ready = Permissions.readiness(context)
        val nowMs = now()
        val zone = ZoneId.systemDefault()
        for (st in db.alarms().all()) {
            val slot = st.alarmSlot
            when (st.alarmStatus) {
                AlarmStatus.RINGING -> if (!AlarmService.isRinging(slot)) {
                    // e.g. reboot while ringing — don't resume a stale alarm
                    put(slot, AlarmStatus.MISSED, st, note = "Interrupted while ringing ($reason)")
                }
                AlarmStatus.SCHEDULED -> {
                    val trigger = if (slot.isNap) AlarmTiming.napTriggerEpoch(
                        st.triggerAtEpochMs, st.triggerElapsedMs, st.bootCount, bootCount(), nowMs, elapsed(),
                    ) else st.localDateTime?.let {
                        ReminderLogic.resolveWallClock(LocalDateTime.parse(it), zone).toEpochMilli()
                    } ?: st.triggerAtEpochMs

                    if (AlarmTiming.isStale(trigger, nowMs, AlarmTiming.graceFor(slot.isNap))) {
                        scheduler.cancel(slot)
                        put(slot, AlarmStatus.MISSED, st, note = "Missed ($reason); not sounded late")
                        Notifications.postStatus(context, "${slot.label} for ${fmtTime(trigger)} was missed ($reason) and was not sounded late.")
                        continue
                    }
                    if (!ready.exactAlarmsAllowed) {
                        scheduler.cancel(slot)
                        put(slot, AlarmStatus.NOT_SCHEDULED, st, note = "\"Alarms & reminders\" access was turned off")
                        continue
                    }
                    val r = scheduler.schedule(slot, trigger)
                    if (r == ScheduleResult.Scheduled) {
                        put(slot, AlarmStatus.SCHEDULED, st) { e ->
                            e.copy(
                                triggerAtEpochMs = trigger,
                                triggerElapsedMs = if (slot.isNap) elapsed() + (trigger - nowMs) else null,
                                bootCount = if (slot.isNap) bootCount() else null,
                            )
                        }
                    } else {
                        put(slot, AlarmStatus.NOT_SCHEDULED, st, note = "Re-scheduling failed ($reason)")
                    }
                }
                else -> Unit
            }
        }
        maybeFinishNapSession()
    }
}
