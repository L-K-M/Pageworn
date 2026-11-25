package ch.lkmc.pageworn.ui.player

import ch.lkmc.pageworn.core.timeline.Division
import ch.lkmc.pageworn.core.timeline.Segment
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Where the book bar is notched, which notches fit, and when a drag reaches one. */
class ChapterNotchesTest {
    private val segments = listOf(
        Segment(0, 0, 25 * MINUTE, "One", generated = false),
        Segment(1, 25 * MINUTE, 50 * MINUTE, "Two", generated = false),
        Segment(2, 50 * MINUTE, 100 * MINUTE, "Three", generated = false),
    )
    private val book = 0L..100 * MINUTE

    @Test
    fun chaptersAndTracksAreNotchedButPartsAreNot() {
        assertThat(chapterBoundaries(Division.CHAPTERS, segments)).containsExactly(25 * MINUTE, 50 * MINUTE).inOrder()
        assertThat(chapterBoundaries(Division.TRACKS, segments)).containsExactly(25 * MINUTE, 50 * MINUTE).inOrder()
        // Ten-minute parts are not the book's own divisions.
        assertThat(chapterBoundaries(Division.PARTS, segments)).isEmpty()
        assertThat(chapterBoundaries(Division.CHAPTERS, segments.take(1))).isEmpty()
    }

    @Test
    fun aNotchStandsWhereTheThumbStandsAtThatTime() {
        assertThat(notchX(25 * MINUTE, book, 400f)).isWithin(0.01f).of(100f)
        // A scale that does not start at zero, such as a chapter's own.
        assertThat(notchX(30 * MINUTE, 20 * MINUTE..40 * MINUTE, 400f)).isWithin(0.01f).of(200f)
    }

    @Test
    fun theRoundedEndsCarryNoNotch() {
        val boundaries = listOf(MINUTE / 2, 50 * MINUTE, 100 * MINUTE - MINUTE / 2)

        // At 400 px half a minute is 2 px from an end, inside the 10 px of a rounded end.
        assertThat(visibleNotches(boundaries, book, 400f, endInsetPx = 10f, minSpacingPx = 12f)).containsExactly(50 * MINUTE)
    }

    @Test
    fun crowdedBoundariesShareOneNotch() {
        // Every minute from 10 to 20: 4 px apart on a 400 px bar, with notches at least 12 px apart.
        val boundaries = (10L..20L).map { it * MINUTE }

        val kept = visibleNotches(boundaries, book, 400f, endInsetPx = 10f, minSpacingPx = 12f)

        assertThat(kept).containsExactly(10 * MINUTE, 13 * MINUTE, 16 * MINUTE, 19 * MINUTE).inOrder()
        // Short chapters elsewhere do not take a well-separated notch with them.
        assertThat(visibleNotches(boundaries + 60 * MINUTE, book, 400f, 10f, 12f)).contains(60 * MINUTE)
    }

    @Test
    fun aBarNotYetMeasuredHasNoNotches() {
        assertThat(visibleNotches(listOf(50 * MINUTE), book, 0f, 10f, 12f)).isEmpty()
        assertThat(visibleNotches(listOf(50 * MINUTE), 0L..0L, 400f, 10f, 12f)).isEmpty()
    }

    @Test
    fun aDragTicksWhenItReachesANotchEitherWay() {
        val notches = listOf(25 * MINUTE, 50 * MINUTE)

        assertThat(crossesNotch(notches, 20 * MINUTE, 30 * MINUTE)).isTrue()
        assertThat(crossesNotch(notches, 30 * MINUTE, 20 * MINUTE)).isTrue()
        assertThat(crossesNotch(notches, 26 * MINUTE, 49 * MINUTE)).isFalse()
        // Landing on a notch ticks; moving off it again does not tick a second time.
        assertThat(crossesNotch(notches, 20 * MINUTE, 25 * MINUTE)).isTrue()
        assertThat(crossesNotch(notches, 25 * MINUTE, 30 * MINUTE)).isFalse()
        assertThat(crossesNotch(notches, 30 * MINUTE, 25 * MINUTE)).isTrue()
        assertThat(crossesNotch(notches, 25 * MINUTE, 20 * MINUTE)).isFalse()
        assertThat(crossesNotch(notches, 25 * MINUTE, 25 * MINUTE)).isFalse()
    }

    @Test
    fun aFastDragTicksAtMostOnceInAShortSpell() {
        val ticks = NotchTicks()

        assertThat(ticks.take(1_000)).isTrue()
        assertThat(ticks.take(1_016)).isFalse()
        assertThat(ticks.take(1_000 + NotchTicks.MIN_TICK_GAP_MS - 1)).isFalse()
        assertThat(ticks.take(1_000 + NotchTicks.MIN_TICK_GAP_MS)).isTrue()
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
