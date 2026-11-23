package ch.lkmc.pageworn.ui.state

import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.ui.model.BookFace
import ch.lkmc.pageworn.ui.model.CatalogAction
import ch.lkmc.pageworn.ui.model.CatalogRow
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.CoverImage
import ch.lkmc.pageworn.ui.model.TransferPhase
import ch.lkmc.pageworn.ui.model.TransferUi
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A Library row's metadata line: an active or queued transfer is the ring alone, while a
 * resting one pairs its state text with any failure message, so it never reads as progress
 * (docs/02-interface.md, catalog rows).
 */
class CatalogRowMessageTest {
    private val face = BookFace(BookKey("profile", "item"), "Title", null, CoverImage.Cloth)

    @Test
    fun activeOrQueuedTransferShowsNoStateText() {
        assertThat(RowBuilders.catalogRowMessage(row(TransferPhase.TRANSFERRING, "Downloading"))).isNull()
        assertThat(RowBuilders.catalogRowMessage(row(TransferPhase.QUEUED, "Waiting to download"))).isNull()
    }

    @Test
    fun restingTransferShowsItsStateText() {
        assertThat(RowBuilders.catalogRowMessage(row(TransferPhase.PAUSED, "Paused"))).isEqualTo("Paused")
        assertThat(RowBuilders.catalogRowMessage(row(TransferPhase.WAITING, "Waiting for connection"))).isEqualTo("Waiting for connection")
        assertThat(RowBuilders.catalogRowMessage(row(TransferPhase.FAILED, "Download stopped"))).isEqualTo("Download stopped")
    }

    @Test
    fun restingTransferPairsStateTextWithFailureMessage() {
        val message = RowBuilders.catalogRowMessage(row(TransferPhase.FAILED, "Download stopped", "More space is needed."))
        assertThat(message).isEqualTo("Download stopped · More space is needed.")
    }

    @Test
    fun otherActionsKeepTheirMessages() {
        assertThat(RowBuilders.catalogRowMessage(CatalogRow(face, CatalogAction.DownloadDisabled))).isEqualTo(Copy.DOWNLOAD_DISABLED)
        assertThat(RowBuilders.catalogRowMessage(CatalogRow(face, CatalogAction.Unsupported))).isEqualTo(Copy.UNSUPPORTED_FORMAT)
        assertThat(RowBuilders.catalogRowMessage(CatalogRow(face, CatalogAction.Download))).isNull()
    }

    private fun row(phase: TransferPhase, stateText: String, message: String? = null) = CatalogRow(
        face,
        CatalogAction.Transferring(TransferUi("job", phase, 1, 2, stateText, message, canPause = true, canResume = false, canRetry = false, canCancel = true)),
        message,
    )
}
