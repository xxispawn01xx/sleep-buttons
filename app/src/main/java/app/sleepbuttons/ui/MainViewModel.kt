package app.sleepbuttons.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sleepbuttons.SleepButtonsApp
import app.sleepbuttons.alarm.AlarmService
import app.sleepbuttons.alarm.NapResult
import app.sleepbuttons.alarm.Permissions
import app.sleepbuttons.alarm.ReadinessState
import app.sleepbuttons.alarm.ReconcileReason
import app.sleepbuttons.alarm.ReminderResult
import app.sleepbuttons.data.AlarmStateEntity
import app.sleepbuttons.data.AppSettings
import app.sleepbuttons.data.DoseLogEntity
import app.sleepbuttons.data.NapAttemptEntity
import app.sleepbuttons.data.SleepEntryEntity
import app.sleepbuttons.domain.AlarmSlot
import app.sleepbuttons.domain.Dose
import app.sleepbuttons.domain.PeriodSummary
import app.sleepbuttons.domain.SleepSummarizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZonedDateTime

/** One-shot result shown as a card/dialog on the home screen. */
sealed interface UiMessage {
    data class Info(val text: String, val isError: Boolean = false) : UiMessage
    data class NapExisting(val states: List<AlarmStateEntity>) : UiMessage
    data class DuplicateDose(val text: String) : UiMessage
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val sb = app as SleepButtonsApp
    private val controller = sb.controller

