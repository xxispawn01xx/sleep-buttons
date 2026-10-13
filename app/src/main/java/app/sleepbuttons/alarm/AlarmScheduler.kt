package app.sleepbuttons.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import app.sleepbuttons.domain.AlarmSlot
import app.sleepbuttons.ui.MainActivity

sealed interface ScheduleResult {
    data object Scheduled : ScheduleResult
    data object MissingExactAlarmPermission : ScheduleResult
    data class Failed(val reason: String) : ScheduleResult
}

/**
 * Boundary for "something that can ring at a time". The MVP uses AlarmManager;
 * a Google Clock (AlarmClock intent) backend could implement this later.
 */
interface AlarmScheduler {
    fun canScheduleExact(): Boolean
    fun schedule(slot: AlarmSlot, triggerAtEpochMs: Long): ScheduleResult
    fun cancel(slot: AlarmSlot)
}

class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {
    private val am = context.getSystemService(AlarmManager::class.java)

    override fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    override fun schedule(slot: AlarmSlot, triggerAtEpochMs: Long): ScheduleResult {
        if (!canScheduleExact()) return ScheduleResult.MissingExactAlarmPermission
        return try {
            val fire = firePendingIntent(context, slot)
            when (slot.kind) {
                // setAlarmClock: highest-priority user-visible alarm, exempt from Doze,
                // shown as "next alarm" by the system.
                AlarmSlot.Kind.RINGING_ALARM -> {
                    val show = PendingIntent.getActivity(
                        context, slot.showRequestCode,
                        Intent(context, MainActivity::class.java).setData(Uri.parse(slot.uri + "/show")),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtEpochMs, show), fire)
                }
                // Reminder is a notification, not a wake-up alarm, so it doesn't
                // masquerade as the system "next alarm".
                AlarmSlot.Kind.REMINDER ->
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtEpochMs, fire)
            }
            ScheduleResult.Scheduled
        } catch (e: SecurityException) {
            ScheduleResult.MissingExactAlarmPermission
        } catch (e: Exception) {
            ScheduleResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    override fun cancel(slot: AlarmSlot) {
        am.cancel(firePendingIntent(context, slot))
    }

    companion object {
        const val ACTION_FIRE = "app.sleepbuttons.action.ALARM_FIRE"
        const val EXTRA_SLOT = "slot"

        /** Stable identity: explicit component + action + per-slot data URI + per-slot request code. */
        fun firePendingIntent(context: Context, slot: AlarmSlot): PendingIntent {
            val intent = Intent(context, AlarmReceiver::class.java)
                .setAction(ACTION_FIRE)
                .setData(Uri.parse(slot.uri))
                .putExtra(EXTRA_SLOT, slot.id)
            return PendingIntent.getBroadcast(
                context, slot.requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
