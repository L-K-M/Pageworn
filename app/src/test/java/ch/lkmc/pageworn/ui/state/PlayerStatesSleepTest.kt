package ch.lkmc.pageworn.ui.state

import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.sleep.SleepLength
import ch.lkmc.pageworn.core.timeline.Division
import ch.lkmc.pageworn.data.SleepSettings
import ch.lkmc.pageworn.playback.LivePlayback
import ch.lkmc.pageworn.playback.SleepState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The Sleep sheet selects the length that is actually running. */
class PlayerStatesSleepTest {
    private val book = BookKey("profile", "item")

    @Test
    fun aCarriedOverTimerIsSelectedWhileTheModeIsOff() {
        val live = LivePlayback(
            key = book,
            sleep = SleepState.Timed(20, deadlineElapsedMs = 0),
            sleepRemainingMs = 1_150_000,
            playWhenReady = true,
        )

        val ui = PlayerStates.sleep(live, Division.CHAPTERS, SleepSettings(enabled = false), book)

        assertThat(ui.selected).isEqualTo(SleepLength.Minutes(20))
        assertThat(ui.remainingText).isNotNull()
        assertThat(ui.options.any { it.length == SleepLength.Minutes(20) }).isTrue()
    }

    @Test
    fun aRemovedPresetThatIsStillRunningKeepsItsRow() {
        val live = LivePlayback(key = book, sleep = SleepState.Timed(20, 0), sleepRemainingMs = 60_000)

        val ui = PlayerStates.sleep(live, Division.CHAPTERS, SleepSettings(enabled = true, length = SleepLength.Minutes(30)), book)

        assertThat(ui.selected).isEqualTo(SleepLength.Minutes(20))
        assertThat(ui.options.count { it.length == SleepLength.Minutes(20) }).isEqualTo(1)
    }

    @Test
    fun aRunningPresetThatStillExistsIsNotDuplicated() {
        val live = LivePlayback(key = book, sleep = SleepState.Timed(30, 0), sleepRemainingMs = 30_000)

        val ui = PlayerStates.sleep(live, Division.CHAPTERS, SleepSettings(enabled = true, length = SleepLength.Minutes(30)), book)

        assertThat(ui.selected).isEqualTo(SleepLength.Minutes(30))
        assertThat(ui.options.count { it.length == SleepLength.Minutes(30) }).isEqualTo(1)
    }

    @Test
    fun aTimerOnAnotherItemDoesNotLeakIntoThisSheet() {
        val live = LivePlayback(
            key = BookKey("profile", "other"),
            sleep = SleepState.Timed(20, 0),
            sleepRemainingMs = 1_000,
        )

        val ui = PlayerStates.sleep(live, Division.CHAPTERS, SleepSettings(enabled = false), book)

        assertThat(ui.selected).isNull()
        assertThat(ui.remainingText).isNull()
        assertThat(ui.options.none { it.length == SleepLength.Minutes(20) }).isTrue()
    }

    @Test
    fun aSynthesizedLengthJoinsThePresetOrder() {
        val live = LivePlayback(key = book, sleep = SleepState.Timed(20, 0), sleepRemainingMs = 60_000)
        val mode = SleepSettings(enabled = true, presets = listOf(5, 15, 30), length = SleepLength.Minutes(20))

        val ui = PlayerStates.sleep(live, Division.CHAPTERS, mode, book)

        assertThat(ui.options.map { it.length }).containsExactly(
            null, SleepLength.Minutes(5), SleepLength.Minutes(15), SleepLength.Minutes(20),
            SleepLength.Minutes(30), SleepLength.EndOfSegment,
        ).inOrder()
    }

    @Test
    fun aStaleSettingsLengthStillGetsItsOwnRow() {
        val mode = SleepSettings(enabled = true, presets = listOf(15, 30), length = SleepLength.Minutes(20))

        val ui = PlayerStates.sleep(LivePlayback(key = book), Division.CHAPTERS, mode, book)

        assertThat(ui.selected).isEqualTo(SleepLength.Minutes(20))
        assertThat(ui.options.any { it.length == SleepLength.Minutes(20) }).isTrue()
    }

    @Test
    fun withNoTimerTheModeChoosesTheSelection() {
        val off = PlayerStates.sleep(LivePlayback(key = book), Division.CHAPTERS, SleepSettings(enabled = false), book)
        assertThat(off.selected).isNull()

        val on = PlayerStates.sleep(
            LivePlayback(key = book), Division.CHAPTERS,
            SleepSettings(enabled = true, length = SleepLength.Minutes(30)), book,
        )
        assertThat(on.selected).isEqualTo(SleepLength.Minutes(30))
    }

    @Test
    fun aRunningChapterTimerSelectsEndOfChapter() {
        val live = LivePlayback(
            key = book,
            sleep = SleepState.EndOf(Division.CHAPTERS, boundaryMs = 75_000, atBookEnd = false),
            sleepRemainingMs = 40_000,
        )

        val ui = PlayerStates.sleep(live, Division.CHAPTERS, SleepSettings(enabled = false), book)

        assertThat(ui.selected).isEqualTo(SleepLength.EndOfSegment)
        assertThat(ui.remainingText).isNotNull()
    }
}
