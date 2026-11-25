package ch.lkmc.pageworn.ui.components

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import ch.lkmc.pageworn.ui.theme.MIN_PRESS_VISIBLE_MS
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

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

/** Plays the haptic of [feel]; call it once for each tap the control takes. */
@Composable
fun rememberTapFeel(feel: TapFeel): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember(haptic, feel) { { haptic.performHapticFeedback(feel.haptic) } }
}

/**
 * Whether a control is drawn pressed. A quick tap presses and releases within one frame, and one
 * in a scrolling list is only reported once the finger has left, so a press stays visible for a
 * moment after the release.
 */
@Composable
fun rememberVisiblePress(interaction: InteractionSource): Boolean {
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(interaction) {
        interaction.interactions.collectLatest {
            when (it) {
                is PressInteraction.Press -> pressed = true
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    delay(MIN_PRESS_VISIBLE_MS)
                    pressed = false
                }
            }
        }
    }
    return pressed
}
