package app.sleepbuttons.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sleepbuttons.alarm.Permissions
import app.sleepbuttons.alarm.ReadinessState
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@Composable
fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))

/** Setup / reliability checklist. Required items block scheduling; the rest are honest warnings. */
@Composable
fun ReadinessCard(r: ReadinessState, onRequestNotifications: () -> Unit, showAll: Boolean) {
    val ctx = LocalContext.current
    fun open(i: Intent) = runCatching { ctx.startActivity(i) }.onFailure { ctx.startActivity(Permissions.appDetails(ctx)) }
    if (!showAll && r.canSchedule && r.fullScreenAllowed && !r.alarmVolumeMuted) return
    val err = !r.canSchedule
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (err) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (err) "Setup needed — alarms won't be scheduled until fixed" else "Alarm reliability",
                fontWeight = FontWeight.SemiBold)
            CheckRow("Alarms & reminders access (required)", r.exactAlarmsAllowed, "Open") { open(Permissions.exactAlarmSettings(ctx)) }
            CheckRow("Notifications (required — dismiss controls live here)", r.notificationsAllowed, "Allow", onRequestNotifications)
            CheckRow("Full-screen alarm on lock screen (recommended)", r.fullScreenAllowed, "Open") { open(Permissions.fullScreenSettings(ctx)) }
            CheckRow("Battery: unrestricted (recommended on some phones)", r.batteryUnrestricted, "Open") { open(Permissions.batterySettings()) }
            if (r.alarmVolumeMuted) Text("⚠ Alarm volume is at zero — nap alarms will vibrate only.", color = MaterialTheme.colorScheme.error)
            if (r.dndActive) Text("Do Not Disturb is on. Nap alarms use the alarm stream, which DND allows by default — check your DND settings if you changed that.")
            if (showAll) Text(
                "Limits: if you Force stop the app, Android cancels its alarms until you open it again. " +
                    "Some manufacturers' battery savers can delay or block alarms. " +
                    "Muted alarm volume or a DND rule that blocks alarms will silence them. No app can guarantee an alarm is impossible to miss.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text((if (ok) "✓ " else "✗ ") + label, modifier = Modifier.weight(1f))
        if (!ok) TextButton(onClick = onAction) { Text(action) }
    }
}

/** Date + time picker using the platform dialogs (no extra dependencies). Supports backdating. */
@Composable
fun DateTimeField(label: String, value: ZonedDateTime?, onChange: (ZonedDateTime?) -> Unit, optional: Boolean) {
    val ctx = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value?.let { fmtFull(it) } ?: if (optional) "Not set (optional)" else "Not set")
        }
        OutlinedButton(onClick = {
            val base = value ?: ZonedDateTime.now()
            DatePickerDialog(ctx, { _, y, m, d ->
                TimePickerDialog(ctx, { _, h, min ->
                    onChange(ZonedDateTime.of(LocalDate.of(y, m + 1, d), LocalTime.of(h, min), ZoneId.systemDefault()))
                }, base.hour, base.minute, false).show()
            }, base.year, base.monthValue - 1, base.dayOfMonth).show()
        }) { Text(if (value == null) "Set" else "Change") }
        if (optional && value != null) TextButton(onClick = { onChange(null) }) { Text("Clear") }
    }
}

@Composable
fun TimeField(label: String, value: LocalTime, onChange: (LocalTime) -> Unit) {
    val ctx = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("$label: ${fmt(value)}", modifier = Modifier.weight(1f))
        OutlinedButton(onClick = {
            TimePickerDialog(ctx, { _, h, m -> onChange(LocalTime.of(h, m)) }, value.hour, value.minute, false).show()
        }) { Text("Change") }
    }
}
