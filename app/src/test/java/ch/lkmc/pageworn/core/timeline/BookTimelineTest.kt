package ch.lkmc.pageworn.core.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The boundary-book fixture timeline from docs/05-playback-and-formats.md and A09. */
class BookTimelineTest {
    private val tracks = BookTimeline.contiguousTracks(listOf(90_000L, 120_000L), listOf("Track A", "Track B"))
    private val chapters = ChapterMapBuilder.build(
        210_000L,
        listOf(RawChapter(0, 75_000, "One"), RawChapter(75_000, 140_000, "Two"), RawChapter(140_000, 210_000, "Three")),
    ).chapters
    private val boundary = BookTimeline(tracks, chapters)

    @Test
    fun chapterTwoPlusFortySecondsIsTrackBAtTwentyFive() {
        val position = boundary.positionInSegment(1, 40_000)
        assertThat(position).isEqualTo(115_000)
        assertThat(boundary.trackAt(position)).isEqualTo(TrackPosition(1, 25_000))
    }

    @Test
    fun internalTrackEdgeSelectsFollowingTrackAtZero() {
        assertThat(boundary.trackAt(90_000)).isEqualTo(TrackPosition(1, 0))
        assertThat(boundary.trackAt(89_999)).isEqualTo(TrackPosition(0, 89_999))
    }

    @Test
    fun a09SeekTargetsSelectTheRightIntervals() {
        assertThat(boundary.segmentIndexAt(75_000)).isEqualTo(1)
        assertThat(boundary.trackAt(75_000)).isEqualTo(TrackPosition(0, 75_000))
        assertThat(boundary.segmentIndexAt(90_000)).isEqualTo(1)
        assertThat(boundary.trackAt(90_000)).isEqualTo(TrackPosition(1, 0))
        assertThat(boundary.segmentIndexAt(140_000)).isEqualTo(2)
        assertThat(boundary.trackAt(140_000)).isEqualTo(TrackPosition(1, 50_000))
        assertThat(boundary.isAtEnd(210_000)).isTrue()
        assertThat(boundary.isAtEnd(209_999)).isFalse()
        assertThat(boundary.segmentIndexAt(210_000)).isEqualTo(2)
    }

    @Test
    fun externalSeeksAreClampedToTheEdition() {
        assertThat(boundary.clamp(-5)).isEqualTo(0)
        assertThat(boundary.clamp(400_000)).isEqualTo(210_000)
        assertThat(boundary.trackAt(400_000)).isEqualTo(TrackPosition(1, 120_000))
    }

    @Test
    fun trackLocalTimestampBecomesBookPosition() {
        assertThat(boundary.bookPosition(1, 25_000)).isEqualTo(115_000)
        assertThat(boundary.bookPosition(0, 0)).isEqualTo(0)
    }

    @Test
    fun endOfChapterSleepArmedAt115StopsAt140() {
        assertThat(boundary.sleepBoundaryFor(115_000)).isEqualTo(140_000)
        // Reopening at the boundary shows chapter 3 at its start.
        assertThat(boundary.segmentIndexAt(140_000)).isEqualTo(2)
        // Armed in the last chapter, it stops at the book end.
        assertThat(boundary.sleepBoundaryFor(200_000)).isEqualTo(210_000)
    }

    @Test
    fun chapterCommandsFollowTheCarRules() {
        assertThat(boundary.nextSegmentStart(115_000)).isEqualTo(140_000)
        assertThat(boundary.nextSegmentStart(150_000)).isNull()
        // More than three seconds into chapter 2: back to its start.
        assertThat(boundary.previousSegmentTarget(115_000)).isEqualTo(75_000)
        // Less than three seconds into chapter 2: to chapter 1.
        assertThat(boundary.previousSegmentTarget(77_000)).isEqualTo(0)
        // In the first chapter there is nothing before it.
        assertThat(boundary.previousSegmentTarget(1_000)).isEqualTo(0)
    }

