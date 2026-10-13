package app.sleepbuttons

import android.app.Application
import app.sleepbuttons.alarm.AlarmController
import app.sleepbuttons.alarm.AndroidAlarmScheduler
import app.sleepbuttons.alarm.Notifications
import app.sleepbuttons.data.AppDatabase
import app.sleepbuttons.data.SettingsRepository

/** Tiny manual service locator — no DI framework needed for an app this size. */
class SleepButtonsApp : Application() {
    val db by lazy { AppDatabase.create(this) }
    val settings by lazy { SettingsRepository(this) }
    val scheduler by lazy { AndroidAlarmScheduler(this) }
    val controller by lazy { AlarmController(this, db, settings, scheduler) }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
