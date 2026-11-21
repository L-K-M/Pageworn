package ch.lkmc.pageworn.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import ch.lkmc.pageworn.R
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.StartAction
import ch.lkmc.pageworn.ui.theme.LocalReducedMotion
import ch.lkmc.pageworn.ui.theme.PagewornTheme

@Composable
fun PencilIcon(resId: Int, contentDescription: String?, size: Dp = 26.dp) {
    Image(painterResource(resId), contentDescription, Modifier.size(size), colorFilter = ColorFilter.tint(PagewornTheme.colors.primaryInk))
}

@Composable
private fun Key(face: Int, inlay: Int?, title: String, faceWidth: Dp, faceHeight: Dp, enabled: Boolean, disabledReason: String?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier.size(width = maxOf(48.dp, faceWidth), height = maxOf(48.dp, faceHeight))
            .semantics { contentDescription = title; if (!enabled) { disabled(); disabledReason?.let { stateDescription = it } } }
            .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(faceWidth, faceHeight).graphicsLayer { translationY = if (pressed && !reduced) 2.dp.toPx() else 0f }.alpha(if (enabled) 1f else .45f), contentAlignment = Alignment.Center) {
            Image(painterResource(face), null, Modifier.size(faceWidth, faceHeight))
            if (inlay != null) Image(painterResource(inlay), null, Modifier.size(faceWidth * .43f, faceHeight * .43f))
        }
    }
}

@Composable
fun PlayPauseKey(isPlaying: Boolean, title: String, size: Dp, enabled: Boolean = true, disabledReason: String? = null, start: StartAction = StartAction.PLAY, onClick: () -> Unit) {
    val action = when {
        isPlaying -> "Pause"
        start == StartAction.REPLAY -> Copy.REPLAY
        start == StartAction.RESUME -> "Resume"
        else -> "Play"
    }
    Key(R.drawable.pw_key_oxblood, if (isPlaying) R.drawable.pw_inlay_pause else R.drawable.pw_inlay_play, "$action $title", size, size, enabled, disabledReason, onClick)
}

@Composable
fun SkipKey(back: Boolean, onClick: () -> Unit, enabled: Boolean = true, disabledReason: String? = null) {
    Key(R.drawable.pw_key_ivory_skip, if (back) R.drawable.pw_inlay_back_15 else R.drawable.pw_inlay_forward_30, if (back) "Back 15 seconds" else "Forward 30 seconds", 80.dp, 44.dp, enabled, disabledReason, onClick)
}

@Composable
fun TransferPauseKey(title: String, onClick: () -> Unit) {
    Key(R.drawable.pw_transfer_pause, null, "Pause download of $title", 36.dp, 36.dp, true, null, onClick)
}
