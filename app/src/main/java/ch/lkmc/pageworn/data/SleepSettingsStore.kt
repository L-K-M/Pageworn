package ch.lkmc.pageworn.data

import android.content.Context
import ch.lkmc.pageworn.core.Clock
import ch.lkmc.pageworn.core.sleep.SleepLength
import ch.lkmc.pageworn.core.sleep.SleepSchedule
import ch.lkmc.pageworn.core.sleep.SleepWindow
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the listener has set up for sleep: whether the mode is on, its length, presets and schedule. */
data class SleepSettings(
    /** Sleep mode is on: every start of playback runs a timer of [length]. */
    val enabled: Boolean = false,
    val length: SleepLength = SleepLength.Minutes(DEFAULT_PRESETS[1]),
    /** The lengths in minutes that the sleep sheet offers, ascending and distinct. */
    val presets: List<Int> = DEFAULT_PRESETS,
    /** The mode turns itself on when [window] begins and off when it ends. */
    val automatic: Boolean = false,
    val window: SleepWindow = SleepWindow.DEFAULT,
) {
    companion object {
        val DEFAULT_PRESETS = listOf(15, 30, 45, 60)
        const val MAX_PRESETS = 8
    }
}

/**
 * Keeps [SleepSettings] in preferences and applies the schedule. The schedule acts on its edges:
 * when the window begins the mode turns on and when it ends the mode turns off, and between those
 * moments the listener's own choice stands. Turning the mode off at 23:00 therefore keeps it off
 * until the next evening, and turning it on at noon keeps it on until the window next ends.
 *
 * Edges are found lazily by [refresh]: the newest edge since the last decision (a choice, a
 * schedule change, or an edge already applied) wins, so a phone that was off at 22:00 still
 * finds the mode on at 23:00. A clock set backwards finds no edge until it passes that decision.
 *
 * Also remembers the last timer that stopped playback, for a Play shortly after it.
 */
