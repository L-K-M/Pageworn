package ch.lkmc.pageworn.formats

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.Id3Frame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import ch.lkmc.pageworn.core.timeline.RawChapter
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EmbeddedChaptersTest {

    @Test
    fun id3ChapterFramesKeepTheirTimesAndTitles() {
        val metadata = Metadata(
            chap("ch0", 0, 30_000, "Start"),
            chap("ch1", 30_000, 60_000, "Middle"),
            TextInformationFrame("TIT2", null, listOf("The book")),
        )
        assertThat(EmbeddedChapters.from(metadata)).containsExactly(
            RawChapter(0, 30_000, "Start"),
            RawChapter(30_000, 60_000, "Middle"),
        ).inOrder()
    }

    @Test
    fun aChapterWithoutTitleOrEndKeepsWhatItHas() {
        val metadata = Metadata(ChapterFrame("ch0", 1_000, 1_000, -1, -1, emptyArray()))
        assertThat(EmbeddedChapters.from(metadata)).containsExactly(RawChapter(1_000, null, null))
    }

    @Test
    fun commentChaptersArePairedByNumberInNumberOrder() {
        val metadata = Metadata(
            VorbisComment("CHAPTER002NAME", "Middle"),
            VorbisComment("CHAPTER002", "00:00:30.000"),
            VorbisComment("chapter001", "0:00:00"),
            VorbisComment("CHAPTER001NAME", "Start"),
            VorbisComment("TITLE", "The book"),
        )
        assertThat(EmbeddedChapters.from(metadata)).containsExactly(
            RawChapter(0, null, "Start"),
            RawChapter(30_000, null, "Middle"),
        ).inOrder()
    }

    @Test
    fun anUnreadableCommentStartDropsItsChapter() {
        val chapters = EmbeddedChapters.fromComments(
            listOf("CHAPTER001" to "soon", "CHAPTER001NAME" to "Lost", "CHAPTER002" to "01:02:03.4", "CHAPTER003NAME" to "No start"),
        )
        assertThat(chapters).containsExactly(RawChapter(3_723_400, null, null))
    }

    @Test
    fun timesReadHoursMinutesSecondsAndFractions() {
        assertThat(EmbeddedChapters.parseTime("00:00:00.000")).isEqualTo(0)
        assertThat(EmbeddedChapters.parseTime("1:30:05.5")).isEqualTo(5_405_500)
        assertThat(EmbeddedChapters.parseTime("00:01:02,123456")).isEqualTo(62_123)
        assertThat(EmbeddedChapters.parseTime("00:61:00")).isNull()
        assertThat(EmbeddedChapters.parseTime("90")).isNull()
    }

    @Test
    fun chapterEntriesWinOverComments() {
        val metadata = Metadata(chap("ch0", 0, 10_000, "Marked"), VorbisComment("CHAPTER001", "00:00:05.000"))
        assertThat(EmbeddedChapters.from(metadata)).containsExactly(RawChapter(0, 10_000, "Marked"))
    }

    @Test
    fun noMetadataMeansNoChapters() {
        assertThat(EmbeddedChapters.from(null)).isEmpty()
        assertThat(EmbeddedChapters.from(Metadata(VorbisComment("ARTIST", "Someone")))).isEmpty()
    }

    private fun chap(id: String, start: Int, end: Int, title: String) =
        ChapterFrame(id, start, end, -1, -1, arrayOf<Id3Frame>(TextInformationFrame("TIT2", null, listOf(title))))
}
