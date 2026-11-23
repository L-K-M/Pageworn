package ch.lkmc.pageworn.playback

import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.sleep.SleepLength
import ch.lkmc.pageworn.core.timeline.Division
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The sleep timer the playback service runs for the playing book. */
sealed interface SleepState {
    /** The length a fresh timer of the same kind runs for, or null when none runs. */
    val length: SleepLength?

    data object Off : SleepState {
        override val length: SleepLength? get() = null
    }

    /** Pause after a wall-clock duration; [deadlineElapsedMs] is on the elapsed-realtime clock. */
    data class Timed(val minutes: Int, val deadlineElapsedMs: Long) : SleepState {
        override val length: SleepLength get() = SleepLength.Minutes(minutes)
    }

    /** Pause at the end of the current chapter, track or book; [boundaryMs] is book time. */
    data class EndOf(val division: Division, val boundaryMs: Long, val atBookEnd: Boolean) : SleepState {
        override val length: SleepLength get() = SleepLength.EndOfSegment
    }
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
    /** Wall time in milliseconds until the running timer stops playback, at the current speed. */
    val sleepRemainingMs: Long? = null,
    /** Set after a playback failure: the place is saved and the transport is disabled until repair. */
    val failed: Boolean = false,
    val ended: Boolean = false,
    /** The player holds [key]'s item; false while a chosen book is still being handed to it. */
    val held: Boolean = false,
    /**
     * Playback is requested and nothing in the player blocks it (not idle, ended or suppressed): the
     * condition under which Media3's own controls show Pause.
     */
    val playbackRequested: Boolean = false,
    /** A book whose Play was tapped and whose start the session has not reported yet. */
    val starting: BookKey? = null,
) {
    /**
     * Whether [book]'s key shows Pause. A key always does what it shows, so this alone decides its
     * face, and a tap that arrives after this changed does not undo the tap that changed it.
     */
    fun showsPause(book: BookKey): Boolean =
        if (starting != null) starting == book else key == book && playbackRequested

    /** [book] shows Pause but is not heard yet: its key shows that it is starting. */
    fun waitsForAudio(book: BookKey): Boolean = showsPause(book) && !(key == book && isPlaying)

    /** This state without its moving clock, for lists that draw only each book's key. */
    fun transportOnly(): LivePlayback = copy(positionMs = 0, sleepRemainingMs = null)
}

class PlaybackHub {
    private val _state = MutableStateFlow(LivePlayback())
    val state: StateFlow<LivePlayback> = _state.asStateFlow()

    /** The session's state. It ends the expectation of a start that it reports. */
    fun publish(state: LivePlayback) {
        _state.update { previous ->
            val starting = previous.starting?.takeUnless { it == state.key && state.held && (state.playbackRequested || state.failed) }
            state.copy(starting = starting)
        }
    }

    /** A Play tap: its key shows Pause at once, before the session has answered. */
    fun expectStart(key: BookKey) {
        _state.update { it.copy(starting = key) }
    }

    /** A Pause tap, or a start that did not happen. */
    fun endStart() {
        _state.update { it.copy(starting = null) }
    }

    fun clear() {
        _state.value = LivePlayback()
    }
}
