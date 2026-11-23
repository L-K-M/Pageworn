package ch.lkmc.pageworn.ui.state

import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.playback.LivePlayback
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The Downloaded row clock follows transport changes, not the player's moving position. */
class DownloadedClockTest {
    private val key = BookKey("profile", "item")

    @Test
    fun positionsInsideOneCheckpointStepBuildTheSameRowState() {
        val early = LivePlayback(key = key, positionMs = 1_000, sleepRemainingMs = 90_000)
        val late = early.copy(positionMs = 4_999, sleepRemainingMs = 86_000)

        assertThat(early.forRows()).isEqualTo(late.forRows())
    }

    @Test
    fun aNewStepStillUpdatesTheRow() {
        val early = LivePlayback(key = key, positionMs = 1_000).forRows()
        val next = LivePlayback(key = key, positionMs = 5_000).forRows()

        assertThat(next.positionMs).isEqualTo(5_000)
        assertThat(early.positionMs).isEqualTo(0)
    }

    @Test
    fun transportChangesPassThroughAtOnce() {
        val playing = LivePlayback(key = key, positionMs = 900, isPlaying = true, playbackRequested = true)
        val paused = playing.copy(isPlaying = false, playbackRequested = false, playWhenReady = false)

        assertThat(playing.forRows()).isNotEqualTo(paused.forRows())
        assertThat(playing.forRows().showsPause(key)).isTrue()
        assertThat(paused.forRows().showsPause(key)).isFalse()
    }
}
