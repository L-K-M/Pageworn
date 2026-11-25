package ch.lkmc.pageworn.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The paper's pressed state, which replaces the Material ripple app-wide: a wash of [ink] over a
 * control, which clips itself to its own shape. A press shows at once and stays for the same
 * moment as a key's after a quick tap, then fades, rather than spreading a grey circle from the
 * finger. Keyboard focus and a hovering pointer get a lighter wash, as the ripple gave them.
 * The drawn keys and the leather button draw their own press instead.
 */
@Immutable
data class InkWash(private val ink: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = InkWashNode(interactionSource, ink)
}

private class InkWashNode(private val interactionSource: InteractionSource, private val ink: Color) : Modifier.Node(), DrawModifierNode {
    private val press = Animatable(0f)
    private var focused by mutableStateOf(false)
    private var hovered by mutableStateOf(false)

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collectLatest {
                when (it) {
                    is PressInteraction.Press -> press.snapTo(1f)
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        delay(MIN_PRESS_VISIBLE_MS)
                        press.animateTo(0f, tween(WASH_FADE_MS))
                    }
                    is FocusInteraction.Focus -> focused = true
                    is FocusInteraction.Unfocus -> focused = false
                    is HoverInteraction.Enter -> hovered = true
                    is HoverInteraction.Exit -> hovered = false
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val resting = when {
            focused -> FOCUS_WASH_ALPHA
            hovered -> HOVER_WASH_ALPHA
            else -> 0f
        }
        val alpha = maxOf(resting, press.value * PRESS_WASH_ALPHA)
        if (alpha > 0f) drawRect(ink, alpha = alpha)
    }
}

/** The shortest a press is drawn, however quick the tap (docs/02-interface.md, Playback controls). */
const val MIN_PRESS_VISIBLE_MS = 100L
private const val WASH_FADE_MS = 150
private const val PRESS_WASH_ALPHA = 0.12f
private const val FOCUS_WASH_ALPHA = 0.10f
private const val HOVER_WASH_ALPHA = 0.06f
