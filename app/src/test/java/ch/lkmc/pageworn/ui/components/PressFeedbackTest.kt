package ch.lkmc.pageworn.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoundEffect
import androidx.compose.ui.platform.SoundEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.KeyAction
import ch.lkmc.pageworn.ui.theme.InkWash
import ch.lkmc.pageworn.ui.theme.PagewornTheme
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Buttons answer a press: drawn keys and the leather button sink and click, pencilled and typeset
 * ones wash and tick, and each press shows for a moment however quick the tap
 * (docs/02-interface.md, Playback controls).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class PressFeedbackTest {
    @get:Rule
    val compose = createComposeRule()

    private val haptics = RecordingHaptics()
    private val sounds = mutableListOf<TapFeel>()
    private val platformSound = RecordingPlatformSound()

    @Test
    fun aKeyClicksOnceForEachTapItTakes() {
        var skips = 0
        show { SkipKey(back = false, width = 80.dp, onClick = { skips++ }) }

        compose.onNodeWithContentDescription(Copy.FORWARD_30).performClick()
        compose.onNodeWithContentDescription(Copy.FORWARD_30).performClick()

        assertThat(skips).isEqualTo(2)
        assertThat(haptics.played).containsExactly(HapticFeedbackType.VirtualKey, HapticFeedbackType.VirtualKey)
        assertThat(sounds).containsExactly(TapFeel.KEY, TapFeel.KEY)
    }

    @Test
    fun aQuickTouchClicksOnDownAndActsOnUp() {
        var skips = 0
        show { SkipKey(back = false, width = 80.dp, onClick = { skips++ }) }
        val key = compose.onNodeWithContentDescription(Copy.FORWARD_30)

        key.performTouchInput { down(center) }
        assertThat(haptics.played).containsExactly(HapticFeedbackType.VirtualKey)
        assertThat(sounds).containsExactly(TapFeel.KEY)
        assertThat(skips).isEqualTo(0)

        key.performTouchInput { up() }
        assertThat(haptics.played).hasSize(1)
        assertThat(sounds).hasSize(1)
        assertThat(skips).isEqualTo(1)
        assertThat(platformSound.clicks).isEqualTo(0)
    }

    @Test
    fun aHeldButtonAnswersReleaseWithALighterCue() {
        var taps = 0
        show { PaperButton("Connect", onClick = { taps++ }) }
        val button = compose.onNodeWithText("Connect")

        button.performTouchInput { down(center) }
        assertThat(haptics.played).containsExactly(HapticFeedbackType.VirtualKey)
        assertThat(sounds).containsExactly(TapFeel.KEY)
        assertThat(taps).isEqualTo(0)

        button.performTouchInput {
            advanceEventTime(HOLD_MS)
            up()
        }
        assertThat(haptics.played).containsExactly(HapticFeedbackType.VirtualKey, HapticFeedbackType.ContextClick).inOrder()
        assertThat(sounds).containsExactly(TapFeel.KEY, TapFeel.LIGHT).inOrder()
        assertThat(taps).isEqualTo(1)
    }

    @Test
    fun cancellingAHeldPressDoesNotGiveAReleaseCueOrActivate() {
        var taps = 0
        show { TextAction(Copy.DISMISS) { taps++ } }
        val action = compose.onNodeWithText(Copy.DISMISS)

        action.performTouchInput { down(center) }
        assertThat(haptics.played).containsExactly(HapticFeedbackType.ContextClick)
        assertThat(sounds).containsExactly(TapFeel.LIGHT)

        action.performTouchInput { cancel(HOLD_MS) }
        assertThat(haptics.played).hasSize(1)
        assertThat(sounds).hasSize(1)
        assertThat(taps).isEqualTo(0)

        action.performClick()
        assertThat(sounds).hasSize(2)
        assertThat(taps).isEqualTo(1)
    }

    @Test
    fun draggingFromAButtonScrollsWithoutActivatingIt() {
        var taps = 0
        val scroll = ScrollState(0)
        show {
            Column(Modifier.height(180.dp).verticalScroll(scroll)) {
                Spacer(Modifier.height(72.dp))
                TextAction(Copy.DISMISS) { taps++ }
                Spacer(Modifier.height(400.dp))
            }
        }
        val action = compose.onNodeWithText(Copy.DISMISS)

        action.performTouchInput { down(center) }
        assertThat(sounds).containsExactly(TapFeel.LIGHT)

        action.performTouchInput {
            moveBy(Offset(0f, -100f), HOLD_MS)
            up()
        }
        assertThat(scroll.value).isGreaterThan(0)
        assertThat(sounds).containsExactly(TapFeel.LIGHT)
        assertThat(haptics.played).containsExactly(HapticFeedbackType.ContextClick)
        assertThat(taps).isEqualTo(0)
    }

    @Test
    fun aDisabledKeyNeitherActsNorClicks() {
        var skips = 0
        show { SkipKey(back = true, width = 80.dp, onClick = { skips++ }, enabled = false, disabledReason = Copy.DAMAGED_COPY) }

        compose.onNodeWithContentDescription(Copy.BACK_15).performTouchInput { click() }
        compose.onNodeWithContentDescription(Copy.BACK_15).performClick()

        assertThat(skips).isEqualTo(0)
        assertThat(haptics.played).isEmpty()
        assertThat(sounds).isEmpty()
    }

    @Test
    fun theSecondTapOfADoubleTapIsTheSameTapAndClicksOnce() {
        val actions = mutableListOf<KeyAction>()
        show { PlayPauseKey(showsPause = false, title = "The Night Orchard", size = 80.dp) { actions += it } }

        compose.onNodeWithContentDescription("Play The Night Orchard").performTouchInput {
            click()
            advanceEventTime(DOUBLE_TAP_GAP_MS)
            click()
        }

        assertThat(actions).containsExactly(KeyAction.PLAY)
        assertThat(haptics.played).containsExactly(HapticFeedbackType.VirtualKey)
        assertThat(sounds).containsExactly(TapFeel.KEY)
    }

    @Test
    fun theLeatherButtonClicksLikeAKeyUnlessDisabled() {
        var taps = 0
        show { PaperButton("Connect", onClick = { taps++ }) }
        compose.onNodeWithText("Connect").performClick()
        assertThat(taps).isEqualTo(1)
        assertThat(haptics.played).containsExactly(HapticFeedbackType.VirtualKey)
        assertThat(sounds).containsExactly(TapFeel.KEY)
    }

    @Test
    fun aDisabledLeatherButtonStaysSilent() {
        var taps = 0
        show { PaperButton("Connect", onClick = { taps++ }, enabled = false) }
        compose.onNodeWithText("Connect").performTouchInput { click() }
        compose.onNodeWithText("Connect").performClick()
        assertThat(taps).isEqualTo(0)
        assertThat(haptics.played).isEmpty()
        assertThat(sounds).isEmpty()
    }

    @Test
    fun typesetAndPencilledActionsTickLightly() {
        var taps = 0
        show {
            Column {
                TextAction(Copy.DISMISS) { taps++ }
                PencilButton(onClick = { taps++ }) { Text("×", Modifier.semantics { contentDescription = "Clear search" }) }
                BackAction { taps++ }
            }
        }

        compose.onNodeWithText(Copy.DISMISS).performClick()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithText("Back").performClick()

        assertThat(taps).isEqualTo(3)
        assertThat(haptics.played).containsExactly(HapticFeedbackType.ContextClick, HapticFeedbackType.ContextClick, HapticFeedbackType.ContextClick)
        assertThat(sounds).containsExactly(TapFeel.LIGHT, TapFeel.LIGHT, TapFeel.LIGHT)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aKeyIsDrawnDownWhileHeldAndForAMomentAfterAQuickTap() {
        show { SkipKey(back = false, width = 80.dp, onClick = {}) }
        val key = compose.onNodeWithContentDescription(Copy.FORWARD_30)
        val resting = key.meanLuminance()
        compose.mainClock.autoAdvance = false

        key.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        assertWithMessage("held").that(key.meanLuminance()).isLessThan(resting - SHADED)

        key.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(STILL_SHOWN_MS)
        assertWithMessage("just released").that(key.meanLuminance()).isLessThan(resting - SHADED)

        compose.mainClock.advanceTimeBy(SETTLE_MS)
        assertWithMessage("back up").that(key.meanLuminance()).isWithin(RESTORED).of(resting)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aTypesetActionIsWashedWhilePressedInsteadOfRippling() {
        show { TextAction(Copy.DISMISS) {} }
        val action = compose.onNodeWithText(Copy.DISMISS)
        val resting = action.meanLuminance()
        compose.mainClock.autoAdvance = false

        action.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        val washed = action.meanLuminance()
        assertWithMessage("held").that(washed).isLessThan(resting - WASHED)

        action.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(STILL_SHOWN_MS)
        assertWithMessage("just released").that(action.meanLuminance()).isLessThan(resting - WASHED)

        compose.mainClock.advanceTimeBy(SETTLE_MS)
        assertWithMessage("faded").that(action.meanLuminance()).isWithin(RESTORED).of(resting)
    }

    @Test
    fun aPressEndsEvenWhenFocusOrHoverArrivesWhileItIsHeld() {
        // A click that moves focus, or a pointer leaving at once, reports a focus or hover change
        // during the moment a released press stays drawn; the press must still end.
        val source = MutableInteractionSource()
        var pressed: Boolean? = null
        compose.setContent { pressed = rememberVisiblePress(source) }
        compose.mainClock.autoAdvance = false
        val press = PressInteraction.Press(Offset.Zero)

        runBlocking {
            source.emit(press)
            compose.mainClock.advanceTimeByFrame()
            source.emit(PressInteraction.Release(press))
            source.emit(FocusInteraction.Focus())
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)

        assertThat(pressed).isFalse()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theInkWashFadesEvenWhenHoverChangesWhileItIsHeld() {
        val source = MutableInteractionSource()
        show { Box(Modifier.size(48.dp).semantics { contentDescription = "patch" }.indication(source, InkWash(PagewornTheme.colors.primaryInk))) }
        val patch = compose.onNodeWithContentDescription("patch")
        val resting = patch.meanLuminance()
        compose.mainClock.autoAdvance = false
        val press = PressInteraction.Press(Offset.Zero)
        val hover = HoverInteraction.Enter()

        runBlocking {
            source.emit(press)
            compose.mainClock.advanceTimeByFrame()
            source.emit(PressInteraction.Release(press))
            source.emit(hover)
            source.emit(HoverInteraction.Exit(hover))
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)

        assertWithMessage("faded").that(patch.meanLuminance()).isWithin(RESTORED).of(resting)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aQuickTapReportedAllAtOnceStillWashes() {
        // A quick tap in a scrolling list is reported as a press and its release together, once
        // the finger has left; the release must not cancel the press before it is drawn. The
        // test dispatcher runs launched work at once, so it cannot reproduce a release that
        // overtakes a queued press: that was seen on the emulator, by screen recording a quick
        // tap on Library's Sort control.
        val source = MutableInteractionSource()
        show { Box(Modifier.size(48.dp).semantics { contentDescription = "patch" }.indication(source, InkWash(PagewornTheme.colors.primaryInk))) }
        val patch = compose.onNodeWithContentDescription("patch")
        val resting = patch.meanLuminance()
        compose.mainClock.autoAdvance = false
        val press = PressInteraction.Press(Offset.Zero)

        runBlocking {
            source.emit(press)
            source.emit(PressInteraction.Release(press))
        }
        compose.mainClock.advanceTimeBy(STILL_SHOWN_MS)
        assertWithMessage("just released").that(patch.meanLuminance()).isLessThan(resting - WASHED)

        compose.mainClock.advanceTimeBy(SETTLE_MS)
        assertWithMessage("faded").that(patch.meanLuminance()).isWithin(RESTORED).of(resting)
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(
                LocalHapticFeedback provides haptics,
                LocalSoundEffect provides platformSound,
                LocalPlayTapSound provides { sounds += it },
            ) {
                PagewornTheme {
                    Box(Modifier.background(PagewornTheme.colors.paper).padding(24.dp)) { content() }
                }
            }
        }
    }

    private fun SemanticsNodeInteraction.meanLuminance(): Float {
        val pixels = captureToImage().toPixelMap()
        var sum = 0f
        for (x in 0 until pixels.width) for (y in 0 until pixels.height) sum += pixels[x, y].luminance()
        return sum / (pixels.width * pixels.height)
    }

    private class RecordingPlatformSound : SoundEffect {
        var clicks = 0
            private set

        override fun playClickSound() {
            clicks++
        }
    }

    private class RecordingHaptics : HapticFeedback {
        val played = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            played += hapticFeedbackType
        }
    }

    private companion object {
        /** Well inside the system's 300 ms double-tap time. */
        const val DOUBLE_TAP_GAP_MS = 120L
        const val HOLD_MS = 250L
        /** Long enough for a press or release animation to finish. */
        const val SETTLE_MS = 600L
        /** Within the 100 ms a press stays drawn after the finger leaves. */
        const val STILL_SHOWN_MS = 50L
        const val SHADED = 0.02f
        const val WASHED = 0.01f
        const val RESTORED = 0.005f
    }
}
