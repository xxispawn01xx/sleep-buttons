package app.sleepbuttons.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.sleepbuttons.SleepButtonsApp
import app.sleepbuttons.domain.AlarmSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Runs suspend work while keeping the broadcast alive (goAsync gives ~10 s). */
private fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    receiverScope.launch {
        try {
            block()
        } catch (e: Exception) {
            Log.e("SleepButtons", "receiver failed", e)
        } finally {
            pending.finish()
        }
    }
}

private val Context.controller get() = (applicationContext as SleepButtonsApp).controller

/** Fired by AlarmManager for every slot. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidAlarmScheduler.ACTION_FIRE) return
        val slot = AlarmSlot.fromId(intent.getStringExtra(AndroidAlarmScheduler.EXTRA_SLOT)) ?: return
        val app = context.applicationContext
        runAsync {
            val shouldRing = app.controller.onFired(slot)
            if (shouldRing) {
                try {
                    // Must be called promptly after the alarm is delivered.
                    AlarmService.start(app, slot)
                } catch (e: Exception) {
                    Log.e("SleepButtons", "FGS start refused; using fallback notification", e)
                    Notifications.post(
                        app, slot.notificationId,
                        Notifications.ringing(app, slot, "Alarm (fallback mode — sound plays via notification).", fallbackSound = true),
                    )
                    app.controller.markRingFailed(slot, e.javaClass.simpleName)
                }
            }
        }
    }
}

/** Notification buttons: Dismiss / End nap / Taken / Skip. */
class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_DISMISS = "app.sleepbuttons.action.DISMISS"
        const val ACTION_END_NAP = "app.sleepbuttons.action.END_NAP"
        const val ACTION_TAKEN = "app.sleepbuttons.action.TAKEN"
        const val ACTION_SKIP = "app.sleepbuttons.action.SKIP"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val slot = AlarmSlot.fromId(intent.getStringExtra(AndroidAlarmScheduler.EXTRA_SLOT))
        when (intent.action) {
            ACTION_DISMISS -> {
                AlarmService.dismissRinging()
                // Fallback-mode notification (no service running): update state directly.
                if (slot != null) runAsync {
                    app.controller.onDismissed(listOf(slot), timedOut = false)
                    Notifications.cancel(app, slot.notificationId)
                }
            }
            ACTION_END_NAP -> runAsync {
                app.controller.endNap()
                AlarmSlot.napSlots.forEach { Notifications.cancel(app, it.notificationId) }
            }
            ACTION_TAKEN -> runAsync { app.controller.markTaken() }
            ACTION_SKIP -> runAsync { app.controller.markSkipped() }
        }
    }
}

/** Reboot, app update, clock/time-zone change, exact-alarm permission granted. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> ReconcileReason.BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> ReconcileReason.PACKAGE_REPLACED
            Intent.ACTION_TIME_CHANGED -> ReconcileReason.TIME_CHANGED
            Intent.ACTION_TIMEZONE_CHANGED -> ReconcileReason.TIMEZONE_CHANGED
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> ReconcileReason.PERMISSION_GRANTED
            else -> return
        }
        val app = context.applicationContext
        runAsync { app.controller.reconcile(reason) }
    }
}
