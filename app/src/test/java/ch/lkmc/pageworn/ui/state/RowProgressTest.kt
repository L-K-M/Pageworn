package ch.lkmc.pageworn.ui.state

import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.data.db.BookProgressEntity
import ch.lkmc.pageworn.data.db.CatalogBookEntity
import ch.lkmc.pageworn.data.db.EditionState
import ch.lkmc.pageworn.data.db.LocalEditionEntity
import ch.lkmc.pageworn.playback.LivePlayback
import ch.lkmc.pageworn.ui.model.CatalogAction
import ch.lkmc.pageworn.ui.model.StartAction
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** The Library row's start action follows only the two projected progress facts. */
class RowProgressTest {
    private val book = CatalogBookEntity("p", "lib", "item", "Title", null, null, 0, 1, null, null, "r")
    private val edition = LocalEditionEntity(
        profileId = "p", itemId = "item", generationId = "g", fingerprint = "f",
        state = EditionState.READY, durationMs = 60_000, relativeDir = "g", coverFile = null,
        title = "Title", authors = null, narrators = null, description = null,
        chapterSource = null, publishedAt = 1, sizeBytes = 1,
    )

    @Test
    fun aPositionOnlyCheckpointDoesNotEmitAnotherRowProjection() = runBlocking {
        val first = BookProgressEntity(
            "p", "item", 1, 60_000, "f", false, 1, StopKind.ORDINARY, 1, 1, 1,
        )
        val later = first.copy(positionMs = 12_000, updatedAt = 2, writeSequence = 2)
        val finished = later.copy(finished = true)

        assertThat(flowOf(listOf(first), listOf(later), listOf(finished)).forLibraryRows().toList())
            .containsExactly(
                mapOf("item" to RowProgress(hasPlace = true, finished = false)),
                mapOf("item" to RowProgress(hasPlace = true, finished = true)),
            ).inOrder()
    }

    @Test
    fun readyRowStartFollowsOnlyPlaceAndFinished() {
        val live = LivePlayback()
        fun start(progress: RowProgress?) = (RowBuilders.catalogRow(book, edition, null, null, progress, live, canDownload = true, unsupported = false).action as CatalogAction.Ready).start

        assertThat(start(RowProgress(hasPlace = false, finished = false))).isEqualTo(StartAction.PLAY)
        assertThat(start(RowProgress(hasPlace = true, finished = false))).isEqualTo(StartAction.RESUME)
        assertThat(start(RowProgress(hasPlace = false, finished = true))).isEqualTo(StartAction.REPLAY)
        assertThat(start(RowProgress(hasPlace = true, finished = true))).isEqualTo(StartAction.REPLAY)
        assertThat(start(null)).isEqualTo(StartAction.PLAY)
    }
}
