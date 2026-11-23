package ch.lkmc.pageworn.catalog

import ch.lkmc.pageworn.account.Routed
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.timeline.BookTimeline
import ch.lkmc.pageworn.core.timeline.ChapterMapBuilder
import ch.lkmc.pageworn.formats.FormatSupport
import ch.lkmc.pageworn.server.ItemManifest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the details of a book that is not on the phone show before a download: its description,
 * its chapters as the server lists them, and whether this phone can play its audio.
 *
 * [timeline] uses the server's track durations. The downloaded edition measures its own, so the
 * chapters shown here are for reading, not for seeking.
 */
data class BookPreview(
    val description: String?,
    val timeline: BookTimeline?,
    val unsupported: Boolean,
) {
    companion object {
        fun of(manifest: ItemManifest, formats: FormatSupport): BookPreview {
            val tracks = manifest.tracks
            return BookPreview(
                description = manifest.description?.trim()?.takeIf { it.isNotEmpty() },
                timeline = timelineOf(manifest),
                unsupported = tracks.isNotEmpty() && formats.bookUnsupported(tracks.map { FormatSupport.Track(it.extension, it.codec) }),
            )
        }

        /** The chapter map the edition would start from; null when the server's tracks give no usable timeline. */
        private fun timelineOf(manifest: ItemManifest): BookTimeline? {
            val tracks = manifest.tracks
            if (tracks.isEmpty() || tracks.any { it.durationMs <= 0 }) return null
            return runCatching {
                val spans = BookTimeline.contiguousTracks(tracks.map { it.durationMs })
                val chapters = ChapterMapBuilder.build(spans.last().endMs, manifest.chapters, trackStartsMs = spans.map { it.startMs })
                val titled = chapters.trackTitles?.let { titles -> spans.map { it.copy(title = titles.getOrNull(it.index)) } } ?: spans
                BookTimeline(titled, chapters.chapters)
            }.getOrNull()
        }
    }
}

/**
 * Reads previews through the profile's routes and keeps the latest few in memory, by book and
 * revision, so going back to a book does not ask the server again. A failed read is not kept:
 * the next visit tries again.
 */
class BookPreviews(
    private val manifest: suspend (itemId: String) -> Routed<ItemManifest>,
    private val formats: FormatSupport,
    private val capacity: Int = CAPACITY,
) {
    private data class Revision(val key: BookKey, val updatedAt: Long)

    private val lock = Mutex()
    private val kept = LinkedHashMap<Revision, BookPreview>(capacity, LOAD_FACTOR, true)

    /** The preview of [key] at catalog revision [updatedAt], or null when no address could serve it. */
    suspend fun preview(key: BookKey, updatedAt: Long): BookPreview? {
        val revision = Revision(key, updatedAt)
        lock.withLock { kept[revision] }?.let { return it }
        val read = manifest(key.itemId) as? Routed.Done ?: return null
        val preview = BookPreview.of(read.value, formats)
        lock.withLock {
            kept[revision] = preview
            while (kept.size > capacity) kept.remove(kept.keys.first())
        }
        return preview
    }

    private companion object {
        const val CAPACITY = 32
        const val LOAD_FACTOR = 0.75f
    }
}
