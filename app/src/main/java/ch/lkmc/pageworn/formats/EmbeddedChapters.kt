package ch.lkmc.pageworn.formats

import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.Chapter
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import ch.lkmc.pageworn.core.timeline.RawChapter

/**
 * The chapter markers embedded in one audio file, read from the metadata that the player's own
 * extractors attach to the file's audio format. MP4 `chpl` atoms, Matroska chapters and ID3v2 CHAP
 * frames arrive as [Chapter] entries. Ogg and FLAC files carry `CHAPTERnnn` and `CHAPTERnnnNAME`
 * comments instead, which Media3 passes through as [VorbisComment]s. One file uses one of the two;
 * when both are present the [Chapter] entries win.
 *
 * Times are relative to the file. Validation, conversion to book time and the fallback order
 * belong to [ch.lkmc.pageworn.core.timeline.ChapterMapBuilder].
 */
object EmbeddedChapters {

    fun from(metadata: Metadata?): List<RawChapter> {
        if (metadata == null) return emptyList()
        val entries = (0 until metadata.length()).map(metadata::get)
        val marked = entries.filterIsInstance<Chapter>()
            .filterNot { it.isHidden }
            .map { chapter ->
                val end = chapter.endTimeMs.takeIf { it != C.TIME_UNSET && it > chapter.startTimeMs }
                RawChapter(chapter.startTimeMs, end, chapter.title?.value?.trim()?.takeIf { it.isNotEmpty() })
            }
        if (marked.isNotEmpty()) return marked
        return fromComments(entries.filterIsInstance<VorbisComment>().map { it.key to it.value })
    }

    /**
     * `CHAPTER001=00:01:30.500` and `CHAPTER001NAME=Title`, the convention ffmpeg and most taggers
     * write. A start that cannot be read drops its chapter; a missing name leaves the title empty.
     */
    internal fun fromComments(comments: List<Pair<String, String>>): List<RawChapter> {
        val starts = sortedMapOf<Int, Long>()
        val names = HashMap<Int, String>()
        for ((key, value) in comments) {
            val match = COMMENT_KEY.matchEntire(key.trim().uppercase()) ?: continue
            val number = match.groupValues[1].toInt()
            if (match.groupValues[2].isEmpty()) {
                parseTime(value)?.let { starts.putIfAbsent(number, it) }
            } else {
                names.putIfAbsent(number, value.trim())
            }
        }
        return starts.map { (number, start) -> RawChapter(start, null, names[number]?.takeIf { it.isNotEmpty() }) }
    }

    /** `H:MM:SS` with an optional fraction of a second, to milliseconds. */
    internal fun parseTime(value: String): Long? {
        val match = TIME.matchEntire(value.trim()) ?: return null
        val (hours, minutes, seconds, fraction) = match.destructured
        val millis = fraction.padEnd(3, '0').take(3).toLong()
        return ((hours.toLong() * 60 + minutes.toLong()) * 60 + seconds.toLong()) * 1_000 + millis
    }

    private val COMMENT_KEY = Regex("^CHAPTER(\\d{1,3})(NAME)?$")
    private val TIME = Regex("^(\\d{1,3}):([0-5]\\d):([0-5]\\d)(?:[.,](\\d{1,9}))?$")
}