class SleepSettingsStore(context: Context, private val clock: Clock, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    private val prefs = context.getSharedPreferences("pageworn-sleep", Context.MODE_PRIVATE)
    private val lock = Any()

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<SleepSettings> = _settings.asStateFlow()

    /** Turns the mode on with [length], or off for null. Replaces whatever the schedule decided. */
    fun choose(length: SleepLength?) = update { current ->
        if (length == null) current.copy(enabled = false) else current.copy(enabled = true, length = length)
    }

    /**
     * Replaces the presets. Values outside the allowed range are dropped, duplicates merged and at
     * most [SleepSettings.MAX_PRESETS] kept. An empty result is refused. A chosen length that is no
     * longer offered moves to the nearest preset; a timer already running keeps its length.
     */
    fun setPresets(minutes: List<Int>): Boolean {
        val clean = minutes.filter { it in SleepLength.MIN_MINUTES..SleepLength.MAX_MINUTES }.distinct().sorted()
        if (clean.isEmpty() || clean.size > SleepSettings.MAX_PRESETS) return false
        update(decided = false) { current ->
            val length = current.length
            val kept = if (length is SleepLength.Minutes && length.minutes !in clean) {
                SleepLength.Minutes(clean.minBy { kotlin.math.abs(it - length.minutes) })
            } else length
            current.copy(presets = clean, length = kept)
        }
        return true
    }

    /** Sets the schedule. Switching it on, or moving it, inside its window turns the mode on now. */
    fun setAutomatic(on: Boolean, window: SleepWindow) = update { current ->
        val inside = on && SleepSchedule.isInside(window, clock.wallMillis(), zone())
        current.copy(automatic = on, window = window, enabled = current.enabled || inside)
    }

    /**
     * Applies a schedule edge that passed since the last decision. Returns the new state of the mode
     * when an edge changed it, or null when nothing changed.
     */
    fun refresh(): Boolean? = synchronized(lock) {
        val current = _settings.value
        if (!current.automatic) return null
        val now = clock.wallMillis()
        val edge = SleepSchedule.latestEdge(current.window, now, zone())
        if (edge.atMillis <= prefs.getLong(DECIDED_AT, Long.MIN_VALUE)) return null
        write(current.copy(enabled = edge.turnsOn), decidedAt = now)
        edge.turnsOn.takeIf { it != current.enabled }
    }

    /** A timer of [length] stopped playback now. */
    fun recordStop(length: SleepLength) {
        prefs.edit().putString(STOPPED_LENGTH, encode(length)).putLong(STOPPED_AT, clock.elapsedRealtimeMillis()).apply()
    }

    /**
     * The length of the timer that stopped playback within [withinMs], if one did. Elapsed realtime
     * restarts with the phone, so a stop recorded "later" than now is from before a restart.
     */
    fun recentStop(withinMs: Long): SleepLength? {
        val length = decode(prefs.getString(STOPPED_LENGTH, null)) ?: return null
        val since = clock.elapsedRealtimeMillis() - prefs.getLong(STOPPED_AT, Long.MIN_VALUE)
        return length.takeIf { since in 0..withinMs }
    }

    fun forgetStop() {
        prefs.edit().remove(STOPPED_LENGTH).remove(STOPPED_AT).apply()
    }

    /** Applies [change]. A listener's decision also sets the moment edges are counted from. */
    private fun update(decided: Boolean = true, change: (SleepSettings) -> SleepSettings) = synchronized(lock) {
        write(change(_settings.value), decidedAt = if (decided) clock.wallMillis() else null)
    }

    private fun write(settings: SleepSettings, decidedAt: Long?) {
        val edit = prefs.edit()
            .putBoolean(ENABLED, settings.enabled)
            .putString(LENGTH, encode(settings.length))
            .putString(PRESETS, settings.presets.joinToString(","))
            .putBoolean(AUTOMATIC, settings.automatic)
            .putInt(WINDOW_START, settings.window.startMinute)
            .putInt(WINDOW_END, settings.window.endMinute)
        decidedAt?.let { edit.putLong(DECIDED_AT, it) }
        edit.apply()
        _settings.value = settings
    }

    private fun read(): SleepSettings {
        val defaults = SleepSettings()
        val presets = prefs.getString(PRESETS, null)?.split(",")?.mapNotNull { it.toIntOrNull() }
            ?.filter { it in SleepLength.MIN_MINUTES..SleepLength.MAX_MINUTES }?.distinct()?.sorted()
            ?.takeIf { it.isNotEmpty() } ?: defaults.presets
        val window = runCatching { SleepWindow(prefs.getInt(WINDOW_START, -1), prefs.getInt(WINDOW_END, -1)) }.getOrNull() ?: defaults.window
        return SleepSettings(
            enabled = prefs.getBoolean(ENABLED, defaults.enabled),
            length = decode(prefs.getString(LENGTH, null)) ?: defaults.length,
            presets = presets,
            automatic = prefs.getBoolean(AUTOMATIC, defaults.automatic),
            window = window,
        )
    }

    private companion object {
        const val ENABLED = "enabled"
        const val LENGTH = "length"
        const val PRESETS = "presets"
        const val AUTOMATIC = "automatic"
        const val WINDOW_START = "window_start"
        const val WINDOW_END = "window_end"
        const val DECIDED_AT = "decided_at"
        const val STOPPED_LENGTH = "stopped_length"
        const val STOPPED_AT = "stopped_at"
        const val END_OF_SEGMENT = "end"

        fun encode(length: SleepLength): String = when (length) {
            SleepLength.EndOfSegment -> END_OF_SEGMENT
            is SleepLength.Minutes -> length.minutes.toString()
        }

        fun decode(value: String?): SleepLength? = when {
            value == null -> null
            value == END_OF_SEGMENT -> SleepLength.EndOfSegment
            else -> value.toIntOrNull()?.takeIf { it in SleepLength.MIN_MINUTES..SleepLength.MAX_MINUTES }?.let { SleepLength.Minutes(it) }
        }
    }
}
