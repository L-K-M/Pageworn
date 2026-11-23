package ch.lkmc.pageworn.ui.state

import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.timeline.BookTimeline
import ch.lkmc.pageworn.data.DownloadedOrder
import ch.lkmc.pageworn.data.db.BookProgressEntity
import ch.lkmc.pageworn.data.db.CatalogBookEntity
import ch.lkmc.pageworn.data.db.EditionState
import ch.lkmc.pageworn.data.db.FailureCategory
import ch.lkmc.pageworn.data.db.LocalEditionEntity
import ch.lkmc.pageworn.data.db.TransferEntity
import ch.lkmc.pageworn.data.db.TransferState
import ch.lkmc.pageworn.playback.LivePlayback
import ch.lkmc.pageworn.ui.model.BookFace
import ch.lkmc.pageworn.ui.model.CatalogAction
import ch.lkmc.pageworn.ui.model.CatalogRow
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.CoverImage
import ch.lkmc.pageworn.ui.model.EmptyState
import ch.lkmc.pageworn.ui.model.Format
import ch.lkmc.pageworn.ui.model.ReadyBadge
import ch.lkmc.pageworn.ui.model.ReadyBookRow
import ch.lkmc.pageworn.ui.model.StartAction
import ch.lkmc.pageworn.ui.model.TransferPhase
import ch.lkmc.pageworn.ui.model.TransferRow
import ch.lkmc.pageworn.ui.model.TransferUi
import ch.lkmc.pageworn.ui.model.UiAction
import java.io.File

/** Pure builders from durable state to rows, shared by the screens and the JVM tests. */
object RowBuilders {

    fun editionFace(edition: LocalEditionEntity, editionDir: File): BookFace =
        BookFace(
            edition.key,
            edition.title,
            edition.authors,
            edition.coverFile?.let { CoverImage.Local(File(editionDir, it)) } ?: CoverImage.Cloth,
        )

    fun transferFace(transfer: TransferEntity): BookFace =
        BookFace(
            transfer.key,
            transfer.title,
            transfer.authors,
            transfer.coverUpdatedAt?.let { CoverImage.Remote(transfer.profileId, transfer.itemId, it) } ?: CoverImage.Cloth,
        )

    fun transferUi(t: TransferEntity): TransferUi {
        val phase = when (t.state) {
            TransferState.QUEUED -> if (t.userPaused) TransferPhase.PAUSED else TransferPhase.QUEUED
            TransferState.TRANSFERRING -> TransferPhase.TRANSFERRING
            TransferState.WAITING_FOR_CONNECTION -> if (t.userPaused) TransferPhase.PAUSED else TransferPhase.WAITING
            TransferState.PAUSED -> TransferPhase.PAUSED
            TransferState.FAILED -> TransferPhase.FAILED
            TransferState.VERIFYING, TransferState.COMPLETE, TransferState.CANCELLED -> TransferPhase.VERIFYING
        }
        val stateText = when (phase) {
            TransferPhase.QUEUED -> "Waiting to download"
            TransferPhase.TRANSFERRING -> "Downloading"
            TransferPhase.WAITING -> "Waiting for connection"
            TransferPhase.PAUSED -> "Paused"
            TransferPhase.FAILED -> if (t.attempt > 0 && t.failureCategory in setOf(FailureCategory.NETWORK, FailureCategory.SERVER)) "Download stopped (attempt ${t.attempt})" else "Download stopped"
            TransferPhase.VERIFYING -> "Checking the download"
        }
        val message = when (t.failureCategory) {
            null -> null
            FailureCategory.STORAGE_FULL -> Copy.storageFull(t.shortfallBytes)
            FailureCategory.DOWNLOAD_DENIED -> Copy.DOWNLOAD_DISABLED
            FailureCategory.UNSUPPORTED_FORMAT -> Copy.UNSUPPORTED_FORMAT
            FailureCategory.NO_AUDIO -> Copy.NO_AUDIO
            FailureCategory.SESSION_EXPIRED -> Copy.SESSION_EXPIRED
            FailureCategory.NO_ROUTE, FailureCategory.NETWORK -> if (phase == TransferPhase.WAITING) null else Copy.UNREACHABLE
            FailureCategory.SOURCE_MISMATCH -> "The book changed on the server. Retry reads it again."
            FailureCategory.VERIFICATION -> "The downloaded audio didn’t match the server’s."
            FailureCategory.SERVER -> "The server didn’t answer as expected."
            FailureCategory.LOCAL_NETWORK_DENIED, FailureCategory.CERTIFICATE_CHANGED -> null
        }
        val stopped = phase == TransferPhase.PAUSED || phase == TransferPhase.FAILED
        return TransferUi(
            jobId = t.jobId,
            phase = phase,
            bytesDone = t.bytesDone,
            bytesTotal = t.bytesTotal,
            stateText = stateText,
            message = message,
            canPause = phase == TransferPhase.QUEUED || phase == TransferPhase.TRANSFERRING || phase == TransferPhase.WAITING,
            canResume = phase == TransferPhase.PAUSED,
            canRetry = phase == TransferPhase.FAILED || phase == TransferPhase.WAITING,
            canCancel = phase != TransferPhase.VERIFYING,
        ).let { if (stopped && phase == TransferPhase.FAILED) it.copy(canResume = false) else it }
    }

