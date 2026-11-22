package ch.lkmc.pageworn.ui.state

import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.data.db.EditionState
import ch.lkmc.pageworn.data.db.FailureCategory
import ch.lkmc.pageworn.data.db.LocalEditionEntity
import ch.lkmc.pageworn.data.db.TransferEntity
import ch.lkmc.pageworn.data.db.TransferState
import ch.lkmc.pageworn.playback.LivePlayback
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/** A27: transfers form their own group, each with the actions its state allows. */
class DownloadedGroupsTest {
    private val active = job("active", TransferState.TRANSFERRING)
    private val waiting = job("waiting", TransferState.WAITING_FOR_CONNECTION)
    private val failed = job("failed", TransferState.FAILED, FailureCategory.STORAGE_FULL)
    private val completed = job("ready", TransferState.COMPLETE)
    private val edition = LocalEditionEntity(
        PROFILE, "ready", "generation", "print", EditionState.READY, 210_000, "generation", null, "Ready", null,
        narrators = null, description = null, chapterSource = null, publishedAt = 1, sizeBytes = 1,
    )

    @Test
    fun a27TransfersAndReadyBooksFormSeparateGroups() {
        val (transfers, books) = rows(active, waiting, failed, completed)

        assertThat(transfers.map { it.transfer.jobId }).containsExactly("active", "waiting", "failed")
        assertThat(books.map { it.face.key }).containsExactly(BookKey(PROFILE, "ready"))

        val byJob = transfers.associate { it.transfer.jobId to it.transfer }
        assertThat(byJob.getValue("active").canPause).isTrue()
        assertThat(byJob.getValue("failed").canRetry || byJob.getValue("failed").canResume).isTrue()
        byJob.values.forEach {
            assertThat(it.canCancel).isTrue()
            assertThat(it.stateText).isNotEmpty()
        }
    }

    @Test
    fun a27PausingTheActiveTransferChangesOnlyItsActions() {
        val before = rows(active, waiting, failed).first
        val after = rows(active.copy(state = TransferState.PAUSED, userPaused = true), waiting, failed).first

        assertThat(after.first().transfer.canPause).isFalse()
        assertThat(after.first().transfer.canResume).isTrue()
        assertThat(after.drop(1)).isEqualTo(before.drop(1))
    }

    private fun rows(vararg jobs: TransferEntity) =
        RowBuilders.downloadedRows(listOf(edition), { File("editions") }, emptyMap(), emptyMap(), jobs.toList(), LivePlayback())

    private fun job(id: String, state: TransferState, failure: FailureCategory? = null) = TransferEntity(
        jobId = id, profileId = PROFILE, itemId = id, stagingGenerationId = "staged-$id", manifestFingerprint = null,
        state = state, activeMarker = 1, failureCategory = failure, createdAt = 1, updatedAt = 1, title = id, authors = null,
    )

    private companion object {
        const val PROFILE = "profile"
    }
}