    val settings: StateFlow<AppSettings> = sb.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val alarmStates: StateFlow<Map<AlarmSlot, AlarmStateEntity>> = sb.db.alarms().observeAll()
        .map { list -> list.associateBy { it.alarmSlot } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val sleepEntries: StateFlow<List<SleepEntryEntity>> = sb.db.sleep().observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val doseLogs: StateFlow<List<DoseLogEntity>> = sb.db.doses().observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val unloggedNap: StateFlow<NapAttemptEntity?> = sb.db.naps().observeUnloggedEnded()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val ringing: StateFlow<Set<AlarmSlot>> = AlarmService.ringing

    private val _tick = MutableStateFlow(System.currentTimeMillis())
    val summaries: StateFlow<Pair<PeriodSummary, PeriodSummary>?> =
        combine(sleepEntries, _tick) { entries, _ ->
            val records = entries.map { it.toRecord() }
            val now = ZonedDateTime.now()
            SleepSummarizer.summarize(records, now, 7) to SleepSummarizer.summarize(records, now, 14)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _readiness = MutableStateFlow(Permissions.readiness(app))
    val readiness: StateFlow<ReadinessState> = _readiness.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun clearMessage() { _message.value = null }

    /** Called from onResume: refresh permissions, re-register alarms, repair state. */
    fun onResume() {
        _readiness.value = Permissions.readiness(getApplication())
        _tick.value = System.currentTimeMillis()
        viewModelScope.launch { controller.reconcile(ReconcileReason.APP_START) }
    }

    private fun guarded(block: suspend () -> Unit) {
        if (_busy.value) return          // UI-level double-tap guard; controller has its own mutex
        _busy.value = true
        viewModelScope.launch {
            try { block() } finally { _busy.value = false }
        }
    }

    fun startNap(replace: Boolean = false) = guarded {
        _message.value = when (val r = controller.startNap(replace)) {
            is NapResult.Started -> UiMessage.Info(
                "Nap alarms set: get up ${controller.fmtTime(r.plan.getUpAtEpochMs)}, backup ${controller.fmtTime(r.plan.backupAtEpochMs)}." +
                    if (r.lateWarning) "\nHeads-up: this is after your late-nap cutoff (${fmt(settings.value.lateNapCutoff)}). Your choice — it's still set."
                    else ""
            )
            is NapResult.AlreadyActive -> UiMessage.NapExisting(r.states)
            NapResult.IgnoredDuplicateTap -> _message.value
            is NapResult.NotScheduled -> UiMessage.Info("Not scheduled: ${r.reason}", isError = true)
        }
        _readiness.value = Permissions.readiness(getApplication())
    }

    fun endNap() = guarded {
        controller.endNap()
        _message.value = UiMessage.Info("Nap ended. Remaining nap alarms cancelled.")
    }

    /** Mini "Cancel" next to the get-up alarm: the backup stays scheduled. */
    fun cancelGetUpOnly() = guarded {
        val remaining = controller.cancelNapSlot(AlarmSlot.NAP_GET_UP)
        _message.value = UiMessage.Info(
            "Get-up alarm cancelled. " +
                (remaining?.let { "Backup still set for ${controller.fmtTime(it.triggerAtEpochMs)}." } ?: "No nap alarms remain.")
        )
    }

    /** Auto-saved; applies to the next nap you start. */
    fun updateNapOffsets(first: Int, second: Int) = viewModelScope.launch {
        sb.settings.saveNapOffsets(first, second)
    }

    fun updateLateCutoff(t: LocalTime) = viewModelScope.launch { sb.settings.saveLateCutoff(t) }

    fun setReminder() = guarded {
        _message.value = when (val r = controller.setMelatoninReminder()) {
            is ReminderResult.Scheduled -> UiMessage.Info(
                "Melatonin reminder scheduled for ${fmtFull(r.at)}" +
                    (if (r.laterDate) " (tomorrow — today's time has passed; no catch-up reminder)" else "") +
                    ". Saved plan: ${r.dose.display()}."
            )
            is ReminderResult.AlreadyScheduled -> UiMessage.Info("A reminder is already scheduled: ${fmtFull(epochToZdt(r.state.triggerAtEpochMs))}. No duplicate created.")
            is ReminderResult.DuplicateDose -> UiMessage.DuplicateDose(
                "You already logged ${r.dose.display()} at ${fmtFull(r.loggedAt)}, inside the window ${r.windowText}. " +
                    "No reminder was scheduled for that window."
            )
            ReminderResult.NeedsPlan -> UiMessage.Info("Set and confirm your dose in Settings first. The app won't pick a dose for you.", isError = true)
            is ReminderResult.NotScheduled -> UiMessage.Info("Not scheduled: ${r.reason}", isError = true)
        }
        _readiness.value = Permissions.readiness(getApplication())
    }

    fun cancelReminder() = guarded {
        controller.cancelMelatonin()
        _message.value = UiMessage.Info("Melatonin reminder cancelled.")
    }

    fun markTaken() = guarded {
        _message.value = if (controller.markTaken()) UiMessage.Info("Logged as taken just now.")
        else UiMessage.Info("Nothing to mark — no reminder is waiting.")
    }

    fun markSkipped() = guarded { controller.markSkipped(); _message.value = UiMessage.Info("Marked as skipped.") }

    fun dismissRinging() = AlarmService.dismissRinging()

    // ---- settings
    fun savePlan(dose: Dose, time: LocalTime, ws: LocalTime, we: LocalTime) = viewModelScope.launch {
        sb.settings.saveConfirmedPlan(dose, time, ws, we)
        val pending = alarmStates.value[AlarmSlot.MELATONIN]?.alarmStatus == app.sleepbuttons.domain.AlarmStatus.SCHEDULED
        _message.value = UiMessage.Info(
            "Plan saved: ${dose.display()} at ${fmt(time)}." +
                if (pending) " The reminder already scheduled still uses the previous plan — cancel and set it again to use this one." else ""
        )
    }

    fun clearPlan() = viewModelScope.launch {
        sb.settings.clearPlan()
        _message.value = UiMessage.Info("Saved dose cleared.")
    }

    // ---- log
    fun saveSleep(e: SleepEntryEntity) = viewModelScope.launch {
        sb.db.sleep().upsert(e)
        _tick.value = System.currentTimeMillis()
    }

    fun deleteSleep(e: SleepEntryEntity) = viewModelScope.launch { sb.db.sleep().delete(e) }
    fun saveDose(e: DoseLogEntity) = viewModelScope.launch { sb.db.doses().upsert(e) }
    fun deleteDose(e: DoseLogEntity) = viewModelScope.launch { sb.db.doses().delete(e) }
    fun markNapLogHandled(id: Long) = viewModelScope.launch { sb.db.naps().markLogHandled(id) }
}
