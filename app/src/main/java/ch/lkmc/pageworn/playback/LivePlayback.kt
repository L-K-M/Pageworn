package ch.lkmc.pageworn.playback

import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.timeline.Division
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The sleep request the playback service holds. */
sealed interface SleepState {
    data object Off : SleepState

    /** Pause after a wall-clock duration; [deadlineElapsedMs] is on the elapsed-realtime clock. */
    data class Timed(val minutes: Int, val deadlineElapsedMs: Long) : SleepState

    /** Pause at the end of the current chapter, track or book; [boundaryMs] is book time. */
    data class EndOf(val division: Division, val boundaryMs: Long, val atBookEnd: Boolean) : SleepState
}

/**
 * One source of live player state for the UI, published by the playback service in this process.
 * Media-session callbacks remain the authority for system surfaces; this mirrors the same player.
 */
data class LivePlayback(
    val key: BookKey? = null,
    val plan: EditionPlan? = null,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val positionMs: Long = 0,
    val speed: Float = 1f,
    val sleep: SleepState = SleepState.Off,
    /** Remaining sleep time in milliseconds (wall time for timed mode, book time for end-of modes). */
    val sleepRemainingMs: Long? = null,
    /** Set after a playback failure: the place is saved and the transport is disabled until repair. */
    val failed: Boolean = false,
    val ended: Boolean = false,
)

class PlaybackHub {
    private val _state = MutableStateFlow(LivePlayback())
    val state: StateFlow<LivePlayback> = _state.asStateFlow()

    fun publish(state: LivePlayback) {
        _state.value = state
    }

    fun clear() {
        _state.value = LivePlayback()
    }
}
