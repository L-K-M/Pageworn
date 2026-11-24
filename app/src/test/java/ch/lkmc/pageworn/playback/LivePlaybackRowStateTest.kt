package ch.lkmc.pageworn.playback

import ch.lkmc.pageworn.core.BookKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LivePlaybackRowStateTest {
    private val key = BookKey("profile", "book")

    @Test
    fun rowDisplayKeepsOnlyTheMinuteScalePlaceAndDropsTheCountdown() {
        val live = LivePlayback(
            key = key,
            isPlaying = true,
            playWhenReady = true,
            positionMs = 1_234,
            speed = 1.25f,
            sleepRemainingMs = 900,
            failed = true,
            ended = true,
            held = true,
            playbackRequested = true,
            starting = key,
        )
        assertThat(live.rowDisplay()).isEqualTo(live.copy(positionMs = 1, sleepRemainingMs = null))
    }

    @Test
    fun subMinutePositionTicksDrawTheSameRows() {
        val first = LivePlayback(key = key, positionMs = 61_000, playWhenReady = true).rowDisplay()
        val second = LivePlayback(key = key, positionMs = 95_999, playWhenReady = true).rowDisplay()

        assertThat(first.positionMs).isEqualTo(second.positionMs)
        assertThat(first.sameRowsAs(second)).isTrue()
    }

    @Test
    fun transportChangesStillReachTheRows() {
        val playing = LivePlayback(key = key, positionMs = 10_000, isPlaying = true, playWhenReady = true)
        val paused = playing.copy(isPlaying = false, playWhenReady = false, playbackRequested = false)

        assertThat(playing.sameRowsAs(paused)).isFalse()
    }
}
