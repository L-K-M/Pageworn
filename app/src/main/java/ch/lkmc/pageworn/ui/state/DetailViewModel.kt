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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal sealed interface DetailState {
    data object Loading : DetailState
    data object Missing : DetailState
    data class Available(val book: BookDetailUiState) : DetailState
}

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

    /**
     * Set once this screen removes the audio. The confirmation stays in memory for this screen;
     * its display is masked while an edition or transfer exists, so another path removing the
     * book cannot resurrect it.
     */
    private val _removed = MutableStateFlow(false)
    private val removed = _removed

    private data class RevisionPreview(val updatedAt: Long, val book: BookPreview)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val preview = inputs.map { i ->
        i.book?.updatedAt?.takeIf { i.edition == null && i.profile?.profileId == key.profileId }
    }.distinctUntilChanged().flatMapLatest { revision ->
        flow<RevisionPreview?> {
            emit(null)
            if (revision != null) {
                val book = withContext(Dispatchers.IO) { container.previews.preview(key, revision) }
                if (book != null) emit(RevisionPreview(revision, book))
            }
        }
    }

    internal val state: StateFlow<DetailState> = combine(
        inputs,
        container.playbackHub.state,
        actions.refusal,
        removed,
        preview,
    ) { i, live, refusal, removed, preview ->
        val currentPreview = preview?.takeIf { it.updatedAt == i.book?.updatedAt && i.edition == null }?.book
        val built = build(i, i.edition?.let { plans.plan(it) }, live, currentPreview) ?: return@combine DetailState.Missing
        DetailState.Available(run {
            val removalIsNewest = removed && i.edition == null && i.transfer == null
            val refusalHere = refusal?.takeIf { it.key == key && !removalIsNewest }
            when {
                // A refusal about this book is newer and actionable while the book still
                // exists, and outranks the removal confirmation. Once the book is gone, the
                // removal is the newest fact.
                refusalHere != null -> {
                    // Only the primary Download action gives way. A playable, transferring or
                    // repairing book keeps its own control and carries the message beside it.
                    val action = if (built.action == DetailAction.Download) refusalHere.disabledAction ?: built.action else built.action
                    built.copy(message = refusalHere.text, action = action)
                }
                removalIsNewest -> {
                    // The saved place survives the removal, and the listener should hear so.
                    built.copy(message = Copy.AUDIO_REMOVED)
                }
                else -> built
            }
        })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState.Loading)

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
            container.transfers.remove(key)
            _removed.value = true
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
        val previewTimeline = preview?.timeline?.takeIf { it.division != Division.PARTS }
        return BookDetailUiState(
            face = BookFace(key, title, authors, cover),
            narrator = shownNarrator(edition?.narrators, book?.narrators),
            durationText = durationMs?.let { Format.bookLength(it) },
            sizeText = sizeBytes?.let { Format.bytes(it) },
            action = action,
            message = message,
            description = edition?.description?.takeIf(String::isNotBlank) ?: preview?.description ?: book?.description,
            segmentsTitle = plan?.let { PlayerStates.segmentsLabel(it.timeline.division) }
                ?: previewTimeline?.let { PlayerStates.segmentsLabel(it.division) },
            segments = plan?.let { PlayerStates.segments(it, position) }
                ?: previewTimeline?.let { PlayerStates.segments(it, positionMs = null) }.orEmpty(),
            segmentsPlayable = edition != null && edition.state == EditionState.READY,
            canRemove = edition != null,
        )
    }
}

/**
 * The narrator to print: the edition's, else the catalog's. Audiobookshelf sends an empty string
 * for a book without one, which must not print a bare "Narrated by".
 */
internal fun shownNarrator(editionNarrators: String?, bookNarrators: String?): String? =
    listOf(editionNarrators, bookNarrators).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
