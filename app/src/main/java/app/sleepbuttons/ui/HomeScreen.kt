package app.sleepbuttons.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sleepbuttons.data.AlarmStateEntity
import app.sleepbuttons.domain.AlarmSlot
import app.sleepbuttons.domain.AlarmStatus
import app.sleepbuttons.domain.Dose
import app.sleepbuttons.domain.NapLogic
import java.time.LocalTime

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onRequestNotifications: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLog: () -> Unit,
    onLogNap: (Long) -> Unit,
) {
    val settings by vm.settings.collectAsState()
    val states by vm.alarmStates.collectAsState()
    val readiness by vm.readiness.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val ringing by vm.ringing.collectAsState()
    val summaries by vm.summaries.collectAsState()
    val unloggedNap by vm.unloggedNap.collectAsState()

    val napStates = AlarmSlot.napSlots.mapNotNull { states[it] }
    val napActive = napStates.any { it.alarmStatus.isActive }
    val mel = states[AlarmSlot.MELATONIN]
    val late = NapLogic.isLateNap(LocalTime.now(), settings.lateNapCutoff)

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Sleep Buttons", style = MaterialTheme.typography.headlineMedium)

        ReadinessCard(readiness, onRequestNotifications, showAll = false)

        if (ringing.isNotEmpty()) {
            Button(onClick = { vm.dismissRinging() }, modifier = Modifier.fillMaxWidth().height(72.dp)) {
                Text("Dismiss ${ringing.joinToString { it.label }}", fontSize = 20.sp)
            }
        }

        // ---------------------------------------------------------------- Nap
        Button(
            onClick = { vm.startNap() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(110.dp),
        ) { Text("Start nap", fontSize = 28.sp) }
        if (late && !napActive) {
            Text(
                "After your ${fmt(settings.lateNapCutoff)} late-nap cutoff. Starting a nap is still allowed.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
            )
        }
        // Timings: adjustable right here, saved instantly, kept across app restarts.
        var first by remember(settings.napFirstMin) { mutableIntStateOf(settings.napFirstMin) }
        var second by remember(settings.napSecondMin) { mutableIntStateOf(settings.napSecondMin) }
        OffsetStepper(
            label = "Get up",
            minutes = first,
            canDecrease = first > 1,
            canIncrease = first + 1 < second,
        ) { first = it; vm.updateNapOffsets(first, second) }
        OffsetStepper(
            label = "Backup",
            minutes = second,
            canDecrease = second - 1 > first,
            canIncrease = second < NapLogic.MAX_OFFSET_MIN,
        ) { second = it; vm.updateNapOffsets(first, second) }
        Text(
            "Minutes after you tap Start nap (not after falling asleep — the app can't detect sleep). " +
                "Changes save automatically and apply to the next nap" + if (napActive) "; the current nap keeps its times." else ".",
            style = MaterialTheme.typography.bodySmall,
        )
        if (napStates.isNotEmpty() && (napActive || napStates.any { it.alarmStatus == AlarmStatus.NOT_SCHEDULED })) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    napStates.forEach { st ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Box(Modifier.weight(1f)) { StatusLine(st) }
                            if (st.alarmSlot == AlarmSlot.NAP_GET_UP && st.alarmStatus == AlarmStatus.SCHEDULED) {
                                // Still awake? One tap skips "get up"; the backup stays set.
                                OutlinedButton(
                                    onClick = { vm.cancelGetUpOnly() },
                                    enabled = !busy,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    modifier = Modifier.height(36.dp),
                                ) { Text("Cancel") }
                            }
                        }
                    }
                    if (napActive) {
                        OutlinedButton(onClick = { vm.endNap() }, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) {
                            Text("End nap / cancel remaining alarm")
                        }
                    }
                }
            }
        }

        // ----------------------------------------------------------- Melatonin
        Button(
            onClick = { if (settings.confirmedDose == null) onOpenSettings() else vm.setReminder() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(110.dp),
        ) { Text("Set melatonin reminder", fontSize = 24.sp) }
        val dose = settings.confirmedDose
        Text(
            if (dose != null) "Saved plan: ${dose.display()} immediate-release at ${fmt(settings.reminderTime)} · user-entered, not a medical recommendation"
            else "No confirmed dose yet — tap to open Settings and enter your plan.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (mel != null) {
            when (mel.alarmStatus) {
                AlarmStatus.SCHEDULED -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Reminder scheduled: ${fmtEpochFull(mel.triggerAtEpochMs)}", fontWeight = FontWeight.SemiBold)
                        mel.doseMicrograms?.let { Text("Plan at scheduling: ${Dose(it).display()}") }
                        TextButton(onClick = { vm.cancelReminder() }) { Text("Cancel reminder") }
                    }
                }
                AlarmStatus.POSTED -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Reminder shown at ${fmtEpochTime(mel.triggerAtEpochMs)}. Not recorded as taken.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.markTaken() }, enabled = !busy) { Text("Taken") }
                            OutlinedButton(onClick = { vm.markSkipped() }, enabled = !busy) { Text("Skip") }
                        }
                    }
                }
                AlarmStatus.NOT_SCHEDULED -> StatusLine(mel)
                else -> Unit
            }
        }

        // ------------------------------------------------------------ Messages
        when (val m = message) {
            is UiMessage.Info -> Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (m.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(12.dp)) {
                    Text(m.text, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.clearMessage() }) { Text("OK") }
                }
            }
            is UiMessage.NapExisting -> AlertDialog(
                onDismissRequest = { vm.clearMessage() },
                title = { Text("A nap session is already active") },
                text = {
                    Column { m.states.forEach { Text("${it.alarmSlot.label}: ${fmtEpochTime(it.triggerAtEpochMs)} (${it.alarmStatus.name.lowercase()})") } }
                },
                confirmButton = { TextButton(onClick = { vm.clearMessage(); vm.startNap(replace = true) }) { Text("Replace with new nap from now") } },
                dismissButton = { TextButton(onClick = { vm.clearMessage() }) { Text("Keep current") } },
            )
            is UiMessage.DuplicateDose -> AlertDialog(
                onDismissRequest = { vm.clearMessage() },
                title = { Text("Dose already logged") },
                text = { Text(m.text) },
                confirmButton = { TextButton(onClick = { vm.clearMessage() }) { Text("OK") } },
            )
            null -> Unit
        }

        unloggedNap?.let { nap ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Nap attempt started ${fmtEpochFull(nap.startedAtEpochMs)} isn't logged. It only counts as sleep if you log an estimated onset.")
                    Row {
                        TextButton(onClick = { onLogNap(nap.id) }) { Text("Log it") }
                        TextButton(onClick = { vm.markNapLogHandled(nap.id) }) { Text("Don't log") }
                    }
                }
            }
        }

        // ------------------------------------------------------------- Summary
        summaries?.first?.let { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Last 7 days (from your log)", fontWeight = FontWeight.SemiBold)
                    Text("Main sleeps logged: ${s.mainSleepCount}")
                    Text("Sleep onset: ${typicalTimeText(s.onset)}")
                    Text("Final wake: ${typicalTimeText(s.wake)}")
                    Text("Duration: ${durationSummaryText(s.duration)}")
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onOpenLog) { Text("Log sleep") }
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
    }
}

