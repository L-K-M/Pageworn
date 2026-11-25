package ch.lkmc.pageworn.ui.player

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.Format
import ch.lkmc.pageworn.ui.model.PlayerUiState
import ch.lkmc.pageworn.ui.theme.LocalHighContrast
import ch.lkmc.pageworn.ui.theme.PagewornTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * The two scales of the player: the chapter, track or part that holds the position, and below it
 * the whole book. Both move the same position, and each reads out where a drag would land.
 */
@Composable
internal fun PlayerScrubbers(state: PlayerUiState, onSeekTo: (Long) -> Unit, modifier: Modifier = Modifier) {
    val segment = remember(state.face.key) { ScrubState() }
    val book = remember(state.face.key) { ScrubState() }
    segment.ReleaseWhenMoved(state.positionMs)
    book.ReleaseWhenMoved(state.positionMs)

    val segmentScale = segment.target?.scale ?: (state.segmentStartMs..state.segmentEndMs)
    val segmentPosition = segment.target?.positionMs ?: state.positionMs
    val bookScale = 0L..state.bookDurationMs
    val bookPosition = book.target?.positionMs ?: state.positionMs
    val elapsed = Format.clock(segmentPosition - segmentScale.first)
    val bookLine = book.target?.let { Format.bookLine(it.positionMs, state.bookDurationMs) } ?: state.bookLine
    // The segment list is the timeline's own and outlives the live ticks, so this runs once a book.
    val boundaries = remember(state.division, state.segments) { chapterBoundaries(state.division, state.segments) }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Scrubber(
            ScrubberLook.SEGMENT, segment, segmentPosition, segmentScale, state,
            name = "${state.segmentName} position",
            value = "$elapsed of ${Format.clock(segmentScale.last - segmentScale.first)}",
            onSeekTo = onSeekTo,
        )
        // The bar's round ends sit half a thumb in, and the times under them.
        Row(Modifier.fillMaxWidth().padding(horizontal = ScrubberLook.SEGMENT.thumbSize / 2), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(elapsed, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.time)
            Text(
                segment.target?.let { Format.remaining(segmentScale.last - it.positionMs) } ?: state.remainingText,
                color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.time,
            )
        }
        Scrubber(ScrubberLook.BOOK, book, bookPosition, bookScale, state, name = "Book position", value = bookLine, onSeekTo = onSeekTo, boundariesMs = boundaries)
        Text(bookLine, color = PagewornTheme.colors.secondaryInk, style = PagewornTheme.type.time, textAlign = TextAlign.Center)
    }
}

/** The whole-book scale is slimmer and drawn in ink, so the two never read as one another. */
private enum class ScrubberLook(val trackHeight: Dp, val thumbSize: Dp) {
    SEGMENT(8.dp, 16.dp),
    BOOK(5.dp, 12.dp),
}

/** Where a finger holds a scrubber, on the scale it had when it was touched. */
private data class ScrubTarget(val scale: LongRange, val positionMs: Long)

private class ScrubState {
    var target by mutableStateOf<ScrubTarget?>(null)
        private set
    private var dragging by mutableStateOf(false)

    /** Holds [fraction] of the scale; returns where the same drag held the thumb before, or null when this starts one. */
    fun move(scale: LongRange, fraction: Float): Long? {
        // Playback can enter the next segment under the finger; the drag keeps the scale it began on.
        val previous = target?.takeIf { dragging }
        val held = previous?.scale ?: scale
        dragging = true
        target = ScrubTarget(held, held.first + ((held.last - held.first).toDouble() * fraction).toLong())
        return previous?.positionMs
    }

    fun drop(): Long? {
        dragging = false
        return target?.positionMs
    }

    /** Holds a released thumb until the player has moved, so it does not jump back first. */
    @Composable
    fun ReleaseWhenMoved(positionMs: Long) {
        LaunchedEffect(positionMs) {
            if (!dragging) target = null
        }
        LaunchedEffect(target, dragging) {
            if (dragging || target == null) return@LaunchedEffect
            delay(RELEASE_HOLD_MS)
            target = null
        }
    }
}

