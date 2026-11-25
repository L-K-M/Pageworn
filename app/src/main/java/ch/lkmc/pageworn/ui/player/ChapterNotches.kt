package ch.lkmc.pageworn.ui.player

import ch.lkmc.pageworn.core.timeline.Division
import ch.lkmc.pageworn.core.timeline.Segment

/**
 * Where the book bar is notched: the start of every chapter or track after the first. Parts get
 * none, being ten-minute cuts of a file without chapters rather than divisions of the book.
 */
internal fun chapterBoundaries(division: Division, segments: List<Segment>): List<Long> {
    if (division == Division.PARTS) return emptyList()
    return segments.drop(1).map { it.startMs }
}

/**
 * The boundaries a bar [widthPx] wide over [scale] can show as notches, in order. None sits
 * inside a rounded end ([endInsetPx] from either end), and none closer than [minSpacingPx] to the
 * one before it, so a book of many short chapters keeps a readable bar instead of a dashed one.
 * Each notch kept stands for the boundaries just after it that it hides.
 */
internal fun visibleNotches(boundariesMs: List<Long>, scale: LongRange, widthPx: Float, endInsetPx: Float, minSpacingPx: Float): List<Long> {
    if (scale.last <= scale.first || widthPx <= 0f) return emptyList()
    val kept = mutableListOf<Long>()
    var lastX = Float.NEGATIVE_INFINITY
    for (boundary in boundariesMs) {
        val x = notchX(boundary, scale, widthPx)
        if (x < endInsetPx || x > widthPx - endInsetPx) continue
        if (x - lastX < minSpacingPx) continue
        kept += boundary
        lastX = x
    }
    return kept
}

/** Where [boundaryMs] falls along a bar [widthPx] wide that spans [scale]. */
internal fun notchX(boundaryMs: Long, scale: LongRange, widthPx: Float): Float =
    ((boundaryMs - scale.first).toDouble() / (scale.last - scale.first) * widthPx).toFloat()

/**
 * Whether a drag from [fromMs] to [toMs] passed a notch: one landing on a notch counts, one
 * leaving it does not, so resting on a notch and moving off it gives no second tick.
 */
internal fun crossesNotch(notchesMs: List<Long>, fromMs: Long, toMs: Long): Boolean = when {
    toMs > fromMs -> notchesMs.any { it in (fromMs + 1)..toMs }
    toMs < fromMs -> notchesMs.any { it in toMs until fromMs }
    else -> false
}

/**
 * Spaces the ticks of a fast drag: one swept across a book of many short chapters reaches a notch
 * every frame, which would buzz rather than tick.
 */
internal class NotchTicks {
    private var lastMs: Long? = null

    /** Whether a notch reached at [nowMs] ticks. */
    fun take(nowMs: Long): Boolean {
        val last = lastMs
        if (last != null && nowMs - last < MIN_TICK_GAP_MS) return false
        lastMs = nowMs
        return true
    }

    companion object {
        const val MIN_TICK_GAP_MS = 60L
    }
}
