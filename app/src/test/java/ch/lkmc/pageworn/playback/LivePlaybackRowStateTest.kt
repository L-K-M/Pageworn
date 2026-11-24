package ch.lkmc.pageworn.playback

import ch.lkmc.pageworn.core.BookKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LivePlaybackRowStateTest {
    private val key = BookKey("profile", "book")

    @Test
    fun rowDisplayKeepsOnlyTheMinuteScalePlaceAndDropsTheCountdown() {
        val displayed = LivePlayback(key = key, positionMs = 1_234, sleepRemainingMs = 900, isPlaying = true)
            .rowDisplay()

        assertThat(displayed.positionMs).isEqualTo(0)
        assertThat(displayed.sleepRemainingMs).isNull()
        assertThat(displayed.isPlaying).isTrue()
        assertThat(displayed.key).isEqualTo(key)
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
