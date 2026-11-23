package ch.lkmc.pageworn.playback

import ch.lkmc.pageworn.core.BookKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The list-facing projections of live playback state (used by Library, Downloaded, widgets). */
class LivePlaybackTest {
    private val key = BookKey("profile", "item")

    @Test
    fun minuteCoarseCoarsensThePositionUpToWholeMinutes() {
        val live = LivePlayback(key = key, positionMs = 61_000, sleepRemainingMs = 12_000)
        val coarse = live.minuteCoarse()
        assertThat(coarse.positionMs).isEqualTo(120_000)
        assertThat(coarse.sleepRemainingMs).isNull()
    }

    @Test
    fun minuteCoarseKeepsZeroZeroSoAPlaceStillMeansAPlace() {
        assertThat(LivePlayback(key = key, positionMs = 0).minuteCoarse().positionMs).isEqualTo(0)
        // One millisecond is still a place, not "never played".
        assertThat(LivePlayback(key = key, positionMs = 1).minuteCoarse().positionMs).isEqualTo(60_000)
    }

    @Test
    fun minuteCoarseLeavesTransportFlagsExact() {
        val live = LivePlayback(key = key, playWhenReady = true, positionMs = 5_000, starting = key)
        val coarse = live.minuteCoarse()
        assertThat(coarse.showsPause(key)).isTrue()
        assertThat(coarse.waitsForAudio(key)).isTrue()
        assertThat(coarse.key).isEqualTo(key)
    }

    @Test
    fun positionsInOneMinuteCoarsenEqualSoTicksDoNotRebuildRows() {
        val a = LivePlayback(key = key, positionMs = 10_000).minuteCoarse()
        val b = LivePlayback(key = key, positionMs = 59_999).minuteCoarse()
        assertThat(a).isEqualTo(b)
    }
}