    /** "Chapter 4 · 2 h 12 min left", "Track 2 · 40 min left", "Part 13 · 2 h 12 min left". */
    fun progressText(timeline: BookTimeline?, positionMs: Long, durationMs: Long): String {
        val left = Format.hoursMinutes((durationMs - positionMs).coerceAtLeast(0)) + " left"
        if (timeline == null) return left
        return "${PlayerStates.segmentTitle(timeline.division, timeline.segmentAt(positionMs))} · $left"
    }

    fun readyRow(
        edition: LocalEditionEntity,
        editionDir: File,
        progress: BookProgressEntity?,
        timeline: BookTimeline?,
        live: LivePlayback,
    ): ReadyBookRow {
        val isLive = live.key == edition.key
        val position = if (isLive) live.positionMs else progress?.positionMs ?: 0L
        val finished = progress?.finished == true && !live.showsPause(edition.key)
        val neverPlayed = progress == null || (progress.lastListenedAt == null && progress.positionMs == 0L && !isLive)
        val badge = when {
            edition.state == EditionState.REPAIR_NEEDED -> ReadyBadge.RepairNeeded(Copy.DAMAGED_COPY)
            finished -> ReadyBadge.Finished
            neverPlayed -> ReadyBadge.New
            else -> ReadyBadge.InProgress(progressText(timeline, position, edition.durationMs))
        }
        return ReadyBookRow(
            face = editionFace(edition, editionDir),
            badge = badge,
            hasPlace = !finished && (position > 0 || (progress != null && !neverPlayed)),
            showsPause = live.showsPause(edition.key),
            starting = live.waitsForAudio(edition.key),
            playable = edition.state == EditionState.READY,
        )
    }

    fun downloadedRows(
        editions: List<LocalEditionEntity>,
        dirOf: (LocalEditionEntity) -> File,
        progress: Map<BookKey, BookProgressEntity>,
        timelines: Map<String, BookTimeline>,
        transfers: List<TransferEntity>,
        live: LivePlayback,
        heldOrder: List<BookKey> = emptyList(),
    ): Pair<List<TransferRow>, List<ReadyBookRow>> {
        val transferRows = transfers.filter { it.state.active }.map { TransferRow(transferFace(it), transferUi(it)) }
        val ordered = holdOrder(DownloadedOrder.sort(editions) { progress[it.key]?.lastListenedAt }, heldOrder)
        val bookRows = ordered.map { readyRow(it, dirOf(it), progress[it.key], timelines[it.generationId], live) }
        return transferRows to bookRows
    }

    /**
     * Keeps rows where they were while the list is on screen, so the book that just started does
     * not move from under the finger that started it. A changed set of books is sorted afresh.
     */
    private fun holdOrder(sorted: List<LocalEditionEntity>, held: List<BookKey>): List<LocalEditionEntity> {
        val byKey = sorted.associateBy { it.key }
        if (held.size != sorted.size || !held.all { it in byKey }) return sorted
        return held.map { byKey.getValue(it) }
    }

    fun noDownloads() = EmptyState(Copy.NO_DOWNLOADS, UiAction.BROWSE_LIBRARY, Copy.BROWSE_LIBRARY)

    /** A Library row for a listing row, which may come from staged pages before the first publish. */
    fun catalogRow(
        book: ch.lkmc.pageworn.catalog.CatalogRow, profileId: String, libraryId: String,
        edition: LocalEditionEntity?, editionDir: File?, transfer: TransferEntity?,
        progress: RowProgress?, live: LivePlayback, canDownload: Boolean, unsupported: Boolean,
    ): CatalogRow = catalogRow(
        CatalogBookEntity(profileId, libraryId, book.itemId, book.title, book.authors, book.narrators, 0, book.updatedAt, book.durationMs, book.sizeBytes, ""),
        edition, editionDir, transfer, progress, live, canDownload, unsupported,
    )

    /** One Library row: the local state of the book wins over its remote availability. */
    fun catalogRow(
        book: CatalogBookEntity,
        edition: LocalEditionEntity?,
        editionDir: File?,
        transfer: TransferEntity?,
        progress: RowProgress?,
        live: LivePlayback,
        canDownload: Boolean,
        unsupported: Boolean,
    ): CatalogRow {
        val key = BookKey(book.profileId, book.itemId)
        val face = BookFace(key, book.title, book.authors, CoverImage.Remote(book.profileId, book.itemId, book.updatedAt))
        return when {
            transfer != null && transfer.state.active -> CatalogRow(face, CatalogAction.Transferring(transferUi(transfer)), transferUi(transfer).message)
            edition != null && edition.state == EditionState.REPAIR_NEEDED -> CatalogRow(face, CatalogAction.RepairNeeded, Copy.DAMAGED_COPY)
            edition != null -> CatalogRow(
                face.copy(cover = edition.coverFile?.let { CoverImage.Local(File(editionDir, it)) } ?: face.cover),
                CatalogAction.Ready(
                    showsPause = live.showsPause(key),
                    starting = live.waitsForAudio(key),
                    start = StartAction.of(hasPlace = progress?.hasPlace == true, finished = progress?.finished == true),
                ),
            )
            unsupported -> CatalogRow(face, CatalogAction.Unsupported, Copy.UNSUPPORTED_FORMAT)
            !canDownload -> CatalogRow(face, CatalogAction.DownloadDisabled, Copy.DOWNLOAD_DISABLED)
            else -> CatalogRow(face, CatalogAction.Download)
        }
    }
}
