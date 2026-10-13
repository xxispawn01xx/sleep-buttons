package app.sleepbuttons.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.sleepbuttons.domain.Dose
import app.sleepbuttons.domain.DoseParse
import app.sleepbuttons.domain.DoseUnit
import app.sleepbuttons.domain.DoseWindows
import app.sleepbuttons.domain.NapLogic
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(vm: MainViewModel, onRequestNotifications: () -> Unit, onBack: () -> Unit) {
    val s by vm.settings.collectAsState()
    val readiness by vm.readiness.collectAsState()
    val message by vm.message.collectAsState()

    // ---- nap form state
    // Auto-saved: values are written as soon as they're valid, only after you edit them
    // (so a field can never overwrite stored values with stale ones).
    var first by remember(s.napFirstMin) { mutableStateOf(s.napFirstMin.toString()) }
    var second by remember(s.napSecondMin) { mutableStateOf(s.napSecondMin.toString()) }
    var napEdited by remember { mutableStateOf(false) }
    val firstInt = first.trim().toIntOrNull()
    val secondInt = second.trim().toIntOrNull()
    val napError = if (firstInt == null || secondInt == null) "Enter whole minutes."
    else NapLogic.validateOffsets(firstInt, secondInt)
    LaunchedEffect(firstInt, secondInt, napEdited) {
        if (napEdited && napError == null && (firstInt != s.napFirstMin || secondInt != s.napSecondMin)) {
            delay(500) // let typing settle
            vm.updateNapOffsets(firstInt!!, secondInt!!)
        }
    }

    // ---- melatonin plan state (dose is never pre-filled with a suggested value)
    var doseText by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf(DoseUnit.MILLIGRAMS) }
    var confirmed by remember { mutableStateOf(false) }
    var reminderTime by remember(s.reminderTime) { mutableStateOf(s.reminderTime) }
    var windowStart by remember(s.doseWindowStart) { mutableStateOf(s.doseWindowStart) }
    var windowEnd by remember(s.doseWindowEnd) { mutableStateOf(s.doseWindowEnd) }
    LaunchedEffect(doseText, unit, reminderTime, windowStart, windowEnd) { confirmed = false }

    val parse = if (doseText.isBlank()) null else Dose.parse(doseText, unit)
    val windowError = DoseWindows.validate(windowStart, windowEnd)
        ?: if (!DoseWindows.timeIsInsideWindow(reminderTime, windowStart, windowEnd))
            "Reminder time must fall inside the dose-check window." else null

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }
        (message as? UiMessage.Info)?.let {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp)) {
                    Text(it.text, Modifier.weight(1f))
                    TextButton(onClick = { vm.clearMessage() }) { Text("OK") }
                }
            }
        }

        SectionTitle("Permissions & reliability")
        ReadinessCard(readiness, onRequestNotifications, showAll = true)

        HorizontalDivider()
        SectionTitle("Nap alarms")
        Text("Minutes after tapping Start nap. Defaults 35 / 40 (earlier you also used 40 / 45).",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(first, { first = it; napEdited = true }, label = { Text("Get up (min)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            OutlinedTextField(second, { second = it; napEdited = true }, label = { Text("Backup (min)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        }
        if (napError != null) Text("$napError Not saved yet.", color = MaterialTheme.colorScheme.error)
        else Text("Saved automatically. Also adjustable with − / + on the Home screen.", style = MaterialTheme.typography.bodySmall)
        TimeField("Late-nap warning after", s.lateNapCutoff) { vm.updateLateCutoff(it) }

        HorizontalDivider()
        SectionTitle("Melatonin plan")
        Text("User-entered plan; not a medical recommendation.", fontWeight = FontWeight.SemiBold)
        Text(
            "Historical context only: the plan you discussed before was a single low dose of 0.05–0.1 mg " +
                "(50–100 mcg) immediate-release around 8:30–9 PM. The app does not choose, change, or adjust a dose.",
            style = MaterialTheme.typography.bodySmall,
        )
        Card(Modifier.fillMaxWidth()) {
            Text(
                s.confirmedDose?.let { "Current saved plan: ${it.display()} immediate-release at ${fmt(s.reminderTime)}" }
                    ?: "No dose saved yet. Reminders can't be set until you enter and confirm one.",
                modifier = Modifier.padding(12.dp),
            )
        }
        OutlinedTextField(doseText, { doseText = it }, label = { Text("Dose") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            DoseUnit.entries.forEach { u ->
                RadioButton(selected = unit == u, onClick = { unit = u })
                Text(if (u == DoseUnit.MILLIGRAMS) "mg (milligrams)" else "mcg (micrograms)")
            }
        }
        when (parse) {
            is DoseParse.Ok -> {
                Text("= ${parse.dose.display()}", fontWeight = FontWeight.SemiBold)
                if (parse.outsideHistoricalRange) Text(
                    "Note: this is outside the 50–100 mcg range you noted before. Double-check the units.",
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            is DoseParse.Error -> Text(parse.message, color = MaterialTheme.colorScheme.error)
            null -> Unit
        }
        TimeField("Reminder time (suggested 8:30 PM)", reminderTime) { reminderTime = it }
        Text("Duplicate-dose check window: if a dose is logged in this window, no second reminder is set for it.",
            style = MaterialTheme.typography.bodySmall)
        TimeField("Window starts", windowStart) { windowStart = it }
        TimeField("Window ends (next morning)", windowEnd) { windowEnd = it }
        windowError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        val ok = parse as? DoseParse.Ok
        if (ok != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(confirmed, { confirmed = it })
                Text("I confirm my plan is ${ok.dose.microgramsText} micrograms = ${ok.dose.milligramsText} milligrams, entered by me.")
            }
        }
        Button(
            enabled = ok != null && confirmed && windowError == null,
            onClick = {
                vm.savePlan(ok!!.dose, reminderTime, windowStart, windowEnd)
                doseText = ""; confirmed = false
            },
        ) { Text("Save confirmed plan") }
        if (s.confirmedDose != null) TextButton(onClick = { vm.clearPlan() }) { Text("Clear saved dose") }

        HorizontalDivider()
        SectionTitle("About the summaries")
        Text(
            "Typical clock times use a circular average: each time is placed on a 24-hour clock face and averaged as a direction, " +
                "so 11:30 PM and 12:30 AM average to about midnight, not noon. The ± figure is the circular standard deviation. " +
                "With fewer than 3 entries, or when times are spread more than about 2 hours, no typical time is shown. " +
                "Limitations: small manual logs are noisy; a split pattern (e.g. some nights at 10 PM, others at 4 AM) has no meaningful average. " +
                "Summaries are descriptive only — they don't diagnose anything, don't estimate your body's melatonin timing, " +
                "and never change your dose or reminder.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
