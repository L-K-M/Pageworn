package ch.lkmc.pageworn.core.progress

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The rewind table of docs/05-playback-and-formats.md, with the arithmetic of A12b, A13 and A29. */
class ResumeRewindTest {
    private val minute = 60_000L
    private val saved = 1_000_000_000L

    private fun start(position: Long, kind: StopKind, restMs: Long, finished: Boolean = false) =
        ResumeRewind.startPosition(position, kind, saved, saved + restMs, finished)

    @Test
    fun a29OrdinaryStops() {
        assertThat(start(115_000, StopKind.ORDINARY, 4 * minute + 59_000)).isEqualTo(115_000)
        assertThat(start(120_000, StopKind.ORDINARY, 5 * minute)).isEqualTo(110_000)
        assertThat(start(8_000, StopKind.ORDINARY, 10 * minute)).isEqualTo(0)
        // Process death or another book played meanwhile: ordinary stop, ten seconds back.
        assertThat(start(115_000, StopKind.ORDINARY, 10 * minute)).isEqualTo(105_000)
        // A seek while paused makes the next start exact.
        assertThat(start(140_000, StopKind.EXACT, 10 * minute)).isEqualTo(140_000)
    }

    @Test
    fun a12bTimedStops() {
        assertThat(start(600_000, StopKind.TIMED, 4 * minute)).isEqualTo(600_000)
        assertThat(start(600_000, StopKind.TIMED, 5 * minute)).isEqualTo(300_000)
        assertThat(start(600_000, StopKind.TIMED, 8 * 60 * minute)).isEqualTo(300_000)
        assertThat(start(200_000, StopKind.TIMED, 10 * minute)).isEqualTo(0)
        assertThat(start(700_000, StopKind.EXACT, 10 * minute)).isEqualTo(700_000)
        // The listener's own pause stays ordinary even if a timer later expires while paused.
        assertThat(start(600_000, StopKind.ORDINARY, 10 * minute)).isEqualTo(590_000)
    }

    @Test
    fun a13ChapterStopResumesExactlyAfterEightHours() {
        assertThat(start(140_000, StopKind.EXACT, 8 * 60 * minute)).isEqualTo(140_000)
    }

    @Test
    fun clockMovedBackwardsCountsAsNoRest() {
        assertThat(ResumeRewind.startPosition(115_000, StopKind.ORDINARY, saved, saved - 60 * minute)).isEqualTo(115_000)
    }

    @Test
    fun finishedBookStartsFromTheBeginning() {
        assertThat(start(210_000, StopKind.EXACT, 0, finished = true)).isEqualTo(0)
    }
}
