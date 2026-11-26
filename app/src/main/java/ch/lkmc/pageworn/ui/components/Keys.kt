package ch.lkmc.pageworn.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.R
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.KeyAction
import ch.lkmc.pageworn.ui.model.StartAction
import ch.lkmc.pageworn.ui.theme.LocalReducedMotion
import ch.lkmc.pageworn.ui.theme.PagewornTheme
import kotlinx.coroutines.delay

@Composable
fun PencilIcon(resId: Int, contentDescription: String?, size: Dp = 26.dp, modifier: Modifier = Modifier) {
    Image(painterResource(resId), contentDescription, modifier.size(size), colorFilter = ColorFilter.tint(PagewornTheme.colors.primaryInk))
}

/**
 * A drawn key. A press sinks the face and its inlay, shrinks them a little and shades them, and
 * the shadow they cast on the paper tightens, so the press shows around a finger that covers the
 * face. With reduced motion the key shades and its shadow tightens without moving.
 */
@Composable
private fun Key(face: Int, inlay: Int?, title: String, faceWidth: Dp, faceHeight: Dp, shape: Shape, enabled: Boolean, disabledReason: String?, starting: Boolean, collapseDoubleTaps: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed = rememberVisiblePress(interaction)
    val focused by interaction.collectIsFocusedAsState()
    val reduced = LocalReducedMotion.current
    // Down at once, up a little slower, as a key springs back.
    val depth by animateFloatAsState(
        if (pressed) 1f else 0f,
        if (reduced) snap() else tween(if (pressed) KEY_DOWN_MS else KEY_UP_MS),
        label = "key depth",
    )
    val shadowInk = PagewornTheme.colors.primaryInk
    // The cast shadow grows with the key, so a small row key does not stand as tall as the player's.
    val restLift = minOf(faceWidth, faceHeight) * KEY_LIFT
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
            .feedbackClickable(
                role = Role.Button,
                feel = TapFeel.KEY,
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
                collapseDoubleTaps = collapseDoubleTaps,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(faceWidth, faceHeight).graphicsLayer {
                val travel = if (reduced) 0f else depth
                translationY = KEY_SINK.toPx() * travel
                scaleX = 1f - KEY_SHRINK * travel
                scaleY = 1f - KEY_SHRINK * travel
                // A disabled key is a subdued skin without a shadow, as the leather button is.
                shadowElevation = if (enabled) (restLift * (1f - KEY_PRESSED_LIFT_CUT * depth)).toPx() else 0f
                this.shape = shape
                ambientShadowColor = shadowInk
                spotShadowColor = shadowInk
                alpha = if (enabled) 1f else DISABLED_KEY_ALPHA
            },
            contentAlignment = Alignment.Center,
        ) {
            // Face and inlay shade together, as one piece going down, in the warm ink of the page
            // rather than grey.
            val shade = if (depth > 0f) ColorFilter.tint(shadowInk.copy(alpha = KEY_PRESSED_SHADE * depth), BlendMode.SrcAtop) else null
            Image(painterResource(face), null, Modifier.size(faceWidth, faceHeight), colorFilter = shade)
            // The inlays are square: sized by the face's height, they keep their shape on the wide skips.
            if (inlay != null) Image(painterResource(inlay), null, Modifier.size(if (faceWidth > faceHeight) faceHeight * WIDE_INLAY else faceHeight * ROUND_INLAY), colorFilter = shade)
            if (starting) StartingArc(minOf(faceWidth, faceHeight))
        }
        if (focused) {
            // The ring surrounds the face rather than covering its rim or inlay.
            // The target box above has a fixed size, so drawing a larger ring here cannot move
            // the key or its row when focus arrives.
            Box(
                Modifier.size(faceWidth + KEY_FOCUS_OUTSET * 2, faceHeight + KEY_FOCUS_OUTSET * 2)
                    .border(2.dp, PagewornTheme.colors.primaryInk, PILL),
            )
        }
    }
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
private val PILL = RoundedCornerShape(percent = 50)
/** Space between a key face and its keyboard focus ring. */
private val KEY_FOCUS_OUTSET = 4.dp

/** The Play key of a book row and of the mini-player. */
val ROW_KEY = 40.dp
private const val ROUND_INLAY = 0.43f
private const val WIDE_INLAY = 0.74f
private const val SKIP_ASPECT = 0.55f
private const val DISABLED_KEY_ALPHA = .45f

/** How far a pressed face sinks, within the 1–2 dp the specification asks for. */
private val KEY_SINK = 1.5.dp
/** How much smaller a pressed face is drawn, so its rim moves where a finger does not cover it. */
private const val KEY_SHRINK = 0.04f
/** How much a pressed face darkens. */
private const val KEY_PRESSED_SHADE = 0.16f
/** The resting key's lift over the paper, as a share of its shorter side: about 3 dp under the player's Play. */
private const val KEY_LIFT = 0.04f
/** How much of that lift a press takes away, tightening the shadow. */
private const val KEY_PRESSED_LIFT_CUT = 0.7f
private const val KEY_DOWN_MS = 40
private const val KEY_UP_MS = 140
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
    Key(
        R.drawable.pw_key_oxblood,
        if (showsPause) R.drawable.pw_inlay_pause else R.drawable.pw_inlay_play,
        "$action $title", size, size, CircleShape, enabled, disabledReason, starting,
        // The second tap of a double tap would undo the first.
        collapseDoubleTaps = true,
    ) {
        onClick(if (showsPause) KeyAction.PAUSE else KeyAction.PLAY)
    }
}

@Composable
fun SkipKey(back: Boolean, width: Dp, onClick: () -> Unit, enabled: Boolean = true, disabledReason: String? = null) {
    Key(R.drawable.pw_key_ivory_skip, if (back) R.drawable.pw_inlay_back_15 else R.drawable.pw_inlay_forward_30, if (back) Copy.BACK_15 else Copy.FORWARD_30, width, width * SKIP_ASPECT, PILL, enabled, disabledReason, starting = false, onClick = onClick)
}

@Composable
fun TransferPauseKey(title: String, onClick: () -> Unit) {
    Key(R.drawable.pw_transfer_pause, null, "Pause download of $title", 32.dp, 32.dp, CircleShape, true, null, starting = false, onClick = onClick)
}