/**
 * One scale. [boundariesMs] are notched into its bar: a cut through the filled part, a pencilled
 * mark on the rest. A drag ticks under the finger as it reaches one of them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Scrubber(look: ScrubberLook, scrub: ScrubState, positionMs: Long, scale: LongRange, player: PlayerUiState, name: String, value: String, onSeekTo: (Long) -> Unit, boundariesMs: List<Long> = emptyList()) {
    val length = scale.last - scale.first
    val fraction = if (length > 0) ((positionMs - scale.first).toDouble() / length).toFloat().coerceIn(0f, 1f) else 0f
    val enabled = player.transportEnabled && length > 0
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val ink = if (look == ScrubberLook.SEGMENT) PagewornTheme.colors.accent else PagewornTheme.colors.secondaryInk
    val filled = if (enabled) ink else PagewornTheme.colors.secondaryInk.copy(alpha = DISABLED_ALPHA)
    val colors = SliderDefaults.colors(
        activeTrackColor = filled,
        inactiveTrackColor = MaterialTheme.colorScheme.primaryContainer,
        disabledActiveTrackColor = filled,
        disabledInactiveTrackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = DISABLED_ALPHA),
    )
    val density = LocalDensity.current
    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    val notches = remember(boundariesMs, scale, trackWidthPx, density) {
        with(density) { visibleNotches(boundariesMs, scale, trackWidthPx, look.trackHeight.toPx(), NOTCH_SPACING.toPx()) }
    }
    val markAlpha = (if (LocalHighContrast.current) 1f else NOTCH_MARK_ALPHA) * (if (enabled) 1f else DISABLED_ALPHA)
    val mark = PagewornTheme.colors.secondaryInk.copy(alpha = markAlpha)
    val haptic = LocalHapticFeedback.current
    val ticks = remember { NotchTicks() }
    Slider(
        value = fraction,
        onValueChange = {
            val from = scrub.move(scale, it)
            val to = scrub.target?.positionMs
            if (from != null && to != null && crossesNotch(notches, from, to) && ticks.take(SystemClock.uptimeMillis())) {
                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
        },
        onValueChangeFinished = { scrub.drop()?.let(onSeekTo) },
        enabled = enabled,
        interactionSource = interaction,
        colors = colors,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = name
            stateDescription = if (enabled) value else player.transportDisabledReason ?: value
            customActions = if (!enabled) emptyList() else listOf(
                CustomAccessibilityAction(Copy.BACK_15) { onSeekTo((player.positionMs - BACK_MS).coerceIn(scale)); true },
                CustomAccessibilityAction(Copy.FORWARD_30) { onSeekTo((player.positionMs + FORWARD_MS).coerceIn(scale)); true },
            )
        },
        thumb = {
            // The box is the touch height; the disc is what shows.
            Box(Modifier.size(look.thumbSize, SCRUBBER_TARGET), contentAlignment = Alignment.Center) {
                if (focused) Box(Modifier.requiredSize(look.thumbSize + FOCUS_RING).border(2.dp, PagewornTheme.colors.primaryInk, CircleShape))
                Box(Modifier.size(look.thumbSize).background(filled, CircleShape))
            }
        },
        track = {
            // The slider lays the bar out between the thumb's two end positions, so a fraction of
            // the bar's own width is where the thumb's centre stands at that fraction of the scale.
            SliderDefaults.Track(
                it,
                Modifier.height(look.trackHeight)
                    .onSizeChanged { size -> trackWidthPx = size.width.toFloat() }
                    .then(if (notches.isEmpty()) Modifier else Modifier.notched(notches, scale, fraction, mark)),
                enabled = enabled, colors = colors,
                drawStopIndicator = null, thumbTrackGapSize = 0.dp, trackInsideCornerSize = 0.dp,
            )
        },
    )
}

/**
 * Cuts each notch through the filled bar, so the paper shows, and pencils it on the unfilled
 * rest in [mark]. Edges fall on whole pixels, so every notch is as crisp as the next.
 */
private fun Modifier.notched(notchesMs: List<Long>, scale: LongRange, filledFraction: Float, mark: Color): Modifier =
    graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        val width = NOTCH_WIDTH.toPx().roundToInt().coerceAtLeast(1).toFloat()
        val filledTo = size.width * filledFraction
        for (notch in notchesMs) {
            val x = notchX(notch, scale, size.width)
            val topLeft = Offset((x - width / 2).roundToInt().toFloat(), 0f)
            val notchSize = Size(width, size.height)
            if (x <= filledTo) drawRect(Color.Black, topLeft, notchSize, blendMode = BlendMode.Clear)
            else drawRect(mark, topLeft, notchSize)
        }
    }

private val SCRUBBER_TARGET = 48.dp
private val FOCUS_RING = 10.dp
private val NOTCH_WIDTH = 2.dp
/** The closest two notches may stand: closer boundaries share the first one's notch. */
private val NOTCH_SPACING = 6.dp
private const val NOTCH_MARK_ALPHA = 0.7f
private const val DISABLED_ALPHA = 0.45f
private const val RELEASE_HOLD_MS = 1_000L
private const val BACK_MS = 15_000L
private const val FORWARD_MS = 30_000L
