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
 * A Library row's metadata line: a transfer always shows its state text, paired with any
 * failure message, so a paused or waiting transfer never reads as active progress
 * (docs/02-interface.md, catalog rows).
 */
class CatalogRowMessageTest {
    private val face = BookFace(BookKey("profile", "item"), "Title", null, CoverImage.Cloth)

    @Test
    fun activeOrQueuedTransferShowsItsStateText() {
        assertThat(RowBuilders.catalogRowMessage(row(TransferPhase.TRANSFERRING, "Downloading"))).isEqualTo("Downloading")
        assertThat(RowBuilders.catalogRowMessage(row(TransferPhase.QUEUED, "Waiting to download"))).isEqualTo("Waiting to download")
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
    fun identicalStateTextAndMessageRenderOnce() {
        assertThat(RowBuilders.catalogRowMessage(row(TransferPhase.PAUSED, "Paused", "Paused"))).isEqualTo("Paused")
    }

    @Test
    fun activeTransferPairsStateTextWithAMessage() {
        assertThat(RowBuilders.catalogRowMessage(row(TransferPhase.TRANSFERRING, "Downloading", "Retrying soon."))).isEqualTo("Downloading · Retrying soon.")
    }

    @Test
    fun transferMessageIsUsedWhenTheRowCarriesNone() {
        val row = CatalogRow(face, CatalogAction.Transferring(TransferUi("job", TransferPhase.FAILED, 1, 2, "Download stopped", "No route.", canPause = false, canResume = false, canRetry = true, canCancel = true)))
        assertThat(RowBuilders.catalogRowMessage(row)).isEqualTo("Download stopped · No route.")
    }

    @Test
    fun otherActionsKeepTheirMessages() {
        assertThat(RowBuilders.catalogRowMessage(CatalogRow(face, CatalogAction.DownloadDisabled))).isEqualTo(Copy.DOWNLOAD_DISABLED)
        assertThat(RowBuilders.catalogRowMessage(CatalogRow(face, CatalogAction.DownloadDisabled, "Offline"))).isEqualTo("Offline")
        assertThat(RowBuilders.catalogRowMessage(CatalogRow(face, CatalogAction.Unsupported))).isEqualTo(Copy.UNSUPPORTED_FORMAT)
        assertThat(RowBuilders.catalogRowMessage(CatalogRow(face, CatalogAction.RepairNeeded))).isEqualTo(Copy.REPAIR_NEEDED)
        assertThat(RowBuilders.catalogRowMessage(CatalogRow(face, CatalogAction.Download))).isNull()
    }

    @Test
    fun blankPartsNeverLeaveDanglingSeparators() {
        assertThat(RowBuilders.catalogRowMessage(row(TransferPhase.PAUSED, "", "Paused"))).isEqualTo("Paused")
        assertThat(RowBuilders.catalogRowMessage(row(TransferPhase.PAUSED, "Paused", ""))).isEqualTo("Paused")
    }

    @Test
    fun theRowMessageWinsOverTheTransferMessage() {
        assertThat(RowBuilders.catalogRowMessage(row(TransferPhase.FAILED, "Download stopped", "Transfer detail", rowMessage = "Row detail"))).isEqualTo("Download stopped · Row detail")
    }

    @Test
    fun aBlankRowMessageFallsBackToTheTransferMessage() {
        assertThat(RowBuilders.catalogRowMessage(row(TransferPhase.FAILED, "Download stopped", "Transfer detail", rowMessage = ""))).isEqualTo("Download stopped · Transfer detail")
    }

    private fun row(phase: TransferPhase, stateText: String, message: String? = null, rowMessage: String? = message) = CatalogRow(
        face,
        CatalogAction.Transferring(TransferUi("job", phase, 1, 2, stateText, message, canPause = true, canResume = false, canRetry = false, canCancel = true)),
        rowMessage,
    )
}
