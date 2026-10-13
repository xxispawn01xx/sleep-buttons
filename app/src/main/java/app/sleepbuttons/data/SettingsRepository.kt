package app.sleepbuttons.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.sleepbuttons.domain.Dose
import app.sleepbuttons.domain.DoseWindows
import app.sleepbuttons.domain.NapLogic
import app.sleepbuttons.domain.ReminderLogic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalTime

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val napFirstMin: Int = NapLogic.DEFAULT_FIRST_MIN,
    val napSecondMin: Int = NapLogic.DEFAULT_SECOND_MIN,
    val lateNapCutoff: LocalTime = NapLogic.DEFAULT_LATE_CUTOFF,
    /** Null until the user enters AND confirms a dose. Never defaulted. */
    val doseMicrograms: Int? = null,
    val planConfirmed: Boolean = false,
    val reminderTime: LocalTime = ReminderLogic.DEFAULT_REMINDER_TIME,
    val doseWindowStart: LocalTime = DoseWindows.DEFAULT_START,
    val doseWindowEnd: LocalTime = DoseWindows.DEFAULT_END,
) {
    val confirmedDose: Dose?
        get() = if (planConfirmed && doseMicrograms != null && doseMicrograms > 0) Dose(doseMicrograms) else null
}

class SettingsRepository(private val context: Context) {
    private object K {
        val napFirst = intPreferencesKey("nap_first_min")
        val napSecond = intPreferencesKey("nap_second_min")
        val lateCutoff = intPreferencesKey("late_cutoff_min")
        val doseMcg = intPreferencesKey("dose_mcg")
        val planConfirmed = booleanPreferencesKey("plan_confirmed")
        val reminderMin = intPreferencesKey("reminder_min")
        val windowStart = intPreferencesKey("window_start_min")
        val windowEnd = intPreferencesKey("window_end_min")
    }

    private fun LocalTime.toMin() = hour * 60 + minute
    private fun Int.toTime() = LocalTime.of(this / 60, this % 60)

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            napFirstMin = this[K.napFirst] ?: d.napFirstMin,
            napSecondMin = this[K.napSecond] ?: d.napSecondMin,
            lateNapCutoff = this[K.lateCutoff]?.toTime() ?: d.lateNapCutoff,
            doseMicrograms = this[K.doseMcg],
            planConfirmed = this[K.planConfirmed] ?: false,
            reminderTime = this[K.reminderMin]?.toTime() ?: d.reminderTime,
            doseWindowStart = this[K.windowStart]?.toTime() ?: d.doseWindowStart,
            doseWindowEnd = this[K.windowEnd]?.toTime() ?: d.doseWindowEnd,
        )
    }

    /** Auto-saved offsets (Home steppers / Settings fields). Persisted in DataStore, so they survive app restarts. */
    suspend fun saveNapOffsets(first: Int, second: Int) {
        if (NapLogic.validateOffsets(first, second) != null) return
        context.dataStore.edit {
            it[K.napFirst] = first
            it[K.napSecond] = second
        }
    }

    suspend fun saveLateCutoff(cutoff: LocalTime) {
        context.dataStore.edit { it[K.lateCutoff] = cutoff.toMin() }
    }

    /** Only called after the user has entered a dose and ticked the confirmation. */
    suspend fun saveConfirmedPlan(dose: Dose, reminderTime: LocalTime, windowStart: LocalTime, windowEnd: LocalTime) {
        require(DoseWindows.validate(windowStart, windowEnd) == null)
        context.dataStore.edit {
            it[K.doseMcg] = dose.micrograms
            it[K.planConfirmed] = true
            it[K.reminderMin] = reminderTime.toMin()
            it[K.windowStart] = windowStart.toMin()
            it[K.windowEnd] = windowEnd.toMin()
        }
    }

    suspend fun clearPlan() {
        context.dataStore.edit {
            it.remove(K.doseMcg)
            it[K.planConfirmed] = false
        }
    }
}
