package app.sleepbuttons.alarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** Snapshot of everything that affects whether alarms can work. */
data class ReadinessState(
    val exactAlarmsAllowed: Boolean,
    val notificationsAllowed: Boolean,
    val fullScreenAllowed: Boolean,
    val batteryUnrestricted: Boolean,
    val alarmVolumeMuted: Boolean,
    val dndActive: Boolean,
) {
    /** Required to schedule anything. Full-screen + battery are recommended, not required. */
    val canSchedule: Boolean get() = exactAlarmsAllowed && notificationsAllowed

    fun missingReason(): String? = when {
        !exactAlarmsAllowed -> "\"Alarms & reminders\" access is off."
        !notificationsAllowed -> "Notifications are off, so alarms couldn't show Dismiss controls."
        else -> null
    }
}

object Permissions {
    fun readiness(context: Context): ReadinessState {
        val am = context.getSystemService(AlarmManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        return ReadinessState(
            exactAlarmsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms(),
            notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            fullScreenAllowed = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent(),
            batteryUnrestricted = pm.isIgnoringBatteryOptimizations(context.packageName),
            alarmVolumeMuted = audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0,
            dndActive = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
        )
    }

    fun exactAlarmSettings(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        } else appDetails(context)

    fun notificationSettings(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun fullScreenSettings(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 34) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
        } else appDetails(context)

    /** Opens the system list; no special permission needed for this action. */
    fun batterySettings(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
}
