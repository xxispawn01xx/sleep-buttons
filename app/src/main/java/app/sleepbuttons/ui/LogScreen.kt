package app.sleepbuttons.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import app.sleepbuttons.data.DoseLogEntity
import app.sleepbuttons.data.SleepEntryEntity
import app.sleepbuttons.domain.Dose
import app.sleepbuttons.domain.DoseParse
import app.sleepbuttons.domain.DoseUnit
import app.sleepbuttons.domain.PeriodSummary
import app.sleepbuttons.domain.SleepMath
import app.sleepbuttons.domain.SleepRecord
import app.sleepbuttons.domain.SleepType
import java.time.ZoneId
import java.time.ZonedDateTime

@Composable
fun LogScreen(
    vm: MainViewModel,
    editingId: Long?,
    prefillNapId: Long?,
    onEdit: (Long) -> Unit,
    onDoneEditing: () -> Unit,
    onBack: () -> Unit,
) {
    val entries by vm.sleepEntries.collectAsState()
    val doses by vm.doseLogs.collectAsState()
    val summaries by vm.summaries.collectAsState()
    val unloggedNap by vm.unloggedNap.collectAsState()

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (editingId != null) onDoneEditing() else onBack() }) { Text("‹ Back") }
            Text("Sleep log", style = MaterialTheme.typography.headlineSmall)
        }

        when {
            editingId == MainActivity.NEW_DOSE || (editingId != null && editingId < -2) -> {
                val existing = doses.firstOrNull { -it.id - 100 == editingId }
                DoseEditor(existing, onSave = { vm.saveDose(it); onDoneEditing() }, onCancel = onDoneEditing)
            }
            editingId != null -> {
                val existing = entries.firstOrNull { it.id == editingId }
                val napStart = if (existing == null && prefillNapId != null && unloggedNap?.id == prefillNapId)
                    unloggedNap?.startedAtEpochMs else null
                SleepEditor(
                    existing = existing,
                    prefillNapStartMs = napStart,
                    onSave = {
                        vm.saveSleep(it)
                        if (prefillNapId != null) vm.markNapLogHandled(prefillNapId)
                        onDoneEditing()
                    },
                    onCancel = onDoneEditing,
                )
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onEdit(MainActivity.NEW_ENTRY) }) { Text("Add sleep / nap") }
                    Button(onClick = { onEdit(MainActivity.NEW_DOSE) }) { Text("Log melatonin dose") }
                }
                Text("Backdating is fine — pick any past date to seed recent history.", style = MaterialTheme.typography.bodySmall)

                summaries?.let { (s7, s14) ->
                    SummaryCard(s7); SummaryCard(s14)
                }
                Text(
                    "Descriptive only, from what you logged. Not measured sleep, not a diagnosis, and not evidence about what caused any change.",
                    style = MaterialTheme.typography.bodySmall,
                )

                SectionTitle("Entries")
                if (entries.isEmpty()) Text("No entries yet.")
                entries.take(60).forEach { e ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(entryText(e), Modifier.weight(1f))
                            TextButton(onClick = { onEdit(e.id) }) { Text("Edit") }
                            TextButton(onClick = { vm.deleteSleep(e) }) { Text("Delete") }
                        }
                    }
                }

                SectionTitle("Melatonin doses (recorded only when you say so)")
                if (doses.isEmpty()) Text("None logged.")
                doses.take(60).forEach { d ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${fmtEpochFull(d.takenAtEpochMs)} · ${Dose(d.doseMicrograms).display()} · ${d.source}" +
                                    if (d.notes.isNotBlank()) "\n${d.notes}" else "",
                                Modifier.weight(1f),
                            )
                            TextButton(onClick = { onEdit(-d.id - 100) }) { Text("Edit") }
                            TextButton(onClick = { vm.deleteDose(d) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(s: PeriodSummary) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Last ${s.days} days", fontWeight = FontWeight.SemiBold)
            Text("Main sleeps logged: ${s.mainSleepCount}")
            Text("Typical sleep onset: ${typicalTimeText(s.onset)}")
            Text("Typical final wake: ${typicalTimeText(s.wake)}")
            Text("Typical duration: ${durationSummaryText(s.duration)}")
            Text("Nap sleep-onset latency: ${durationSummaryText(s.napLatency, isNapLatency = true)}")
        }
    }
}

private fun entryText(e: SleepEntryEntity): String {
    val r = e.toRecord()
    val type = if (r.type == SleepType.MAIN) "Main sleep" else "Nap"
    val parts = mutableListOf<String>()
    r.inBed?.let { parts += "lay down ${fmtFull(it)}" }
    if (r.onset != null) parts += "onset ${fmtFull(r.onset)}"
    else if (r.type == SleepType.NAP) parts += "no onset logged (attempt only)"
    r.wake?.let { parts += "wake ${fmtFull(it)}" }
    if (r.onset != null && r.wake != null) {
        val p = SleepMath.periodMinutes(r.onset, r.wake)
        parts += "period ${durationText(p)}" + (r.awakeMinutes?.let { ", awake $it min" } ?: "")
    }
    return "$type: " + parts.joinToString(" · ") + if (e.notes.isNotBlank()) "\n${e.notes}" else ""
}

