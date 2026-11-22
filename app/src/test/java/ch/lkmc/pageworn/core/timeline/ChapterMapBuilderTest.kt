package ch.lkmc.pageworn.core.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChapterMapBuilderTest {
    private fun s(start: Int, end: Int?, title: String? = null) =
        RawChapter(start * 1000L, end?.let { it * 1000L }, title)

    private fun List<ChapterSpan>.ranges() = map { it.startMs / 1000 to it.endMs / 1000 }

    @Test
    fun a28MalformedServerListIsValidatedAgainstTheBook() {
        val result = ChapterMapBuilder.build(
            210_000,
            listOf(s(0, 75), s(75, 150), s(140, 300), s(140, 300), s(250, 300)),
        )
        assertThat(result.source).isEqualTo(ChapterOrigin.SERVER)
        assertThat(result.chapters.ranges()).containsExactly(0L to 75L, 75L to 140L, 140L to 210L).inOrder()
        assertThat(result.chapters.none { it.origin.generated }).isTrue()
        // End-of-chapter sleep in the last chapter stops at the book end.
        assertThat(BookTimeline(BookTimeline.contiguousTracks(listOf(90_000L, 120_000L)), result.chapters).sleepBoundaryFor(150_000))
            .isEqualTo(210_000)
    }

    @Test
    fun a28bUncoveredAudioBecomesOpeningAndInterludes() {
        val result = ChapterMapBuilder.build(210_000, listOf(s(30, 75, "Alpha"), s(100, 150, "Beta")))
        val c = result.chapters
        assertThat(c.ranges()).containsExactly(0L to 30L, 30L to 75L, 75L to 100L, 100L to 150L, 150L to 210L).inOrder()
        assertThat(c.map { it.title }).containsExactly("Opening", "Alpha", "Interlude", "Beta", "Interlude").inOrder()
        assertThat(c.map { it.origin }).containsExactly(
            ChapterOrigin.GENERATED_OPENING,
            ChapterOrigin.SERVER,
            ChapterOrigin.GENERATED_INTERLUDE,
            ChapterOrigin.SERVER,
            ChapterOrigin.GENERATED_INTERLUDE,
        ).inOrder()
        val timeline = BookTimeline(BookTimeline.contiguousTracks(listOf(90_000L, 120_000L)), c)
        // Armed at 80 s (inside the generated Interlude), sleep stops at its end: 100 s, 10 s into track B.
        assertThat(timeline.sleepBoundaryFor(80_000)).isEqualTo(100_000)
        assertThat(timeline.trackAt(100_000)).isEqualTo(TrackPosition(1, 10_000))
    }

    @Test
    fun editedServerListWinsAndEmbeddedMarkersAreNeverMixedIn() {
        val embedded = listOf(listOf(s(0, 100, "The Orchard Gate"), s(100, 200, "Under the Pear Tree"), s(200, 300, "Moonrise")))
        val edited = ChapterMapBuilder.build(300_000, listOf(s(0, 120, "Part One"), s(120, 300, "Part Two")), embedded, listOf(0L))
        assertThat(edited.source).isEqualTo(ChapterOrigin.SERVER)
        assertThat(edited.chapters.ranges()).containsExactly(0L to 120L, 120L to 300L).inOrder()
        assertThat(edited.chapters.map { it.title }).containsExactly("Part One", "Part Two").inOrder()
    }

    @Test
    fun embeddedMarkersAreImportedWhenTheServerListIsUnusable() {
        val embedded = listOf(listOf(s(0, 100, "The Orchard Gate"), s(100, 200, "Under the Pear Tree"), s(200, 300, "Moonrise")))
        val removed = ChapterMapBuilder.build(300_000, emptyList(), embedded, listOf(0L))
        assertThat(removed.source).isEqualTo(ChapterOrigin.EMBEDDED)
        assertThat(removed.chapters.ranges()).containsExactly(0L to 100L, 100L to 200L, 200L to 300L).inOrder()
        assertThat(removed.chapters.map { it.title }).containsExactly("The Orchard Gate", "Under the Pear Tree", "Moonrise").inOrder()

        // A server list whose every entry lies outside the book is unusable too.
        val stale = ChapterMapBuilder.build(300_000, listOf(s(400, 500)), embedded, listOf(0L))
        assertThat(stale.source).isEqualTo(ChapterOrigin.EMBEDDED)
    }

    @Test
    fun embeddedPerFileTimesBecomeAbsoluteAndMissingTitlesAreNumbered() {
        val embedded = listOf(listOf(s(0, null, null), s(45, null, null)), listOf(s(0, null, "Second file")))
        val result = ChapterMapBuilder.build(210_000, emptyList(), embedded, listOf(0L, 90_000L))
        assertThat(result.chapters.ranges()).containsExactly(0L to 45L, 45L to 90L, 90L to 210L).inOrder()
        assertThat(result.chapters.map { it.title }).containsExactly("Chapter 1", "Chapter 2", "Second file").inOrder()
    }

    @Test
    fun missingEndsUseNextStartOrBookEnd() {
        val result = ChapterMapBuilder.build(210_000, listOf(s(0, null, "A"), s(90, null, "B")))
        assertThat(result.chapters.ranges()).containsExactly(0L to 90L, 90L to 210L).inOrder()
    }

    @Test
    fun emptyAndReversedIntervalsAreDropped() {
        val result = ChapterMapBuilder.build(210_000, listOf(s(0, 0), s(10, 5), s(20, 100, "Real")))
        assertThat(result.chapters.map { it.title }).containsExactly("Opening", "Real", "Interlude").inOrder()
    }

    @Test
    fun roundingNoiseIsClosedNotTurnedIntoInterludes() {
        val raw = listOf(
            RawChapter(2, 74_998, "One"),
            RawChapter(75_001, 139_999, "Two"),
            RawChapter(140_000, 209_997, "Three"),
        )
        val result = ChapterMapBuilder.build(210_000, raw)
        assertThat(result.chapters.map { it.startMs to it.endMs })
            .containsExactly(0L to 75_001L, 75_001L to 140_000L, 140_000L to 210_000L).inOrder()
        assertThat(result.chapters.none { it.origin.generated }).isTrue()
    }

    @Test
    fun encoderPaddingAtTheEndOfEachFileIsNotAChapter() {
        // Media3 reports each MP3 file 29 ms longer than its ID3 chapter frames reach (the encoder
        // padding the decoder counts), so every file ends with audio no marker covers.
        val perFile = listOf(RawChapter(0, 30_000, "Start"), RawChapter(30_000, 60_000, "Middle"))
        val result = ChapterMapBuilder.build(120_058, emptyList(), listOf(perFile, perFile), trackStartsMs = listOf(0L, 60_029L))
        assertThat(result.chapters.map { it.startMs to it.endMs })
            .containsExactly(0L to 30_000L, 30_000L to 60_029L, 60_029L to 90_029L, 90_029L to 120_058L).inOrder()
        assertThat(result.chapters.none { it.origin.generated }).isTrue()
    }

    @Test
    fun aGapOfASecondIsStillANavigableSegment() {
        val result = ChapterMapBuilder.build(210_000, listOf(RawChapter(0, 74_000, "One"), RawChapter(75_000, 209_001, "Two")))
        assertThat(result.chapters.map { it.title }).containsExactly("One", "Interlude", "Two").inOrder()
        assertThat(result.chapters.last().endMs).isEqualTo(210_000L)
    }

    @Test
    fun aFileListOfMp3FilesIsRecognisedDespiteTheirPadding() {
        // Audiobookshelf builds the list from ffprobe's durations (60 000 ms per file); the player's
        // track starts include each file's 29 ms of padding, so the absolute starts drift apart.
        val serverList = listOf(RawChapter(0, 60_000, "01"), RawChapter(60_000, 120_000, "02"), RawChapter(120_000, 180_000, "03"))
        val result = ChapterMapBuilder.build(180_087, serverList, trackStartsMs = listOf(0L, 60_029L, 120_058L))
        assertThat(result.chapters).isEmpty()
        assertThat(result.trackTitles).containsExactly("01", "02", "03").inOrder()
    }

    @Test
    fun noMarkersGivesAnEmptyMap() {
        val result = ChapterMapBuilder.build(210_000, emptyList(), listOf(emptyList(), emptyList()), listOf(0L, 90_000L))
        assertThat(result.chapters).isEmpty()
        assertThat(result.source).isNull()
    }

    @Test
    fun duplicateStartsKeepTheFirstEntry() {
        val result = ChapterMapBuilder.build(100_000, listOf(s(0, 50, "First"), s(0, 80, "Second"), s(50, 100, "Third")))
        assertThat(result.chapters.map { it.title }).containsExactly("First", "Third").inOrder()
    }

    @Test
    fun serverListThatRestatesTheFilesListsTracksWithItsTitles() {
        // Audiobookshelf 2.38 synthesizes one chapter per file for no-chapters-multi (captured item-field-notes-on-rain).
        val result = ChapterMapBuilder.build(210_000, listOf(s(0, 90, "01-part"), s(90, 210, "02-part")), trackStartsMs = listOf(0L, 90_000L))
        assertThat(result.chapters).isEmpty()
        assertThat(result.trackTitles).containsExactly("01-part", "02-part").inOrder()
        val timeline = BookTimeline(BookTimeline.contiguousTracks(listOf(90_000L, 120_000L), result.trackTitles!!), result.chapters)
        assertThat(timeline.division).isEqualTo(Division.TRACKS)
        assertThat(timeline.sleepBoundaryFor(89_000)).isEqualTo(90_000)
    }

    @Test
    fun serverListWithItsOwnBoundsStaysAChapterList() {
        val result = ChapterMapBuilder.build(210_000, listOf(s(0, 75, "One"), s(75, 210, "Two")), trackStartsMs = listOf(0L, 90_000L))
        assertThat(result.trackTitles).isNull()
        assertThat(result.chapters.map { it.title }).containsExactly("One", "Two").inOrder()
    }

    @Test
    fun singleFileBookStoresItsOneServerChapter() {
        // The timeline decides that one chapter around the whole book divides nothing.
        val result = ChapterMapBuilder.build(150_000, listOf(s(0, 150, "Whole")), trackStartsMs = listOf(0L))
        assertThat(result.trackTitles).isNull()
        assertThat(result.chapters.map { it.title }).containsExactly("Whole")
    }
}
