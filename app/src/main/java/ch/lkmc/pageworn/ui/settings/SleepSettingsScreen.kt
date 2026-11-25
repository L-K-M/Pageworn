package ch.lkmc.pageworn.ui.settings

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.ui.components.PaperCard
import ch.lkmc.pageworn.ui.components.PaperField
import ch.lkmc.pageworn.ui.components.PaperRule
import ch.lkmc.pageworn.ui.components.PaperScreen
import ch.lkmc.pageworn.ui.components.SectionLabel
import ch.lkmc.pageworn.ui.components.TextAction
import ch.lkmc.pageworn.ui.components.paperSwitchColors
import ch.lkmc.pageworn.ui.components.rememberTapSound
import ch.lkmc.pageworn.ui.model.SleepSettingsUiState
import ch.lkmc.pageworn.ui.state.AddResult
import ch.lkmc.pageworn.ui.theme.PagewornTheme

@Composable
fun SleepSettingsScreen(
    state: SleepSettingsUiState,
    onBack: () -> Unit,
    onAddPreset: (Int) -> AddResult,
    onRemovePreset: (Int) -> Unit,
    onSetAutomatic: (Boolean) -> Unit,
    onSetStart: (Int) -> Boolean,
    onSetEnd: (Int) -> Boolean,
) {
    val sound = rememberTapSound()
    var adding by rememberSaveable { mutableStateOf(false) }
    var pickingStart by rememberSaveable { mutableStateOf(false) }
    var pickingEnd by rememberSaveable { mutableStateOf(false) }
    var windowError by rememberSaveable { mutableStateOf(false) }

    PaperScreen("Sleep timer", onBack) {
        SectionLabel("Timer lengths")
        PaperCard {
            state.presets.forEachIndexed { index, preset ->
                if (index > 0) PaperRule(Modifier.padding(horizontal = 16.dp))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(preset.label, Modifier.weight(1f), color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.rowTitle)
                    if (state.canRemove) TextAction("Remove", spokenLabel = "Remove ${preset.label}") { onRemovePreset(preset.minutes) }
                }
            }
            if (state.canAdd) {
                PaperRule(Modifier.padding(horizontal = 16.dp))
                Row(Modifier.padding(start = 4.dp)) { TextAction("Add a length") { adding = true } }
            }
        }
        Note("The player also offers End of chapter.")

        SectionLabel("Turn on automatically")
        PaperCard {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .toggleable(value = state.automatic, role = Role.Switch) { sound(); onSetAutomatic(it) }
                    .semantics { contentDescription = "Turn on automatically every night" }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Every night", Modifier.weight(1f), color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.rowTitle)
                Switch(checked = state.automatic, onCheckedChange = null, colors = paperSwitchColors())
            }
            TimeRow("From", state.fromText) {
                windowError = false
                pickingStart = true
            }
            PaperRule(Modifier.padding(horizontal = 16.dp))
            TimeRow("Until", state.untilText) {
                windowError = false
                pickingEnd = true
            }
        }
        if (windowError) Text(
            "Choose different start and end times.",
            Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            color = PagewornTheme.colors.accent,
            style = PagewornTheme.type.status,
        )
        Note("Sleep mode turns on at the start time and off at the end time. You can still turn it on or off in the player.")
    }

    if (adding) AddLengthDialog(onConfirm = onAddPreset, onDismiss = { adding = false })
    if (pickingStart) TimePickerDialog("From", state.startMinute, onConfirm = { minute ->
        pickingStart = false
        windowError = !onSetStart(minute)
    }, onDismiss = { pickingStart = false })
    if (pickingEnd) TimePickerDialog("Until", state.endMinute, onConfirm = { minute ->
        pickingEnd = false
        windowError = !onSetEnd(minute)
    }, onDismiss = { pickingEnd = false })
}

/** The note under a Sleep card, in secondary ink. */
@Composable
private fun Note(text: String) {
    Text(text, Modifier.padding(start = 4.dp), color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.status)
}

/** A window time and the picker it opens. */
@Composable
private fun TimeRow(label: String, timeText: String, onClick: () -> Unit) {
    val sound = rememberTapSound()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { sound(); onClick() }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.rowTitle)
        Text(timeText, color = PagewornTheme.colors.primaryInk, style = PagewornTheme.type.time)
    }
}

/** A new length in minutes, refused in place with the reason left on screen. */
@Composable
private fun AddLengthDialog(onConfirm: (Int) -> AddResult, onDismiss: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val colors = PagewornTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a length", style = PagewornTheme.type.sheetTitle) },
        text = {
            Column {
                PaperField(
                    value = draft,
                    onValueChange = { draft = it; error = null },
                    label = "Minutes",
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                error?.let {
                    Text(
                        it,
                        Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        color = colors.accent,
                        style = PagewornTheme.type.status,
                    )
                }
            }
        },
        confirmButton = {
            TextAction("Add") {
                // A draft that is not a number is an out-of-range length, with the same guidance.
                val result = draft.trim().toIntOrNull()?.let(onConfirm) ?: AddResult.OUT_OF_RANGE
                if (result == AddResult.OK) onDismiss() else error = result.message()
            }
        },
        dismissButton = { TextAction("Cancel", onClick = onDismiss) },
        shape = DIALOG_SHAPE,
        containerColor = colors.raisedSheet,
        titleContentColor = colors.primaryInk,
        textContentColor = colors.secondaryInk,
        tonalElevation = 0.dp,
    )
}

/** The device's own clock format, so a 24-hour listener picks 22:00 and a 12-hour one 10 PM. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(title: String, initialMinute: Int, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val picker = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = DateFormat.is24HourFormat(context),
    )
    val colors = PagewornTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = PagewornTheme.type.sheetTitle) },
        text = { TimePicker(picker) },
        confirmButton = { TextAction("Set") { onConfirm(picker.hour * 60 + picker.minute) } },
        dismissButton = { TextAction("Cancel", onClick = onDismiss) },
        shape = DIALOG_SHAPE,
        containerColor = colors.raisedSheet,
        titleContentColor = colors.primaryInk,
        textContentColor = colors.primaryInk,
        tonalElevation = 0.dp,
    )
}

private fun AddResult.message(): String = when (this) {
    AddResult.DUPLICATE -> "That length is already there."
    AddResult.FULL -> "You can add up to eight lengths."
    else -> "Enter 1 to 720 minutes."
}

private val DIALOG_SHAPE = RoundedCornerShape(14.dp)
