package ch.lkmc.pageworn.ui.components

import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import ch.lkmc.pageworn.ui.theme.MIN_PRESS_VISIBLE_MS
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How firmly a control answers a tap it takes. Android plays the haptic only while the phone's
 * touch feedback is on, so the setting needs no check here.
 */
enum class TapFeel(internal val haptic: HapticFeedbackType) {
    /** A drawn key or the leather button: the click of a key going down. */
    KEY(HapticFeedbackType.VirtualKey),

    /** A pencilled or typeset action: a lighter tick. */
    LIGHT(HapticFeedbackType.ContextClick),
}

internal val LocalPlayTapSound = staticCompositionLocalOf<(TapFeel) -> Unit> { {} }

/** Plays the sound of [feel] for a control that takes a tap. */
@Composable
internal fun rememberTapSound(feel: TapFeel = TapFeel.LIGHT): () -> Unit {
    val play = LocalPlayTapSound.current
    return remember(play, feel) { { play(feel) } }
}

@Composable
internal fun Modifier.feedbackClickable(
    role: Role,
    feel: TapFeel? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = LocalIndication.current,
    collapseDoubleTaps: Boolean = false,
    onClick: () -> Unit,
): Modifier {
    val feedback = rememberPressFeedback(feel, collapseDoubleTaps)
    return clickable(interactionSource, indication, enabled = enabled, role = role) {
        if (enabled && feedback.takeClick()) onClick()
    }.then(if (enabled) feedback.observePointer else Modifier)
}

@Composable
internal fun Modifier.feedbackSelectable(
    selected: Boolean,
    role: Role,
    feedbackEnabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val feedback = rememberPressFeedback()
    return selectable(selected = selected, role = role) {
        if (feedbackEnabled) feedback.takeClick()
        onClick()
    }.then(if (feedbackEnabled) feedback.observePointer else Modifier)
}

@Composable
internal fun Modifier.feedbackToggleable(value: Boolean, onValueChange: (Boolean) -> Unit): Modifier {
    val feedback = rememberPressFeedback()
    return toggleable(value = value, role = Role.Switch) {
        feedback.takeClick()
        onValueChange(it)
    }.then(feedback.observePointer)
}

@Composable
private fun rememberPressFeedback(feel: TapFeel? = null, collapseDoubleTaps: Boolean = false): PressFeedback {
    val haptic = LocalHapticFeedback.current
    val play = LocalPlayTapSound.current
    val cue by rememberUpdatedState<(TapFeel) -> Unit> { sound ->
        if (feel != null) haptic.performHapticFeedback(sound.haptic)
        play(sound)
    }
    val downFeel = feel ?: TapFeel.LIGHT
    return remember(downFeel, collapseDoubleTaps) { PressFeedback(downFeel, collapseDoubleTaps) { cue(it) } }
}

/**
 * The observer sits inside the Compose click modifier. It sees a touch down in Main before the
 * click modifier, then checks in Final that the down was consumed. Compose still handles
 * scrolling, cancellation and activation. Its press interactions can be delayed in scrollable
 * containers, so they are used for drawing only.
 */
private class PressFeedback(
    private val downFeel: TapFeel,
    private val collapseDoubleTaps: Boolean,
    private val playCue: (TapFeel) -> Unit,
) {
    private data class PointerRelease(
        val atMs: Long,
        val durationMs: Long,
        val downCuePlayed: Boolean,
        val doubleTapTimeoutMs: Long,
    )

    private var release: PointerRelease? = null
    private var lastTapMs: Long? = null

    val observePointer: Modifier = Modifier.pointerInput(this) {
        awaitEachGesture {
            val down = awaitFirstDown()
            awaitPointerEvent(PointerEventPass.Final)
            if (!down.isConsumed) return@awaitEachGesture

            val doubleTapTimeoutMs = viewConfiguration.doubleTapTimeoutMillis
            val downCuePlayed = !repeats(down.uptimeMillis, doubleTapTimeoutMs)
            if (downCuePlayed) playCue(downFeel)
            val up = waitForUpOrCancellation() ?: return@awaitEachGesture

            // The click callback runs later in the same Main pass. An unaccepted up must not
            // carry over to the next click or to a keyboard or accessibility activation.
            release = PointerRelease(up.uptimeMillis, up.uptimeMillis - down.uptimeMillis, downCuePlayed, doubleTapTimeoutMs)
            try {
                awaitPointerEvent(PointerEventPass.Final)
            } finally {
                release = null
            }
        }
    }

    fun takeClick(): Boolean {
        val pointer = release
        if (pointer == null) {
            playCue(downFeel)
            return true
        }

        val repeat = repeats(pointer.atMs, pointer.doubleTapTimeoutMs)
        lastTapMs = pointer.atMs
        if (repeat) return false
        when {
            // A second touch may start inside the double-tap window and end outside it.
            !pointer.downCuePlayed -> playCue(downFeel)
            pointer.durationMs >= RELEASE_CUE_AFTER_MS -> playCue(TapFeel.LIGHT)
        }
        return true
    }

    private fun repeats(atMs: Long, timeoutMs: Long): Boolean =
        collapseDoubleTaps && lastTapMs?.let { atMs - it < timeoutMs } == true
}

private const val RELEASE_CUE_AFTER_MS = 120L

/**
 * Whether a control is drawn pressed. A quick tap presses and releases within one frame, and one
 * in a scrolling list is only reported once the finger has left, so a press stays visible for a
 * moment after the release. Only the next press cuts that moment short: focus and hover changes
 * share the interaction stream and must not keep the control down.
 */
@Composable
fun rememberVisiblePress(interaction: InteractionSource): Boolean {
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(interaction) {
        var release: Job? = null
        interaction.interactions.collect {
            when (it) {
                is PressInteraction.Press -> {
                    release?.cancel()
                    pressed = true
                }
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    release?.cancel()
                    release = launch {
                        delay(MIN_PRESS_VISIBLE_MS)
                        pressed = false
                    }
                }
            }
        }
    }
    return pressed
}