    @Test
    fun divisionFallsBackToTracksThenTenMinuteParts() {
        val multi = BookTimeline(tracks, emptyList())
        assertThat(multi.division).isEqualTo(Division.TRACKS)
        assertThat(multi.segments.map { it.startMs to it.endMs }).containsExactly(0L to 90_000L, 90_000L to 210_000L).inOrder()
        assertThat(multi.sleepBoundaryFor(89_000)).isEqualTo(90_000)
        assertThat(multi.nextSegmentStart(10_000)).isEqualTo(90_000)

        val short = singleFile(150_000)
        assertThat(short.division).isEqualTo(Division.PARTS)
        assertThat(short.segments.map { it.startMs to it.endMs }).containsExactly(0L to 150_000L)
        assertThat(short.sleepBoundaryFor(10_000)).isEqualTo(150_000)
        assertThat(short.nextSegmentStart(10_000)).isNull()
    }

    @Test
    fun a24ASingleFileWithoutChaptersIsPartsOfTenMinutes() {
        val book = singleFile(25 * MINUTE)
        assertThat(book.segments.map { it.startMs to it.endMs })
            .containsExactly(0L to 10 * MINUTE, 10 * MINUTE to 20 * MINUTE, 20 * MINUTE to 25 * MINUTE).inOrder()
        assertThat(book.segments.all { it.generated && it.title == null }).isTrue()
        // The boundary belongs to the part after it, as with chapters.
        assertThat(book.segmentIndexAt(10 * MINUTE - 1)).isEqualTo(0)
        assertThat(book.segmentIndexAt(10 * MINUTE)).isEqualTo(1)
        assertThat(book.segmentIndexAt(25 * MINUTE)).isEqualTo(2)
        assertThat(book.sleepBoundaryFor(12 * MINUTE)).isEqualTo(20 * MINUTE)
        assertThat(book.sleepBoundaryFor(22 * MINUTE)).isEqualTo(25 * MINUTE)
        assertThat(book.nextSegmentStart(12 * MINUTE)).isEqualTo(20 * MINUTE)
        assertThat(book.previousSegmentTarget(10 * MINUTE + 2_000)).isEqualTo(0)
    }

    @Test
    fun a24TheLastPartIsTheRemainderUnlessThatIsNoise() {
        assertThat(singleFile(20 * MINUTE).segments.map { it.endMs }).containsExactly(10 * MINUTE, 20 * MINUTE).inOrder()
        assertThat(singleFile(20 * MINUTE + 999).segments.map { it.endMs }).containsExactly(10 * MINUTE, 20 * MINUTE + 999).inOrder()
        assertThat(singleFile(20 * MINUTE + 1_000).segments.map { it.endMs }).containsExactly(10 * MINUTE, 20 * MINUTE, 20 * MINUTE + 1_000).inOrder()
        assertThat(singleFile(500).segments.map { it.endMs }).containsExactly(500L)
        assertThat(singleFile(260 * MINUTE).segments).hasSize(26)
    }

    @Test
    fun a24OneChapterAroundTheWholeBookIsNoChapters() {
        val whole = { durationMs: Long -> listOf(ChapterSpan(0, durationMs, "The whole book", ChapterOrigin.SERVER)) }
        val file = BookTimeline(BookTimeline.contiguousTracks(listOf(25 * MINUTE)), whole(25 * MINUTE))
        assertThat(file.segments).hasSize(3)
        val files = BookTimeline(tracks, whole(210_000))
        assertThat(files.division).isEqualTo(Division.TRACKS)
        assertThat(files.segments).hasSize(2)
    }

    private fun singleFile(durationMs: Long) = BookTimeline(BookTimeline.contiguousTracks(listOf(durationMs)), emptyList())

    @Test
    fun secondsConvertOnceRoundingToNearestMillisecond() {
        assertThat(secondsToMillis(75.0004)).isEqualTo(75_000)
        assertThat(secondsToMillis(75.0005)).isEqualTo(75_001)
        assertThat(secondsToMillis(0.0)).isEqualTo(0)
        assertThat(secondsToMillis(36_000.123)).isEqualTo(36_000_123)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonContiguousTracksAreRejected() {
        BookTimeline(listOf(TrackSpan(0, 0, 90_000), TrackSpan(1, 91_000, 10_000)), emptyList())
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