@Composable
private fun SleepEditor(
    existing: SleepEntryEntity?,
    prefillNapStartMs: Long?,
    onSave: (SleepEntryEntity) -> Unit,
    onCancel: () -> Unit,
) {
    val rec = existing?.toRecord()
    var type by remember { mutableStateOf(rec?.type ?: if (prefillNapStartMs != null) SleepType.NAP else SleepType.MAIN) }
    var inBed by remember { mutableStateOf(rec?.inBed ?: prefillNapStartMs?.let { epochToZdt(it) }) }
    var onset by remember { mutableStateOf(rec?.onset) }
    var wake by remember { mutableStateOf(rec?.wake) }
    var awake by remember { mutableStateOf(rec?.awakeMinutes?.toString() ?: "") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }

    val awakeInt = awake.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
    val awakeError = awake.isNotBlank() && awakeInt == null
    val candidate = SleepRecord(type, if (type == SleepType.NAP) inBed else null, onset, wake, awakeInt)
    val error = if (awakeError) "Awake minutes must be a whole number." else SleepMath.validate(candidate)

    Text(if (existing == null) "New entry" else "Edit entry", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(type == SleepType.MAIN, { type = SleepType.MAIN }); Text("Main sleep")
        RadioButton(type == SleepType.NAP, { type = SleepType.NAP }); Text("Nap")
    }
    if (type == SleepType.NAP) {
        DateTimeField("Lay down / Start nap tap", inBed, { inBed = it }, optional = true)
        Text("Onset and wake are your own estimates. Leave onset empty if you didn't fall asleep.",
            style = MaterialTheme.typography.bodySmall)
    }
    DateTimeField("Estimated sleep onset", onset, { onset = it }, optional = type == SleepType.NAP)
    DateTimeField(if (type == SleepType.MAIN) "Final wake" else "Wake", wake, { wake = it }, optional = type == SleepType.NAP)
    OutlinedTextField(awake, { awake = it }, label = { Text("Awake minutes during the period (optional)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("groggy", "headache", "overslept").forEach { tag ->
            AssistChip(onClick = { notes = if (notes.isBlank()) tag else "$notes, $tag" }, label = { Text("+ $tag") })
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = error == null, onClick = {
            val zone = (onset ?: wake ?: inBed)?.zone ?: ZoneId.systemDefault()
            onSave(
                SleepEntryEntity(
                    id = existing?.id ?: 0,
                    type = type.name,
                    inBedEpochMs = if (type == SleepType.NAP) inBed?.toInstant()?.toEpochMilli() else null,
                    onsetEpochMs = onset?.toInstant()?.toEpochMilli(),
                    wakeEpochMs = wake?.toInstant()?.toEpochMilli(),
                    awakeMinutes = awakeInt,
                    notes = notes.trim(),
                    zoneId = zone.id,
                )
            )
        }) { Text("Save") }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun DoseEditor(existing: DoseLogEntity?, onSave: (DoseLogEntity) -> Unit, onCancel: () -> Unit) {
    var takenAt by remember { mutableStateOf<ZonedDateTime?>(existing?.let { epochToZdt(it.takenAtEpochMs, ZoneId.of(it.zoneId)) }) }
    var doseText by remember { mutableStateOf(existing?.doseMicrograms?.toString() ?: "") }
    var unit by remember { mutableStateOf(DoseUnit.MICROGRAMS) }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    val parse = if (doseText.isBlank()) null else Dose.parse(doseText, unit)

    Text(if (existing == null) "Log a dose you actually took" else "Edit dose log", style = MaterialTheme.typography.titleMedium)
    Text("This records history only. It never schedules or suggests another dose.", style = MaterialTheme.typography.bodySmall)
    DateTimeField("Taken at", takenAt, { takenAt = it }, optional = false)
    OutlinedTextField(doseText, { doseText = it }, label = { Text("Dose") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
        DoseUnit.entries.forEach { u ->
            FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u.short) },
                modifier = Modifier.padding(end = 8.dp))
        }
    }
    when (parse) {
        is DoseParse.Ok -> Text("= ${parse.dose.display()}")
        is DoseParse.Error -> Text(parse.message, color = MaterialTheme.colorScheme.error)
        null -> Unit
    }
    OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth())
    HorizontalDivider()
    val ok = parse as? DoseParse.Ok
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = ok != null && takenAt != null, onClick = {
            onSave(
                DoseLogEntity(
                    id = existing?.id ?: 0,
                    takenAtEpochMs = takenAt!!.toInstant().toEpochMilli(),
                    doseMicrograms = ok!!.dose.micrograms,
                    source = existing?.source ?: "manual",
                    zoneId = takenAt!!.zone.id,
                    notes = notes.trim(),
                )
            )
        }) { Text("Save") }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}
