package ch.lkmc.pageworn.catalog

import ch.lkmc.pageworn.account.Routed
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.timeline.Division
import ch.lkmc.pageworn.core.timeline.RawChapter
import ch.lkmc.pageworn.formats.FormatSupport
import ch.lkmc.pageworn.server.ItemManifest
import ch.lkmc.pageworn.server.ManifestSourceTrack
import ch.lkmc.pageworn.server.NoRoute
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BookPreviewsTest {
    private val everythingDecodes = FormatSupport { true }
    private val key = BookKey("profile", "item")

    private fun track(index: Int, durationMs: Long, extension: String = "mp3", codec: String? = "mp3") =
        ManifestSourceTrack(index, "file-$index", 0, durationMs, 1_000, 1, extension, codec, null)

    private fun manifest(
        tracks: List<ManifestSourceTrack> = listOf(track(0, 210_000)),
        chapters: List<RawChapter> = emptyList(),
        description: String? = null,
    ) = ItemManifest("item", 1, "Title", "Author", null, description, "book", tracks, chapters, coverPresent = false)

    @Test
    fun descriptionIsTrimmedAndABlankOneIsNone() {
        assertThat(BookPreview.of(manifest(description = "  A quiet book.\n"), everythingDecodes).description).isEqualTo("A quiet book.")
        assertThat(BookPreview.of(manifest(description = " \n "), everythingDecodes).description).isNull()
        assertThat(BookPreview.of(manifest(description = null), everythingDecodes).description).isNull()
    }

    @Test
    fun serverChaptersAcrossFilesBecomeTheChapterList() {
        // The fixture timeline of docs/05: tracks 0-90 s and 90-210 s, chapters at 0, 75 and 140 s.
        val preview = BookPreview.of(
            manifest(
                tracks = listOf(track(0, 90_000), track(1, 120_000)),
                chapters = listOf(RawChapter(0, 75_000, "One"), RawChapter(75_000, 140_000, "Two"), RawChapter(140_000, 210_000, "Three")),
            ),
            everythingDecodes,
        )

        val timeline = checkNotNull(preview.timeline)
        assertThat(timeline.division).isEqualTo(Division.CHAPTERS)
        assertThat(timeline.segments.map { it.title }).containsExactly("One", "Two", "Three").inOrder()
        assertThat(timeline.segments.map { it.durationMs }).containsExactly(75_000L, 65_000L, 70_000L).inOrder()
    }

    @Test
    fun chaptersThatOnlyRestateTheFilesListTracksWithTheirTitles() {
        val preview = BookPreview.of(
            manifest(
                tracks = listOf(track(0, 90_000), track(1, 120_000)),
                chapters = listOf(RawChapter(0, 90_000, "Side A"), RawChapter(90_000, 210_000, "Side B")),
            ),
            everythingDecodes,
        )

        val timeline = checkNotNull(preview.timeline)
        assertThat(timeline.division).isEqualTo(Division.TRACKS)
        assertThat(timeline.segments.map { it.title }).containsExactly("Side A", "Side B").inOrder()
    }

    @Test
    fun aBookWithoutUsableTracksHasNoTimeline() {
        assertThat(BookPreview.of(manifest(tracks = emptyList()), everythingDecodes).timeline).isNull()
        assertThat(BookPreview.of(manifest(tracks = listOf(track(0, 0))), everythingDecodes).timeline).isNull()
        assertThat(BookPreview.of(manifest(tracks = emptyList()), everythingDecodes).unsupported).isFalse()
    }

    @Test
    fun anyTrackThePhoneCannotPlayMakesTheBookUnsupported() {
        val wma = manifest(tracks = listOf(track(0, 60_000), track(1, 60_000, extension = "wma", codec = "wmav2")))
        assertThat(BookPreview.of(wma, everythingDecodes).unsupported).isTrue()
        assertThat(BookPreview.of(manifest(), everythingDecodes).unsupported).isFalse()

        val noDecoder = FormatSupport { codec -> codec != "mp3" }
        assertThat(BookPreview.of(manifest(), noDecoder).unsupported).isTrue()
    }

    @Test
    fun aRevisionIsReadOnceAndANewRevisionIsReadAgain() = runTest {
        var reads = 0
        val previews = BookPreviews({ reads++; Routed.Done(manifest(description = "Read $reads"), "home") }, everythingDecodes)

        assertThat(previews.preview(key, updatedAt = 1)?.description).isEqualTo("Read 1")
        assertThat(previews.preview(key, updatedAt = 1)?.description).isEqualTo("Read 1")
        assertThat(reads).isEqualTo(1)

        assertThat(previews.preview(key, updatedAt = 2)?.description).isEqualTo("Read 2")
        assertThat(reads).isEqualTo(2)
    }

    @Test
    fun aFailedReadIsNotKeptSoTheNextVisitTriesAgain() = runTest {
        var reachable = false
        var reads = 0
        val previews = BookPreviews({
            reads++
            if (reachable) Routed.Done(manifest(description = "Back"), "home") else Routed.Unavailable(NoRoute.Unreachable(null))
        }, everythingDecodes)

        assertThat(previews.preview(key, updatedAt = 1)).isNull()
        reachable = true
        assertThat(previews.preview(key, updatedAt = 1)?.description).isEqualTo("Back")
        assertThat(reads).isEqualTo(2)
    }

    @Test
    fun onlyTheLatestBooksAreKept() = runTest {
        var reads = 0
        val previews = BookPreviews({ reads++; Routed.Done(manifest(), "home") }, everythingDecodes, capacity = 2)
        val a = BookKey("profile", "a")
        val b = BookKey("profile", "b")
        val c = BookKey("profile", "c")

        previews.preview(a, 1)
        previews.preview(b, 1)
        previews.preview(a, 1) // a is now the most recent
        previews.preview(c, 1) // evicts b
        assertThat(reads).isEqualTo(3)

        previews.preview(a, 1)
        assertThat(reads).isEqualTo(3)
        previews.preview(b, 1)
        assertThat(reads).isEqualTo(4)
    }
}
