package ch.lkmc.pageworn.core.timeline

/** A chapter marker as supplied, before validation. Times are milliseconds; [endMs] may be missing. */
data class RawChapter(val startMs: Long, val endMs: Long?, val title: String?)

/**
 * Builds the validated chapter map stored with an edition, following the chapter rules in
 * docs/05-playback-and-formats.md ("Chapter map and fallback"):
 *
 * 1. Book-level server chapters first, validated against the verified duration.
 * 2. Embedded markers only when the validated server list is empty.
 * 3. Missing ends use the next valid start or the book end; uncovered audio becomes a generated
 *    Opening or Interlude segment, never merged into a neighbour.
 * 4. No markers at all: an empty map, so the edition is divided by track or is one whole-book segment.
 */
object ChapterMapBuilder {
    /**
     * Gaps or overlaps up to this size are rounding noise from second-to-millisecond conversion and
     * are closed by moving the earlier chapter's end, never turned into an Interlude.
     */
    const val ROUNDING_TOLERANCE_MS = 5L

    data class Result(val chapters: List<ChapterSpan>, val source: ChapterOrigin?)

    fun build(
        durationMs: Long,
        serverChapters: List<RawChapter>,
        embeddedChaptersByTrack: List<List<RawChapter>> = emptyList(),
        trackStartsMs: List<Long> = emptyList(),
    ): Result {
        require(durationMs > 0)
        val server = validate(durationMs, serverChapters)
        if (server.isNotEmpty()) return Result(withGeneratedSegments(durationMs, server, ChapterOrigin.SERVER), ChapterOrigin.SERVER)

        val embeddedAbsolute = embeddedChaptersByTrack.flatMapIndexed { trackIndex, markers ->
            val offset = trackStartsMs.getOrElse(trackIndex) { 0L }
            markers.map { RawChapter(it.startMs + offset, it.endMs?.plus(offset), it.title) }
        }
        val embedded = validate(durationMs, embeddedAbsolute)
        if (embedded.isNotEmpty()) return Result(withGeneratedSegments(durationMs, embedded, ChapterOrigin.EMBEDDED), ChapterOrigin.EMBEDDED)

        return Result(emptyList(), null)
    }

    private data class Interval(val start: Long, var end: Long, val title: String?)

    /**
     * Drops intervals that start outside `[0, duration)`, deduplicates identical starts (keeping the
     * first supplied), fills missing ends, trims overlapping ends to the next start, clamps an
     * overflowing end to the book end and drops empty intervals.
     */
    internal fun validate(durationMs: Long, raw: List<RawChapter>): List<Pair<LongRange, String?>> {
        val seenStarts = HashSet<Long>()
        val candidates = raw
            .filter { it.startMs in 0 until durationMs }
            .filter { it.endMs == null || it.endMs > it.startMs }
            .filter { seenStarts.add(it.startMs) }
            .sortedBy { it.startMs }
            .map { Interval(it.startMs, it.endMs ?: Long.MAX_VALUE, it.title) }

        for (i in candidates.indices) {
            val nextStart = candidates.getOrNull(i + 1)?.start ?: durationMs
            val c = candidates[i]
            if (c.end == Long.MAX_VALUE || c.end > nextStart) c.end = nextStart
            if (c.end > durationMs) c.end = durationMs
        }
        // Close rounding noise between neighbours and at the book edges.
        for (i in candidates.indices) {
            val c = candidates[i]
            val nextStart = candidates.getOrNull(i + 1)?.start ?: durationMs
            if (nextStart - c.end in 1..ROUNDING_TOLERANCE_MS) c.end = nextStart
        }
        return candidates
            .filter { it.end > it.start }
            .map { (it.start until it.end) to it.title }
    }

    private fun withGeneratedSegments(
        durationMs: Long,
        intervals: List<Pair<LongRange, String?>>,
        origin: ChapterOrigin,
    ): List<ChapterSpan> {
        val out = ArrayList<ChapterSpan>()
        var cursor = 0L
        var number = 0
        for ((range, title) in intervals) {
            val start = if (out.isEmpty() && range.first in 1..ROUNDING_TOLERANCE_MS) 0L else range.first
            if (start > cursor) {
                val kind = if (out.isEmpty()) ChapterOrigin.GENERATED_OPENING else ChapterOrigin.GENERATED_INTERLUDE
                out += ChapterSpan(cursor, start, if (kind == ChapterOrigin.GENERATED_OPENING) OPENING else INTERLUDE, kind)
            }
            number++
            val end = range.last + 1
            out += ChapterSpan(start, end, title?.trim()?.takeIf { it.isNotEmpty() } ?: "Chapter $number", origin)
            cursor = end
        }
        if (cursor < durationMs) {
            out += ChapterSpan(cursor, durationMs, INTERLUDE, ChapterOrigin.GENERATED_INTERLUDE)
        }
        return out
    }

    const val OPENING = "Opening"
    const val INTERLUDE = "Interlude"
}
