package ch.lkmc.pageworn.ui.state

import ch.lkmc.pageworn.data.db.CatalogBookEntity
import ch.lkmc.pageworn.data.db.EditionState
import ch.lkmc.pageworn.data.db.LocalEditionEntity
import ch.lkmc.pageworn.data.db.TransferEntity
import ch.lkmc.pageworn.data.db.TransferState
import ch.lkmc.pageworn.playback.LivePlayback
import ch.lkmc.pageworn.ui.model.CatalogAction
import ch.lkmc.pageworn.ui.model.CoverImage
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A transferring row keeps the cover that is already on the phone. */
class CatalogRowCoversTest {
    @get:Rule
    val files = TemporaryFolder()

    private val book = CatalogBookEntity(PROFILE, LIBRARY, ITEM, "The Night Orchard", "Elin Ward", null, 0, 1, 210_000, 1, "refresh")

    @Test
    fun anActiveTransferOnAPublishedEditionShowsItsLocalCover() {
        val dir = files.newFolder("edition")
        val cover = files.writeFile(dir, "cover.jpg")
        val edition = edition(dir, cover.name)

        val row = RowBuilders.catalogRow(book, edition, dir, activeTransfer(), null, LivePlayback(), canDownload = true, unsupported = false)

        assertThat(row.action).isInstanceOf(CatalogAction.Transferring::class.java)
        assertThat(row.face.cover).isEqualTo(CoverImage.Local(cover))
    }

    @Test
    fun aTransferWithoutAPublishedCoverStillUsesTheServerThumbnail() {
        val row = RowBuilders.catalogRow(book, null, null, activeTransfer(), null, LivePlayback(), canDownload = true, unsupported = false)

        assertThat(row.face.cover).isInstanceOf(CoverImage.Remote::class.java)
    }

    @Test
    fun aRepairNeededEditionKeepsItsLocalCoverToo() {
        val dir = files.newFolder("damaged")
        val cover = files.writeFile(dir, "cover.jpg")
        val edition = edition(dir, cover.name).copy(state = EditionState.REPAIR_NEEDED)

        val row = RowBuilders.catalogRow(book, edition, dir, null, null, LivePlayback(), canDownload = true, unsupported = false)

        assertThat(row.action).isEqualTo(CatalogAction.RepairNeeded)
        assertThat(row.face.cover).isEqualTo(CoverImage.Local(cover))
    }

    @Test
    fun anEditionWithoutACoverFileUsesTheServerThumbnail() {
        val dir = files.newFolder("plain")
        val edition = edition(dir, "cover.jpg").copy(coverFile = null)

        val row = RowBuilders.catalogRow(book, edition, dir, activeTransfer(), null, LivePlayback(), canDownload = true, unsupported = false)

        assertThat(row.face.cover).isInstanceOf(CoverImage.Remote::class.java)
    }

    @Test
    fun aMissingEditionDirectoryUsesTheServerThumbnail() {
        val edition = edition(files.root, "cover.jpg").copy(generationId = "gone", relativeDir = "gone")

        val row = RowBuilders.catalogRow(book, edition, null, activeTransfer(), null, LivePlayback(), canDownload = true, unsupported = false)

        assertThat(row.face.cover).isInstanceOf(CoverImage.Remote::class.java)
    }

    private fun activeTransfer() = TransferEntity(
        jobId = "job", profileId = PROFILE, itemId = ITEM, stagingGenerationId = "staged", manifestFingerprint = null,
        state = TransferState.TRANSFERRING, activeMarker = 1, createdAt = 1, updatedAt = 1, title = book.title, authors = book.authors,
    )

    private fun edition(dir: File, coverName: String) = LocalEditionEntity(
        profileId = PROFILE, itemId = ITEM, generationId = "generation", fingerprint = "print",
        state = EditionState.READY, durationMs = 210_000, relativeDir = "generation", coverFile = coverName,
        title = book.title, authors = book.authors, narrators = null, description = null, chapterSource = null,
        publishedAt = 1, sizeBytes = 1,
    )

    private fun TemporaryFolder.writeFile(dir: File, name: String): File =
        File(dir, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    private companion object {
        const val PROFILE = "profile"
        const val LIBRARY = "library"
        const val ITEM = "item"
    }
}
