package ch.lkmc.pageworn.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.timeline.BookTimeline
import ch.lkmc.pageworn.core.timeline.ChapterOrigin
import ch.lkmc.pageworn.core.timeline.ChapterSpan
import ch.lkmc.pageworn.playback.EditionPlan
import ch.lkmc.pageworn.playback.LivePlayback
import ch.lkmc.pageworn.ui.model.PlayerUiState
import ch.lkmc.pageworn.ui.state.PlayerStates
import ch.lkmc.pageworn.ui.theme.PagewornTheme
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The book bar shows where its chapters begin, and a drag ticks as it reaches one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class BookBarNotchesTest {
    @get:Rule
    val compose = createComposeRule()

    private val haptics = RecordingHaptics()
    private val key = BookKey("profile", "item")

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun chapterStartsAreCutIntoTheFilledBarAndMarkedOnTheRest() {
        show(chapters(), positionMs = 40 * MINUTE)
        assertNotched(rightToLeft = false)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aRightToLeftBarIsNotchedFromItsRightEnd() {
        // The slider fills from the right in a right-to-left layout, and the notches follow it.
        show(chapters(), positionMs = 40 * MINUTE, direction = LayoutDirection.Rtl)
        assertNotched(rightToLeft = true)
    }

    /** The bar of [chapters] at 40 minutes, filled from its left end or from its right. */
    private fun assertNotched(rightToLeft: Boolean) {
        val bar = compose.onNode(bookBar()).captureToImage().toPixelMap()
        val y = bar.height / 2
        // The bar's round ends reach its centre line, so the first and last inked pixels on that
        // line are its two ends, wherever the slider places it within its node.
        val paper = bar[0, y].luminance()
        val inked = (0 until bar.width).filter { kotlin.math.abs(bar[it, y].luminance() - paper) > PLAIN }
        val start = inked.first().toFloat()
        val length = (inked.last() - inked.first()).toFloat()
        fun at(fraction: Float) = bar[(start + length * (if (rightToLeft) 1f - fraction else fraction)).toInt(), y]
        val aside = with(compose.density) { NOTCH_ASIDE.toPx() } / length

        // Behind the thumb at 40 minutes, the notch at 25 is a cut: the paper shows through the ink.
        assertWithMessage("cut at 25 min against the fill beside it")
            .that(at(0.25f).luminance() - at(0.25f - aside).luminance()).isGreaterThan(MIN_CONTRAST)
        // Ahead of it, the notch at 75 is pencilled over the pale track.
        assertWithMessage("mark at 75 min against the track beside it")
            .that(at(0.75f + aside).luminance() - at(0.75f).luminance()).isGreaterThan(MIN_CONTRAST)
        // Between notches the bar is plain.
        assertThat(kotlin.math.abs(at(0.65f).luminance() - at(0.65f + aside).luminance())).isLessThan(PLAIN)
    }

    @Test
    fun aDragTicksOnlyWhenItReachesAChapter() {
        show(chapters(), positionMs = 40 * MINUTE)

        compose.onNode(bookBar()).performTouchInput {
            down(Offset(barX(width, 0.40f), centerY))
            moveTo(Offset(barX(width, 0.45f), centerY))
            moveTo(Offset(barX(width, 0.48f), centerY))
        }
        compose.waitForIdle()
        assertThat(haptics.played).isEmpty()

        compose.onNode(bookBar()).performTouchInput {
            moveTo(Offset(barX(width, 0.55f), centerY))
            moveTo(Offset(barX(width, 0.60f), centerY))
            up()
        }
        compose.waitForIdle()
        assertThat(haptics.played).containsExactly(HapticFeedbackType.SegmentTick)
    }

    @Test
    fun partsOfAFileWithoutChaptersAreNotNotched() {
        // 100 minutes in one file: ten parts, none of them a notch.
        val plan = EditionPlan(key, "gen", "fingerprint", "Single File", "Fixture Author", null, listOf(File("000.mp3")), BookTimeline(BookTimeline.contiguousTracks(listOf(100 * MINUTE)), emptyList()))
        show(PlayerStates.player(plan, null, null, LivePlayback()).copy(positionMs = 15 * MINUTE))

        compose.onNode(bookBar()).performTouchInput {
            down(Offset(barX(width, 0.15f), centerY))
            moveTo(Offset(barX(width, 0.25f), centerY))
            moveTo(Offset(barX(width, 0.45f), centerY))
            up()
        }
        compose.waitForIdle()
        assertThat(haptics.played).isEmpty()
    }

    /** Chapters start at 25, 50 and 75 minutes of a 100-minute book. */
    private fun chapters(): PlayerUiState {
        val chapters = listOf(0L, 25L, 50L, 75L, 100L).zipWithNext { a, b -> a to b }.mapIndexed { i, (a, b) ->
            ChapterSpan(a * MINUTE, b * MINUTE, "Chapter ${i + 1}", ChapterOrigin.SERVER)
        }
        val plan = EditionPlan(key, "gen", "fingerprint", "The Night Orchard", "Elin Ward", null, listOf(File("000.mp3")), BookTimeline(BookTimeline.contiguousTracks(listOf(100 * MINUTE)), chapters))
        return PlayerStates.player(plan, null, null, LivePlayback())
    }

    private fun show(state: PlayerUiState, positionMs: Long = state.positionMs, direction: LayoutDirection = LayoutDirection.Ltr) {
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics, LocalLayoutDirection provides direction) {
                PagewornTheme {
                    Box(Modifier.background(PagewornTheme.colors.paper).padding(horizontal = 20.dp)) {
                        PlayerScrubbers(state.copy(positionMs = positionMs), onSeekTo = {})
                    }
                }
            }
        }
    }

    private fun bookBar() = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and hasContentDescription("Book position")

    /**
     * About where [fraction] of the book lies across a node [width] wide: the slider's node is a
     * little wider than its bar, so the drags here stay a few percent clear of every notch.
     */
    private fun barX(width: Int, fraction: Float): Float = width * fraction

    private class RecordingHaptics : HapticFeedback {
        val played = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            played += hapticFeedbackType
        }
    }

    private companion object {
        const val MINUTE = 60_000L
        /** Far enough from a notch to sample the plain bar, near enough to share its fill. */
        val NOTCH_ASIDE = 6.dp
        const val MIN_CONTRAST = 0.15f
        const val PLAIN = 0.02f
    }
}
