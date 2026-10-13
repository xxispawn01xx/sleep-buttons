package app.sleepbuttons.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.sleepbuttons.R
import app.sleepbuttons.domain.AlarmSlot
import app.sleepbuttons.ui.MainActivity

object Notifications {
    const val CH_ALARM = "nap_alarm"                 // ringing nap alarm (sound played by AlarmService)
    const val CH_ALARM_FALLBACK = "nap_alarm_fallback" // used only if the foreground service can't start
    const val CH_MELATONIN = "melatonin_reminder"
    const val CH_STATUS = "status"
    const val STATUS_ID = 9001

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val alarmAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_ALARM, "Nap alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Ringing nap alarms. Sound and vibration are played by the app."
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
                NotificationChannel(CH_ALARM_FALLBACK, "Nap alarms (fallback)", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Used only if the alarm sound service could not start."
                    setSound(alarmSoundUri(), alarmAttrs)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 800, 600, 800)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
                NotificationChannel(CH_MELATONIN, "Melatonin reminder", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "One-shot reminder for your saved plan."
                },
                NotificationChannel(CH_STATUS, "Status", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Missed or skipped alarms and other notices."
                },
            )
        )
    }

    fun alarmSoundUri(): Uri =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    fun openAppIntent(context: Context, requestCode: Int = 1): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun actionIntent(context: Context, action: String, slot: AlarmSlot, requestOffset: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, slot.requestCode + requestOffset,
            Intent(context, ActionReceiver::class.java).setAction(action)
                .setData(Uri.parse(slot.uri + "/" + action.substringAfterLast('.')))
                .putExtra(AndroidAlarmScheduler.EXTRA_SLOT, slot.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Notification for a ringing nap alarm: full-screen on lock screen where allowed, always a Dismiss action. */
    fun ringing(context: Context, slot: AlarmSlot, text: String, fallbackSound: Boolean): Notification {
        val fullScreen = PendingIntent.getActivity(
            context, slot.requestCode + 500,
            Intent(context, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(AndroidAlarmScheduler.EXTRA_SLOT, slot.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(context, if (fallbackSound) CH_ALARM_FALLBACK else CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(slot.label)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Dismiss", actionIntent(context, ActionReceiver.ACTION_DISMISS, slot, 100))
        if (slot == AlarmSlot.NAP_GET_UP) {
            b.addAction(0, "End nap (cancel backup)", actionIntent(context, ActionReceiver.ACTION_END_NAP, slot, 200))
        }
        val n = b.build()
        if (fallbackSound) n.flags = n.flags or Notification.FLAG_INSISTENT
        return n
    }

    fun melatonin(context: Context, text: String, withActions: Boolean): Notification {
        val slot = AlarmSlot.MELATONIN
        val b = NotificationCompat.Builder(context, CH_MELATONIN)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("Melatonin reminder")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent(context, 2))
            .setAutoCancel(!withActions)
        if (withActions) {
            b.addAction(0, "Taken", actionIntent(context, ActionReceiver.ACTION_TAKEN, slot, 100))
            b.addAction(0, "Skip", actionIntent(context, ActionReceiver.ACTION_SKIP, slot, 200))
        }
        return b.build()
    }

    @Suppress("MissingPermission") // guarded by areNotificationsEnabled()
    fun postStatus(context: Context, text: String) {
        val nmc = NotificationManagerCompat.from(context)
        if (!nmc.areNotificationsEnabled()) return
        nmc.notify(
            STATUS_ID,
            NotificationCompat.Builder(context, CH_STATUS)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle("Sleep Buttons")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openAppIntent(context, 3))
                .setAutoCancel(true)
                .build(),
        )
    }

    @Suppress("MissingPermission")
    fun post(context: Context, id: Int, n: Notification) {
        val nmc = NotificationManagerCompat.from(context)
        if (nmc.areNotificationsEnabled()) nmc.notify(id, n)
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)
}
