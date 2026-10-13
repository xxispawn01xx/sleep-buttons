package app.sleepbuttons.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.sleepbuttons.SleepButtonsApp
import app.sleepbuttons.domain.AlarmSlot
import app.sleepbuttons.domain.AlarmTiming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Plays a ringing nap alarm as a foreground service so playback survives the
 * receiver returning and the activity not existing. Started only from
 * AlarmReceiver (an exact alarm delivery, which is allowed to start an FGS from
 * the background). Stops itself on dismiss or after RING_TIMEOUT_MS.
 */
class AlarmService : Service() {

    companion object {
        private const val TAG = "AlarmService"
        const val ACTION_RING = "app.sleepbuttons.action.RING"
        private const val FGS_NOTIFICATION_ID = 4242

        private val _ringing = MutableStateFlow<Set<AlarmSlot>>(emptySet())
        /** Observed by AlarmActivity and the home screen. */
        val ringing: StateFlow<Set<AlarmSlot>> = _ringing

        @Volatile private var instance: AlarmService? = null

        fun isRinging(slot: AlarmSlot) = slot in _ringing.value

        /** Throws if the platform refuses a background FGS start; the caller handles fallback. */
        fun start(context: Context, slot: AlarmSlot) {
            val i = Intent(context, AlarmService::class.java)
                .setAction(ACTION_RING)
                .putExtra(AndroidAlarmScheduler.EXTRA_SLOT, slot.id)
            ContextCompat.startForegroundService(context, i)
        }

        /** Dismiss whatever is ringing. Never starts the service (safe from background). */
        fun dismissRinging(timedOut: Boolean = false) {
            instance?.let { svc -> svc.mainHandler.post { svc.dismissAll(timedOut) } }
        }

        /** Used by End nap: stop sound; state is already updated by the controller. */
        fun stopRinging(@Suppress("UNUSED_PARAMETER") context: Context) = dismissRinging(false)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val active = linkedSetOf<AlarmSlot>()
    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private val timeout = Runnable { dismissAll(timedOut = true) }
    private val toneLoop = object : Runnable {
        override fun run() {
            tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1000)
            mainHandler.postDelayed(this, 1600)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val slot = AlarmSlot.fromId(intent?.getStringExtra(AndroidAlarmScheduler.EXTRA_SLOT))
        if (intent?.action != ACTION_RING || slot == null) {
            // Restarted without a valid alarm (e.g. process death): never ring a stale alarm.
            stopEverything()
            return START_NOT_STICKY
        }
        val controller = (application as SleepButtonsApp).controller
        val text = "Rang at ${controller.fmtTime(System.currentTimeMillis())}. Offsets are counted from when you tapped Start nap."
        val notification = Notifications.ringing(this, slot, text, fallbackSound = false)

        // Publish ringing state before the notification so a full-screen launch sees it.
        active += slot
        _ringing.value = active.toSet()
        if (!enterForeground(notification)) {
            // Could not become a foreground service: fall back to an insistent alarm-channel notification.
            active -= slot
            _ringing.value = active.toSet()
            Notifications.post(this, slot.notificationId, Notifications.ringing(this, slot, text, fallbackSound = true))
            scope.launch { controller.markRingFailed(slot, "foreground service refused") }
            if (active.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        startSoundAndVibration()
        mainHandler.removeCallbacks(timeout)
        mainHandler.postDelayed(timeout, AlarmTiming.RING_TIMEOUT_MS)

        // The notification's full-screen intent shows AlarmActivity on a locked/off screen
        // (if allowed); while the phone is in use it appears as a heads-up with Dismiss.
        return START_NOT_STICKY
    }

    private fun enterForeground(n: android.app.Notification): Boolean {
        if (Build.VERSION.SDK_INT < 34) {
            return try {
                ServiceCompat.startForeground(this, FGS_NOTIFICATION_ID, n,
                    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
                true
            } catch (e: Exception) {
                Log.w(TAG, "startForeground failed", e); false
            }
        }
        // Android 14+: systemExempted is documented for apps holding exact-alarm permission
        // that use an FGS to continue an alarm; mediaPlayback is a fallback.
        for (type in intArrayOf(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )) {
            try {
                ServiceCompat.startForeground(this, FGS_NOTIFICATION_ID, n, type)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "startForeground type=$type failed", e)
            }
        }
        return false
    }

    private fun startSoundAndVibration() {
        if (player != null || tone != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val audio = getSystemService(AudioManager::class.java)
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs).build().also { audio.requestAudioFocus(it) }

        val candidates = listOfNotNull(
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        )
        for (uri in candidates) {
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(attrs)
                    setDataSource(this@AlarmService, uri)
                    isLooping = true
                    prepare()
                    start()
                }
                break
            } catch (e: Exception) {
                Log.w(TAG, "Could not play $uri", e)
                player?.release(); player = null
            }
        }
        if (player == null) {
            // No usable ringtone on the device: beep on the alarm stream.
            tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull()
            mainHandler.post(toneLoop)
        }

        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        }
        @Suppress("DEPRECATION")
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0), attrs)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepButtons:ring")
            .apply { acquire(AlarmTiming.RING_TIMEOUT_MS + 60_000L) }
    }

    /** Dismisses only what is currently ringing. Scheduled alarms (e.g. the backup) are untouched. */
    fun dismissAll(timedOut: Boolean) {
        val slots = active.toSet()
        if (slots.isNotEmpty()) {
            val controller = (application as SleepButtonsApp).controller
            scope.launch { controller.onDismissed(slots, timedOut) }
            if (timedOut) {
                Notifications.postStatus(this, "${slots.joinToString { it.label }} rang for 10 minutes with no response and was silenced.")
            }
        }
        stopEverything()
    }

    private fun stopEverything() {
        mainHandler.removeCallbacks(timeout)
        mainHandler.removeCallbacks(toneLoop)
        runCatching { player?.stop() }
        player?.release(); player = null
        tone?.release(); tone = null
        vibrator?.cancel()
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focusRequest = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        active.clear()
        _ringing.value = emptySet()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (active.isNotEmpty()) stopEverything()
        instance = null
        super.onDestroy()
    }
}
