package ch.lkmc.pageworn.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.ui.detail.BookDetailScreen
import ch.lkmc.pageworn.ui.library.LibraryScreen
import ch.lkmc.pageworn.ui.model.BookDetailUiState
import ch.lkmc.pageworn.ui.model.BookFace
import ch.lkmc.pageworn.ui.model.CatalogAction
import ch.lkmc.pageworn.ui.model.CatalogRow
import ch.lkmc.pageworn.ui.model.CoverImage
import ch.lkmc.pageworn.ui.model.DetailAction
import ch.lkmc.pageworn.ui.model.LibraryUiState
import ch.lkmc.pageworn.data.db.TransferEntity
import ch.lkmc.pageworn.data.db.TransferState
import ch.lkmc.pageworn.ui.state.RowBuilders
import ch.lkmc.pageworn.ui.theme.PagewornTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Retry and its neighbours on a Library row and on details. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetryTransferActionsTest {
    @get:Rule
    val compose = createComposeRule()

    private val face = BookFace(BookKey("profile", "item"), "The Harbour", "A. Author", CoverImage.Cloth)

    @Test
    fun libraryOffersRetryOnAFailedTransfer() {
        var resumed: String? = null
        val job = mutableStateOf(TransferState.VERIFYING)
        compose.setContent {
            val state = LibraryUiState(rows = listOf(CatalogRow(face, CatalogAction.Transferring(transfer(job.value)))))
            PagewornTheme {
                LibraryScreen(state, rememberLazyListState(), {}, {}, {}, {}, {}, { _, _ -> }, {}, { resumed = it }, {}, {}, {}, PaddingValues())
            }
        }

        val retry = compose.onNodeWithContentDescription("Retry download of The Harbour")
        retry.assertDoesNotExist()
        compose.runOnIdle { job.value = TransferState.FAILED }
        retry.performScrollTo().performClick()

        assertThat(resumed).isEqualTo(JOB)
    }

    @Test
    fun detailOffersRetryOnAFailedTransfer() {
        var resumed: String? = null
        val job = mutableStateOf(TransferState.VERIFYING)
        compose.setContent {
            val state = detail(DetailAction.Transferring(transfer(job.value)))
            PagewornTheme {
                BookDetailScreen(state, {}, {}, {}, {}, {}, {}, { resumed = it }, {}, {}, {})
            }
        }

        val retry = compose.onNodeWithContentDescription("Retry download of The Harbour")
        retry.assertDoesNotExist()
        compose.runOnIdle { job.value = TransferState.FAILED }
        retry.performScrollTo().performClick()

        assertThat(resumed).isEqualTo(JOB)
    }

    @Test
    fun libraryWaitingKeepsPauseAndCancelBesideRetry() {
        var paused: String? = null
        var cancelled: String? = null
        val state = LibraryUiState(rows = listOf(CatalogRow(face, CatalogAction.Transferring(transfer(TransferState.WAITING_FOR_CONNECTION)))))
        compose.setContent {
            PagewornTheme {
                LibraryScreen(state, rememberLazyListState(), {}, {}, {}, {}, {}, { _, _ -> }, { paused = it }, {}, { cancelled = it }, {}, {}, PaddingValues())
            }
        }

        listOf("Pause download of The Harbour", "Retry download of The Harbour", "Cancel download of The Harbour").forEach { label ->
            compose.onNodeWithContentDescription(label).assertExists()
        }
        compose.onAllNodesWithContentDescription("Retry download of The Harbour").assertCountEquals(1)
        compose.onNodeWithContentDescription("Pause download of The Harbour").performClick()
        compose.onNodeWithContentDescription("Cancel download of The Harbour").performClick()

        assertThat(paused).isEqualTo(JOB)
        assertThat(cancelled).isEqualTo(JOB)
    }

    @Test
    fun detailWaitingKeepsPauseAndCancelBesideRetry() {
        var paused: String? = null
        var cancelled: String? = null
        val state = detail(DetailAction.Transferring(transfer(TransferState.WAITING_FOR_CONNECTION)))
        compose.setContent {
            PagewornTheme {
                BookDetailScreen(state, {}, {}, {}, {}, {}, { paused = it }, {}, { cancelled = it }, {}, {})
            }
        }

        listOf("Pause download of The Harbour", "Retry download of The Harbour", "Cancel download of The Harbour").forEach { label ->
            compose.onNodeWithContentDescription(label).assertExists()
        }
        compose.onAllNodesWithContentDescription("Retry download of The Harbour").assertCountEquals(1)
        compose.onNodeWithContentDescription("Pause download of The Harbour").performClick()
        compose.onNodeWithContentDescription("Cancel download of The Harbour").performScrollTo().performClick()

        assertThat(paused).isEqualTo(JOB)
        assertThat(cancelled).isEqualTo(JOB)
    }

    private fun detail(action: DetailAction) = BookDetailUiState(
        face = face,
        narrator = null,
        durationText = null,
        sizeText = null,
        action = action,
        message = null,
        description = null,
        segmentsTitle = null,
        segments = emptyList(),
        segmentsPlayable = false,
        canRemove = false,
    )

    private fun transfer(state: TransferState) = RowBuilders.transferUi(
        TransferEntity(
            jobId = JOB, profileId = face.key.profileId, itemId = face.key.itemId,
            stagingGenerationId = "generation", manifestFingerprint = null, state = state,
            activeMarker = 1, createdAt = 0, updatedAt = 0, bytesDone = 10, bytesTotal = 100,
            title = face.title, authors = face.author,
        ),
    )

    private companion object {
        const val JOB = "job-1"
    }
}
