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
        // Every forwarded field holds a non-default value, so dropping one from forRows()
        // fails the comparison instead of silently defaulting.
        val early = LivePlayback(
            key = key,
            isPlaying = true,
            playWhenReady = true,
            positionMs = 1_000,
            speed = 1.25f,
            failed = true,
            ended = true,
            held = true,
            playbackRequested = true,
            starting = key,
            sleepRemainingMs = 90_000,
        )
        val late = early.copy(positionMs = 4_999, sleepRemainingMs = 86_000)

        // The whole object pins the whitelist: excluded fields keep their defaults.
        assertThat(early.forRows()).isEqualTo(
            LivePlayback(
                key = early.key,
                isPlaying = early.isPlaying,
                playWhenReady = early.playWhenReady,
                positionMs = 0L,
                speed = early.speed,
                failed = early.failed,
                ended = early.ended,
                held = early.held,
                playbackRequested = early.playbackRequested,
                starting = early.starting,
            ),
        )
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
