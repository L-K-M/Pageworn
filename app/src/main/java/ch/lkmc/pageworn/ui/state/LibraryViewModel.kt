package ch.lkmc.pageworn.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.pageworn.AppContainer
import ch.lkmc.pageworn.data.db.BookProgressEntity
import ch.lkmc.pageworn.data.db.CatalogBookEntity
import ch.lkmc.pageworn.data.db.LibraryEntity
import ch.lkmc.pageworn.data.db.LocalEditionEntity
import ch.lkmc.pageworn.data.db.ProfileEntity
import ch.lkmc.pageworn.data.db.TransferEntity
import ch.lkmc.pageworn.playback.LivePlayback
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.EmptyState
import ch.lkmc.pageworn.ui.model.LibraryChoice
import ch.lkmc.pageworn.ui.model.LibraryUiState
import ch.lkmc.pageworn.ui.model.UiAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Library: the chosen library's last published catalog, with each row showing the book's local
 * state (docs/02-interface.md, Library). Network refresh and its status lines arrive with M2.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(private val container: AppContainer) : ViewModel() {
    private val db = container.database
    private val query = MutableStateFlow("")

    private data class Scope(val profile: ProfileEntity, val libraries: List<LibraryEntity>, val selected: LibraryEntity?)

    private val scope: Flow<Scope?> = db.profiles().observe().flatMapLatest { profile ->
        if (profile == null) {
            flowOf(null)
        } else {
            db.libraries().observe(profile.profileId).flatMapLatest { libraries ->
                val selected = libraries.firstOrNull { it.libraryId == profile.chosenLibraryId } ?: libraries.firstOrNull()
                flowOf(Scope(profile, libraries, selected))
            }
        }
    }

    private val books: Flow<List<CatalogBookEntity>> = combine(scope, query) { s, q -> s to q }.flatMapLatest { (s, q) ->
        val library = s?.selected ?: return@flatMapLatest flowOf(emptyList())
        db.catalog().observe(s.profile.profileId, library.libraryId, q.trim())
    }

    private val local = scope.flatMapLatest { s ->
        if (s == null) {
            flowOf(LocalState())
        } else {
            combine(
                db.editions().observeAll(s.profile.profileId),
                db.transfers().observeActive(),
                db.progress().observeAll(s.profile.profileId),
                container.playbackHub.state,
            ) { editions, transfers, progress, live -> LocalState(editions, transfers, progress, live) }
        }
    }

    val state: StateFlow<LibraryUiState> = combine(scope, query, books, local) { s, q, rows, l ->
        build(s, q, rows, l)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    fun selectLibrary(libraryId: String) {
        viewModelScope.launch {
            val profile = db.profiles().current() ?: return@launch
            db.profiles().setChosenLibrary(profile.profileId, libraryId)
        }
    }

    private fun build(s: Scope?, q: String, rows: List<CatalogBookEntity>, l: LocalState): LibraryUiState {
        if (s == null || s.libraries.isEmpty()) {
            return LibraryUiState(empty = EmptyState(Copy.NO_AUDIOBOOK_LIBRARIES), showSelectorAndSearch = false)
        }
        val editions = l.editions.associateBy { it.itemId }
        val transfers = l.transfers.filter { it.profileId == s.profile.profileId }.associateBy { it.itemId }
        val progress = l.progress.associateBy { it.itemId }
        val catalogRows = rows.map { book ->
            val edition = editions[book.itemId]
            RowBuilders.catalogRow(
                book = book,
                edition = edition,
                editionDir = edition?.let { container.editionFiles.published(it.generationId) },
                transfer = transfers[book.itemId],
                progress = progress[book.itemId],
                live = l.live,
                canDownload = s.profile.canDownload,
                unsupported = false,
            )
        }
        val empty = when {
            catalogRows.isNotEmpty() -> null
            q.isNotBlank() -> EmptyState(Copy.NO_SEARCH_RESULTS, UiAction.CLEAR_SEARCH, Copy.CLEAR_SEARCH)
            else -> EmptyState(Copy.EMPTY_LIBRARY, UiAction.CHECK_AGAIN, Copy.CHECK_AGAIN)
        }
        return LibraryUiState(
            libraries = s.libraries.map { LibraryChoice(it.libraryId, it.name) },
            selectedLibraryId = s.selected?.libraryId,
            query = q,
            rows = catalogRows,
            empty = empty,
        )
    }

    private data class LocalState(
        val editions: List<LocalEditionEntity> = emptyList(),
        val transfers: List<TransferEntity> = emptyList(),
        val progress: List<BookProgressEntity> = emptyList(),
        val live: LivePlayback = LivePlayback(),
    )
}
