package app.sleepbuttons.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private val Night = Color(0xFF6E7FD8)

@Composable
fun SleepTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(primary = Night, secondary = Color(0xFFB9A7E8))
        else lightColorScheme(primary = Color(0xFF3A4BA8), secondary = Color(0xFF6A55A6)),
        content = content,
    )
}

enum class Screen { HOME, SETTINGS, LOG }

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SleepTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
                var editing by rememberSaveable { mutableStateOf<Long?>(null) }
                var prefillNapId by rememberSaveable { mutableStateOf<Long?>(null) }

                val notifLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { vm.onResume() }
                val requestNotifications = {
                    if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else startActivity(app.sleepbuttons.alarm.Permissions.notificationSettings(this))
                }

                BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

                Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
                    when (screen) {
                        Screen.HOME -> HomeScreen(
                            vm = vm,
                            onRequestNotifications = requestNotifications,
                            onOpenSettings = { screen = Screen.SETTINGS },
                            onOpenLog = { screen = Screen.LOG },
                            onLogNap = { id -> prefillNapId = id; editing = NEW_ENTRY; screen = Screen.LOG },
                        )
                        Screen.SETTINGS -> SettingsScreen(vm, onRequestNotifications = requestNotifications, onBack = { screen = Screen.HOME })
                        Screen.LOG -> LogScreen(
                            vm = vm,
                            editingId = editing,
                            prefillNapId = prefillNapId,
                            onEdit = { editing = it },
                            onDoneEditing = { editing = null; prefillNapId = null },
                            onBack = { screen = Screen.HOME },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    companion object {
        const val NEW_ENTRY = -1L
        const val NEW_DOSE = -2L
    }
}

