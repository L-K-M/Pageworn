package ch.lkmc.pageworn.core.timeline

/** One playable file of an edition on the book timeline, `[startMs, endMs)`. */
data class TrackSpan(
    val index: Int,
    val startMs: Long,
    val durationMs: Long,
    val title: String? = null,
) {
    val endMs: Long get() = startMs + durationMs
}

enum class ChapterOrigin {
    /** Book-level chapter list from Audiobookshelf. */
    SERVER,

    /** Markers embedded in the audio files, converted to book time. */
    EMBEDDED,

    /** Uncovered audio before the first chapter. */
    GENERATED_OPENING,

    /** Uncovered audio between chapters or after the last one. */
    GENERATED_INTERLUDE;

    val generated: Boolean get() = this == GENERATED_OPENING || this == GENERATED_INTERLUDE
}

/** One chapter of the validated chapter map, `[startMs, endMs)` in book time. */
data class ChapterSpan(
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val origin: ChapterOrigin,
) {
    val durationMs: Long get() = endMs - startMs
}

/** How an edition is divided for navigation, the chapter scrubber and the end-of sleep modes. */
enum class Division {
    /** A chapter map exists (server, embedded or generated segments). */
    CHAPTERS,

    /** Several files and no chapter marks: navigate by track. */
    TRACKS,

    /** A single undivided file: the book is one segment. */
    WHOLE_BOOK,
}

/** One navigation segment: a chapter, a track or the whole book, depending on [BookTimeline.division]. */
data class Segment(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    /** Author-supplied or generated chapter title, a track title, or null for an untitled track or whole book. */
    val title: String?,
    val generated: Boolean,
) {
    val durationMs: Long get() = endMs - startMs
}

data class TrackPosition(val trackIndex: Int, val offsetMs: Long)

/**
 * The time model of one published edition. Every position is book time in milliseconds.
 * Intervals are half-open, so an internal boundary belongs to the following track or chapter,
 * and the book end is a distinct finished state.
 */
class BookTimeline(
    val tracks: List<TrackSpan>,
    val chapters: List<ChapterSpan>,
) {
    init {
        require(tracks.isNotEmpty()) { "An edition has at least one track" }
        var expected = 0L
        tracks.forEachIndexed { i, t ->
            require(t.index == i) { "Tracks must be indexed in play order" }
            require(t.startMs == expected) { "Tracks must be contiguous: track $i starts at ${t.startMs}, expected $expected" }
            require(t.durationMs > 0) { "Track $i has no duration" }
            expected = t.endMs
        }
        var previousEnd = 0L
        chapters.forEachIndexed { i, c ->
            require(c.startMs == previousEnd) { "Chapter map must cover the book contiguously (chapter $i)" }
            require(c.endMs > c.startMs) { "Chapter $i is empty" }
            previousEnd = c.endMs
        }
        require(chapters.isEmpty() || previousEnd == expected) { "Chapter map must end at the book end" }
    }

    val durationMs: Long = tracks.last().endMs

    val division: Division = when {
        chapters.isNotEmpty() -> Division.CHAPTERS
        tracks.size > 1 -> Division.TRACKS
        else -> Division.WHOLE_BOOK
    }

    val segments: List<Segment> = when (division) {
        Division.CHAPTERS -> chapters.mapIndexed { i, c -> Segment(i, c.startMs, c.endMs, c.title, c.origin.generated) }
        Division.TRACKS -> tracks.map { t -> Segment(t.index, t.startMs, t.endMs, t.title, generated = false) }
        Division.WHOLE_BOOK -> listOf(Segment(0, 0, durationMs, null, generated = false))
    }

    fun clamp(positionMs: Long): Long = positionMs.coerceIn(0, durationMs)

    fun isAtEnd(positionMs: Long): Boolean = positionMs >= durationMs

    /**
     * Maps a book position to a track and a track-local offset. A position on an internal track
     * edge selects the following track at offset zero. The book end maps to the end of the last track.
     */
    fun trackAt(positionMs: Long): TrackPosition {
        val p = clamp(positionMs)
        if (p >= durationMs) return TrackPosition(tracks.last().index, tracks.last().durationMs)
        val i = indexOfInterval(tracks.size, p) { tracks[it].startMs }
        return TrackPosition(i, p - tracks[i].startMs)
    }

    fun bookPosition(trackIndex: Int, offsetMs: Long): Long {
        val t = tracks[trackIndex]
        return clamp(t.startMs + offsetMs.coerceIn(0, t.durationMs))
    }

    /** Index of the segment containing [positionMs]. The book end belongs to the last segment. */
    fun segmentIndexAt(positionMs: Long): Int {
        val p = clamp(positionMs)
        if (p >= durationMs) return segments.lastIndex
        return indexOfInterval(segments.size, p) { segments[it].startMs }
    }

    fun segmentAt(positionMs: Long): Segment = segments[segmentIndexAt(positionMs)]

    /** Book position of a chapter-relative offset. */
    fun positionInSegment(segmentIndex: Int, offsetMs: Long): Long {
        val s = segments[segmentIndex]
        return clamp(s.startMs + offsetMs.coerceIn(0, s.durationMs))
    }

    /**
     * End boundary that end-of-chapter (or end-of-track, end-of-book) sleep stops at when armed at
     * [positionMs]: the end of the segment that contains it, clamped to the edition duration.
     */
    fun sleepBoundaryFor(positionMs: Long): Long = segmentAt(positionMs).endMs.coerceAtMost(durationMs)

    /** Start of the following segment, or null in the last one (Next chapter does nothing there). */
    fun nextSegmentStart(positionMs: Long): Long? {
        val i = segmentIndexAt(positionMs)
        return if (i >= segments.lastIndex) null else segments[i + 1].startMs
    }

    /**
     * Previous chapter: the start of the current segment, or of the one before it when less than
     * [thresholdMs] of the current segment have played.
     */
    fun previousSegmentTarget(positionMs: Long, thresholdMs: Long = PREVIOUS_CHAPTER_THRESHOLD_MS): Long {
        val p = clamp(positionMs)
        val i = segmentIndexAt(p)
        val current = segments[i]
        return if (p - current.startMs < thresholdMs && i > 0) segments[i - 1].startMs else current.startMs
    }

    private inline fun indexOfInterval(count: Int, position: Long, startOf: (Int) -> Long): Int {
        // Largest index whose start is <= position (binary search over sorted starts).
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (startOf(mid) <= position) lo = mid else hi = mid - 1
        }
        return lo
    }

    companion object {
        const val PREVIOUS_CHAPTER_THRESHOLD_MS = 3_000L

        /** Builds contiguous tracks from verified durations in play order. */
        fun contiguousTracks(durationsMs: List<Long>, titles: List<String?> = emptyList()): List<TrackSpan> {
            var start = 0L
            return durationsMs.mapIndexed { i, d ->
                TrackSpan(i, start, d, titles.getOrNull(i)).also { start += d }
            }
        }
    }
}

/** Converts Audiobookshelf seconds to milliseconds once, at import, rounding to the nearest millisecond. */
fun secondsToMillis(seconds: Double): Long = Math.round(seconds * 1000.0)
