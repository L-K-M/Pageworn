package ch.lkmc.pageworn.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
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
import ch.lkmc.pageworn.ui.model.KeyAction
import ch.lkmc.pageworn.ui.model.StartAction
import ch.lkmc.pageworn.ui.theme.LocalReducedMotion
import ch.lkmc.pageworn.ui.theme.PagewornTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

@Composable
fun PencilIcon(resId: Int, contentDescription: String?, size: Dp = 26.dp, modifier: Modifier = Modifier) {
    Image(painterResource(resId), contentDescription, modifier.size(size), colorFilter = ColorFilter.tint(PagewornTheme.colors.primaryInk))
}

@Composable
private fun Key(face: Int, inlay: Int?, title: String, faceWidth: Dp, faceHeight: Dp, enabled: Boolean, disabledReason: String?, starting: Boolean, gestures: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = rememberVisiblePress(interaction)
    val focused by interaction.collectIsFocusedAsState()
    val reduced = LocalReducedMotion.current
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier.size(width = maxOf(MIN_KEY_TARGET, faceWidth), height = maxOf(MIN_KEY_TARGET, faceHeight))
            .semantics {
                contentDescription = title
                if (!enabled) {
                    disabled()
                    disabledReason?.let { stateDescription = it }
                } else if (starting) {
                    stateDescription = Copy.STARTING
                }
            }
            .then(gestures)
            .clickable(enabled = enabled, role = Role.Button, interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(faceWidth, faceHeight).graphicsLayer { translationY = if (pressed && !reduced) 2.dp.toPx() else 0f }.alpha(if (enabled) 1f else .45f), contentAlignment = Alignment.Center) {
            Image(painterResource(face), null, Modifier.size(faceWidth, faceHeight))
            // The inlays are square: sized by the face's height, they keep their shape on the wide skips.
            if (inlay != null) Image(painterResource(inlay), null, Modifier.size(if (faceWidth > faceHeight) faceHeight * WIDE_INLAY else faceHeight * ROUND_INLAY))
            if (starting) StartingArc(minOf(faceWidth, faceHeight))
        }
        if (focused) {
            // The ring surrounds the face rather than covering its rim or inlay.
            // The target box above has a fixed size, so drawing a larger ring here cannot move
            // the key or its row when focus arrives.
            Box(
                Modifier.size(faceWidth + KEY_FOCUS_OUTSET * 2, faceHeight + KEY_FOCUS_OUTSET * 2)
                    .border(2.dp, PagewornTheme.colors.primaryInk, RoundedCornerShape(percent = 50)),
            )
        }
    }
}

/**
 * Whether the key is drawn depressed. A quick tap in a scrolling list presses and releases within
 * one frame, so a press stays visible for a moment after the finger leaves.
 */
@Composable
private fun rememberVisiblePress(interaction: MutableInteractionSource): Boolean {
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

/** A turning arc on the rim while a book that was started is not heard yet. */
@Composable
private fun StartingArc(size: Dp) {
    var due by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(STARTING_ARC_AFTER_MS)
        due = true
    }
    if (!due) return
    val color = PagewornTheme.colors.inkOnKey
    val angle = if (LocalReducedMotion.current) 0f else {
        val turn = rememberInfiniteTransition(label = "starting")
        turn.animateFloat(0f, FULL_TURN, infiniteRepeatable(tween(STARTING_TURN_MS, easing = LinearEasing)), label = "arc").value
    }
    Canvas(Modifier.size(size)) {
        val inset = this.size.minDimension * ARC_INSET
        drawArc(
            color,
            startAngle = angle - 90f,
            sweepAngle = ARC_SWEEP,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(this.size.width - inset * 2, this.size.height - inset * 2),
            style = Stroke(this.size.minDimension * ARC_WIDTH, cap = StrokeCap.Round),
        )
    }
}

private val MIN_KEY_TARGET = 48.dp
/** Space between a key face and its keyboard focus ring. */
private val KEY_FOCUS_OUTSET = 4.dp

/** The Play key of a book row and of the mini-player. */
val ROW_KEY = 40.dp
private const val ROUND_INLAY = 0.43f
private const val WIDE_INLAY = 0.74f
private const val SKIP_ASPECT = 0.55f
private const val MIN_PRESS_VISIBLE_MS = 100L
private const val STARTING_ARC_AFTER_MS = 350L
private const val STARTING_TURN_MS = 900
private const val FULL_TURN = 360f
private const val ARC_SWEEP = 80f
private const val ARC_INSET = 0.15f
private const val ARC_WIDTH = 0.05f

@Composable
fun PlayPauseKey(showsPause: Boolean, title: String, size: Dp, starting: Boolean = false, enabled: Boolean = true, disabledReason: String? = null, start: StartAction = StartAction.PLAY, onClick: (KeyAction) -> Unit) {
    val action = when {
        showsPause -> "Pause"
        start == StartAction.REPLAY -> Copy.REPLAY
        start == StartAction.RESUME -> "Resume"
        else -> "Play"
    }
    val taps = remember { TapTimes() }
    Key(
        R.drawable.pw_key_oxblood,
        if (showsPause) R.drawable.pw_inlay_pause else R.drawable.pw_inlay_play,
        "$action $title", size, size, enabled, disabledReason, starting,
        Modifier.pointerInput(taps) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Release) {
                        event.changes.firstOrNull()?.let { taps.lifted(it.uptimeMillis, viewConfiguration.doubleTapTimeoutMillis) }
                    }
                }
            }
        },
    ) {
        // The second tap of a double tap is the same tap. Otherwise it would undo the first.
        if (taps.takeRepeat()) return@Key
        onClick(if (showsPause) KeyAction.PAUSE else KeyAction.PLAY)
    }
}

/**
 * When the finger left the key, by the touch events' own times: while a book starts the main thread
 * can handle the second of two quick taps long after it was made.
 */
private class TapTimes {
    private var liftMs: Long? = null
    private var timeoutMs = 0L
    private var lastTapMs: Long? = null

    fun lifted(atMs: Long, timeoutMs: Long) {
        liftMs = atMs
        this.timeoutMs = timeoutMs
    }

    /**
     * Whether the click being handled repeats the tap before it. A click without a finger, as from
     * TalkBack or a keyboard, never does, and neither does a touch that slid off before it lifted.
     */
    fun takeRepeat(): Boolean {
        val lifted = liftMs ?: return false
        liftMs = null
        val repeats = lastTapMs?.let { lifted - it < timeoutMs } == true
        lastTapMs = lifted
        return repeats
    }
}

@Composable
fun SkipKey(back: Boolean, width: Dp, onClick: () -> Unit, enabled: Boolean = true, disabledReason: String? = null) {
    Key(R.drawable.pw_key_ivory_skip, if (back) R.drawable.pw_inlay_back_15 else R.drawable.pw_inlay_forward_30, if (back) Copy.BACK_15 else Copy.FORWARD_30, width, width * SKIP_ASPECT, enabled, disabledReason, starting = false, onClick = onClick)
}

@Composable
fun TransferPauseKey(title: String, onClick: () -> Unit) {
    Key(R.drawable.pw_transfer_pause, null, "Pause download of $title", 32.dp, 32.dp, true, null, starting = false, onClick = onClick)
}
