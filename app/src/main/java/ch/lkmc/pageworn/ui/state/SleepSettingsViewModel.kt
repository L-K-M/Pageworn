package ch.lkmc.pageworn.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.pageworn.AppContainer
import ch.lkmc.pageworn.core.sleep.SleepLength
import ch.lkmc.pageworn.core.sleep.SleepWindow
import ch.lkmc.pageworn.data.SleepSettings
import ch.lkmc.pageworn.ui.model.Format
import ch.lkmc.pageworn.ui.model.SleepPresetRow
import ch.lkmc.pageworn.ui.model.SleepSettingsUiState
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Why a new timer length was refused. */
enum class AddResult { OK, OUT_OF_RANGE, DUPLICATE, FULL }

/** "1 minute" or "15 minutes". */
fun presetLabel(minutes: Int): String = Format.minutes(minutes)

/** The lengths in the summary's own order, e.g. "15, 30, 45 and 60 minutes". */
fun presetsSummary(presets: List<Int>): String = when {
    presets.isEmpty() -> ""
    presets.size == 1 -> presetLabel(presets[0])
    else -> presets.dropLast(1).joinToString(", ") + " and " + presetLabel(presets.last())
}

/** The Sleep timer row's summary, e.g. "15, 30, 45 and 60 minutes · Automatic 22:00 to 07:00". */
fun sleepSummary(settings: SleepSettings, locale: Locale = Locale.getDefault()): String {
    val automatic = if (settings.automatic) {
        "Automatic ${formatMinuteOfDay(settings.window.startMinute, locale)} to ${formatMinuteOfDay(settings.window.endMinute, locale)}"
    } else "Automatic off"
    return "${presetsSummary(settings.presets)} · $automatic"
}

/** A minute of the day in the device's short clock format, e.g. "22:00" or "10:00 PM". */
fun formatMinuteOfDay(minute: Int, locale: Locale = Locale.getDefault()): String =
    LocalTime.of(minute / 60, minute % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))

/** Whether [minutes] may join [presets]. */
fun addPresetResult(presets: List<Int>, minutes: Int): AddResult = when {
    minutes !in SleepLength.MIN_MINUTES..SleepLength.MAX_MINUTES -> AddResult.OUT_OF_RANGE
    minutes in presets -> AddResult.DUPLICATE
    presets.size >= SleepSettings.MAX_PRESETS -> AddResult.FULL
    else -> AddResult.OK
}

class SleepSettingsViewModel(private val container: AppContainer) : ViewModel() {
    val state: StateFlow<SleepSettingsUiState> = container.sleepSettings.settings
        .map { it.toUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), container.sleepSettings.settings.value.toUiState())

    init {
        // An edge passed while the phone was off or the app was not running applies now.
        viewModelScope.launch { container.sleepSettings.refresh() }
    }

    /** Adds a timer length, or reports why it cannot join the list. */
    fun addPreset(minutes: Int): AddResult {
        val current = container.sleepSettings.settings.value.presets
        val result = addPresetResult(current, minutes)
        if (result == AddResult.OK) container.sleepSettings.setPresets(current + minutes)
        return result
    }

    /** Removes a timer length; one length always stays. */
    fun removePreset(minutes: Int) {
        val current = container.sleepSettings.settings.value.presets
        if (current.size > 1) container.sleepSettings.setPresets(current - minutes)
    }

    /** Turns the daily window on or off, keeping its times. */
    fun setAutomatic(on: Boolean) {
        val current = container.sleepSettings.settings.value
        container.sleepSettings.setAutomatic(on, current.window)
    }

    /** Sets the window's start; false when it would equal the end. */
    fun setStart(minute: Int): Boolean = setWindow(start = minute)

    /** Sets the window's end; false when it would equal the start. */
    fun setEnd(minute: Int): Boolean = setWindow(end = minute)

    private fun setWindow(start: Int? = null, end: Int? = null): Boolean {
        val current = container.sleepSettings.settings.value
        val newStart = start ?: current.window.startMinute
        val newEnd = end ?: current.window.endMinute
        if (newStart == newEnd) return false
        container.sleepSettings.setAutomatic(current.automatic, SleepWindow(newStart, newEnd))
        return true
    }

    private fun SleepSettings.toUiState() = SleepSettingsUiState(
        presets = presets.map { SleepPresetRow(it, presetLabel(it)) },
        canRemove = presets.size > 1,
        canAdd = presets.size < SleepSettings.MAX_PRESETS,
        automatic = automatic,
        fromText = formatMinuteOfDay(window.startMinute),
        untilText = formatMinuteOfDay(window.endMinute),
        startMinute = window.startMinute,
        endMinute = window.endMinute,
    )
}
