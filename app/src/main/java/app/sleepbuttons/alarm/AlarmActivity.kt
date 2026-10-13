package app.sleepbuttons.alarm

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import app.sleepbuttons.SleepButtonsApp
import app.sleepbuttons.domain.AlarmSlot
import app.sleepbuttons.ui.SleepTheme
import kotlinx.coroutines.launch

/** Full-screen ringing UI. Shown over the lock screen via the notification's full-screen intent. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = (application as SleepButtonsApp).controller

        setContent {
            SleepTheme {
                val ringing by AlarmService.ringing.collectAsState()
                LaunchedEffect(ringing) { if (ringing.isEmpty()) finish() }
                val slot = ringing.lastOrNull()
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(slot?.label ?: "Alarm", fontSize = 34.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text(controller.fmtTime(System.currentTimeMillis()), fontSize = 22.sp)
                        if (slot == AlarmSlot.NAP_GET_UP) {
                            Spacer(Modifier.height(8.dp))
                            Text("Dismissing keeps the backup alarm scheduled.", textAlign = TextAlign.Center)
                        }
                        Spacer(Modifier.height(32.dp))
                        Button(
                            onClick = { AlarmService.dismissRinging(); finish() },
                            modifier = Modifier.fillMaxWidth().height(88.dp),
                        ) { Text("Dismiss", fontSize = 26.sp) }
                        if (slot == AlarmSlot.NAP_GET_UP) {
                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(
                                onClick = {
                                    lifecycleScope.launch {
                                        controller.endNap()
                                        AlarmSlot.napSlots.forEach { Notifications.cancel(this@AlarmActivity, it.notificationId) }
                                        finish()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(64.dp),
                            ) { Text("End nap (also cancel backup)") }
                        }
                    }
                }
            }
        }
    }
}
