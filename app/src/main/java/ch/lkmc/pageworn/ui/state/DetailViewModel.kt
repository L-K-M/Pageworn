package ch.lkmc.pageworn.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.pageworn.AppContainer
import ch.lkmc.pageworn.catalog.BookPreview
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.timeline.Division
import ch.lkmc.pageworn.data.db.BookProgressEntity
import ch.lkmc.pageworn.data.db.CatalogBookEntity
import ch.lkmc.pageworn.data.db.EditionState
import ch.lkmc.pageworn.data.db.LocalEditionEntity
import ch.lkmc.pageworn.data.db.ProfileEntity
import ch.lkmc.pageworn.data.db.TransferEntity
import ch.lkmc.pageworn.playback.EditionPlan
import ch.lkmc.pageworn.playback.LivePlayback
import ch.lkmc.pageworn.ui.model.BookDetailUiState
import ch.lkmc.pageworn.ui.model.BookFace
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.CoverImage
import ch.lkmc.pageworn.ui.model.DetailAction
import ch.lkmc.pageworn.ui.model.Format
import ch.lkmc.pageworn.ui.model.StartAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** Book details: the catalog entry or the downloaded edition, whichever exists, with its local state. */
class DetailViewModel(private val container: AppContainer, private val key: BookKey) : ViewModel() {
    private val db = container.database
    private val plans = PlanCache(container)

    private data class Inputs(
        val profile: ProfileEntity?,
        val book: CatalogBookEntity?,
        val edition: LocalEditionEntity?,
        val transfer: TransferEntity?,
        val progress: BookProgressEntity?,
    )

    private val inputs = combine(
        db.profiles().observe(),
        db.catalog().observeOne(key.profileId, key.itemId),
        db.editions().observeEdition(key.profileId, key.itemId),
        db.transfers().observeActive().map { all -> all.firstOrNull { it.key == key } },
        db.progress().observe(key.profileId, key.itemId),
    ) { profile, book, edition, transfer, progress -> Inputs(profile, book, edition, transfer, progress) }

    private val actions = TransferActions(container)

    /** The server's description, chapters and format check for a book that is not on the phone. */
    private val preview = MutableStateFlow<BookPreview?>(null)

    init {
        viewModelScope.launch {
            // A downloaded edition carries its own description and chapters, so only a book
            // known from the catalog alone asks the server, once, while the page shows what it has.
            val first = inputs.first { it.book != null || it.edition != null }
            val book = first.book?.takeIf { first.edition == null } ?: return@launch
            if (first.profile?.profileId != key.profileId) return@launch
            preview.value = container.previews.preview(key, book.updatedAt)
        }
    }

    val state: StateFlow<BookDetailUiState?> = combine(inputs, container.playbackHub.state, actions.refusal, preview) { i, live, refusal, preview ->
        build(i, i.edition?.let { plans.plan(it) }, live, preview)?.let { built -> refusal?.let { built.copy(message = it.text) } ?: built }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Download, or Repair download: stages a fresh generation and keeps the saved place. */
    fun download() = actions.download(key)
    fun pauseTransfer(jobId: String) = actions.pause(jobId)
    fun resumeTransfer(jobId: String) = actions.resume(jobId)
    fun cancelTransfer(jobId: String) = actions.cancel(jobId)

    /** Removes the downloaded audio; the saved place stays (docs/03-android-and-data.md). */
    fun remove() {
        // Not the screen's scope: leaving details must not stop between releasing and removing.
        container.appScope.launch {
            container.playerConnection.releaseBook(key)
            container.editionStore.remove(key)
        }
    }

    private fun build(i: Inputs, plan: EditionPlan?, live: LivePlayback, preview: BookPreview?): BookDetailUiState? {
        val edition = i.edition
        val book = i.book
        if (edition == null && book == null) return null

        val title = edition?.title ?: book!!.title
        val authors = edition?.authors ?: book?.authors
        val cover = edition?.coverFile?.let { CoverImage.Local(File(container.editionFiles.published(edition.generationId), it)) }
            ?: book?.let { CoverImage.Remote(it.profileId, it.itemId, it.updatedAt) }
            ?: CoverImage.Cloth
        val durationMs = edition?.durationMs ?: book?.durationMs
        val sizeBytes = edition?.sizeBytes ?: book?.sizeBytes
        val isLive = live.key == key
        val hasPlace = (i.progress?.positionMs ?: 0) > 0 && i.progress?.finished != true

        val action = when {
            i.transfer != null -> DetailAction.Transferring(RowBuilders.transferUi(i.transfer))
            edition?.state == EditionState.REPAIR_NEEDED -> DetailAction.Repair
            edition != null -> DetailAction.Play(StartAction.of(hasPlace, finished = i.progress?.finished == true), live.showsPause(key), live.waitsForAudio(key))
            i.profile?.canDownload == false -> DetailAction.DownloadDisabled
            preview?.unsupported == true -> DetailAction.Unsupported
            else -> DetailAction.Download
        }
        val message = when (action) {
            DetailAction.DownloadDisabled -> Copy.DOWNLOAD_DISABLED
            DetailAction.Repair -> Copy.DAMAGED_COPY
            DetailAction.Unsupported -> Copy.UNSUPPORTED_FORMAT
            is DetailAction.Transferring -> action.transfer.message
            else -> null
        }
        val position = if (isLive) live.positionMs else i.progress?.positionMs
        // Before a download, the server's chapters show what the book holds; its generated
        // ten-minute parts would say nothing about it.
        val previewTimeline = preview?.timeline?.takeIf { edition == null && it.division != Division.PARTS }
        return BookDetailUiState(
            face = BookFace(key, title, authors, cover),
            narrator = edition?.narrators ?: book?.narrators,
            durationText = durationMs?.let { Format.hoursMinutes(it) },
            sizeText = sizeBytes?.let { Format.bytes(it) },
            action = action,
            message = message,
            description = edition?.description ?: preview?.description,
            segmentsTitle = plan?.let { PlayerStates.segmentsLabel(it.timeline.division) } ?: previewTimeline?.let { PlayerStates.segmentsLabel(it.division) },
            segments = plan?.let { PlayerStates.segments(it, position) } ?: previewTimeline?.let { PlayerStates.segments(it, positionMs = null) } ?: emptyList(),
            segmentsPlayable = edition != null && edition.state == EditionState.READY,
            canRemove = edition != null,
        )
    }
}