@Composable
private fun OffsetStepper(
    label: String,
    minutes: Int,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("$label: +$minutes min", modifier = Modifier.weight(1f), fontSize = 18.sp)
        OutlinedButton(onClick = { onChange(minutes - 1) }, enabled = canDecrease,
            modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) { Text("−", fontSize = 22.sp) }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { onChange(minutes + 1) }, enabled = canIncrease,
            modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) { Text("+", fontSize = 22.sp) }
    }
}

@Composable
private fun StatusLine(s: AlarmStateEntity) {
    val text = when (s.alarmStatus) {
        AlarmStatus.SCHEDULED -> "${s.alarmSlot.label}: ${fmtEpochTime(s.triggerAtEpochMs)} — set"
        AlarmStatus.RINGING -> "${s.alarmSlot.label}: ringing"
        AlarmStatus.NOT_SCHEDULED -> "${s.alarmSlot.label}: Not scheduled${s.note?.let { " ($it)" } ?: ""}"
        else -> "${s.alarmSlot.label}: ${fmtEpochTime(s.triggerAtEpochMs)} — ${s.alarmStatus.name.lowercase().replace('_', ' ')}"
    }
    Text(
        text,
        color = if (s.alarmStatus == AlarmStatus.NOT_SCHEDULED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        fontWeight = if (s.alarmStatus.isActive) FontWeight.SemiBold else FontWeight.Normal,
    )
}
