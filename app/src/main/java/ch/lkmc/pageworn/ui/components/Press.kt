package ch.lkmc.pageworn.ui.components

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import ch.lkmc.pageworn.ui.theme.MIN_PRESS_VISIBLE_MS
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
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

/** Plays the haptic and sound of [feel]; call it once for each tap the control takes. */
@Composable
fun rememberTapFeel(feel: TapFeel): () -> Unit {
    val haptic = LocalHapticFeedback.current
    val sound = rememberTapSound(feel)
    return remember(haptic, feel, sound) {
        {
            haptic.performHapticFeedback(feel.haptic)
            sound()
        }
    }
}

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
