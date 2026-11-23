package ch.lkmc.pageworn.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.pageworn.AppContainer
import ch.lkmc.pageworn.account.ServerNotice
import ch.lkmc.pageworn.catalog.CatalogListing
import ch.lkmc.pageworn.catalog.CatalogSort
import ch.lkmc.pageworn.catalog.ListingStatus
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.data.db.BookProgressEntity
import ch.lkmc.pageworn.data.db.LibraryEntity
import ch.lkmc.pageworn.data.db.LocalEditionEntity
import ch.lkmc.pageworn.data.db.ProfileEntity
import ch.lkmc.pageworn.data.db.TransferEntity
import ch.lkmc.pageworn.playback.LivePlayback
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.EmptyState
import ch.lkmc.pageworn.ui.model.LibraryChoice
import ch.lkmc.pageworn.ui.model.LibraryUiState
import ch.lkmc.pageworn.ui.model.StatusLine
import ch.lkmc.pageworn.ui.model.UiAction
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Library: the chosen library's last published catalog, with each row showing the book's local
 * state and a status line for the refresh (docs/02-interface.md, Library). The first time the
 * section is shown for a profile it refreshes the libraries and the catalog.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LibraryViewModel(private val container: AppContainer) : ViewModel() {
    private val db = container.database
    private val query = MutableStateFlow("")
    private var visibleOnce = false
    private var refreshedProfileId: String? = null
    private val librariesChecked = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            db.profiles().observe().collect { profile ->
                if (visibleOnce && profile != null && refreshedProfileId != profile.profileId) {
                    refreshedProfileId = profile.profileId
                    refresh()
                }
            }
        }
    }

    private data class Scope(val profile: ProfileEntity, val libraries: List<LibraryEntity>, val selected: LibraryEntity?)

    /** What the field shows, and the settled query that drives the database search. */
    private data class Query(val typed: String, val searching: String)

    private val queries: Flow<Query> = combine(
        query,
        query.debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MS },
    ) { typed, searching -> Query(typed, searching) }.distinctUntilChanged()

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

    /** The settled search alone drives the catalog observer, so typing does not restart it. */
    private val searching = queries.map { it.searching.trim() }.distinctUntilChanged()

    private val listing = combine(scope, searching, container.settings.librarySort, ::Triple).flatMapLatest { (s, q, sort) ->
        val library = s?.selected ?: return@flatMapLatest flowOf(CatalogListing(emptyList(), ListingStatus.READY, null, sort))
        container.catalog.observeListing(s.profile.profileId, library.libraryId, q.trim(), sort)
    }

    private val local = scope.flatMapLatest { s ->
        if (s == null) {
            flowOf(LocalState())
        } else {
            combine(
                db.editions().observeAll(s.profile.profileId),
                db.transfers().observeActive(),
                db.progress().observeAll(s.profile.profileId),
                // The rows draw keys only: the position that moves four times a second would rebuild
                // every row of a large library on the main thread each time.
                container.playbackHub.state.map { it.transportOnly() }.distinctUntilChanged(),
            ) { editions, transfers, progress, live -> LocalState(editions, transfers, progress, live) }
        }
    }

    private val actions = TransferActions(container)

    private data class Conditions(val notice: ServerNotice?, val checked: Boolean, val refusal: DownloadRefusal?)

    private val conditions = combine(container.accounts.notice, librariesChecked, actions.refusal) { notice, checked, refusal -> Conditions(notice, checked, refusal) }

    val state: StateFlow<LibraryUiState> = combine(scope, queries, listing, local, conditions) { s, q, rows, l, condition ->
        withRefusal(build(s, q.typed, q.searching, rows, l, condition.notice, condition.checked), condition.refusal)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState(sort = container.settings.librarySort.value))

    /** A Download tap refused for the book itself says why on its row. */
    private fun withRefusal(state: LibraryUiState, refusal: DownloadRefusal?): LibraryUiState {
        if (refusal == null || !refusal.onRow) return state
        return state.copy(rows = state.rows.map { if (it.face.key == refusal.key) it.copy(message = refusal.text) else it })
    }

    fun download(key: BookKey) = actions.download(key)
    fun pauseTransfer(jobId: String) = actions.pause(jobId)
    fun resumeTransfer(jobId: String) = actions.resume(jobId)
    fun cancelTransfer(jobId: String) = actions.cancel(jobId)

    fun firstVisible() {
        if (visibleOnce) return
        visibleOnce = true
        viewModelScope.launch {
            db.profiles().current()?.let { profile ->
                if (refreshedProfileId != profile.profileId) {
                    refreshedProfileId = profile.profileId
                    refresh()
                }
            }
        }
    }

    /** Runs in the application scope, so leaving the screen does not cancel a refresh. */
    fun refresh() {
        container.appScope.launch { if (container.accounts.refreshLibraryAndCatalog()) librariesChecked.value = true }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun setSort(sort: CatalogSort) = container.settings.setLibrarySort(sort)

    fun selectLibrary(libraryId: String) {
        container.appScope.launch {
            container.accounts.chooseLibrary(libraryId)
            container.accounts.refreshLibraryAndCatalog(libraryId)
        }
    }

    private fun build(s: Scope?, q: String, searching: String, listing: CatalogListing, local: LocalState, notice: ServerNotice?, checked: Boolean): LibraryUiState {
        if (s == null) return LibraryUiState()
        val status = statusOf(notice, listing)
        if (s.libraries.isEmpty()) {
            if (!checked && notice == null) return LibraryUiState(status = StatusLine(Copy.LIBRARY_LOADING, showsProgress = true), showSelectorAndSearch = false)
            return LibraryUiState(
                empty = if (notice != null) status?.let { EmptyState(it.text, it.action, it.actionLabel) }
                    else EmptyState(Copy.NO_AUDIOBOOK_LIBRARIES, UiAction.CHECK_AGAIN, Copy.CHECK_AGAIN),
                showSelectorAndSearch = false,
            )
        }
        val library = checkNotNull(s.selected) { "A profile with libraries has a selected one" }
        val editions = local.editions.associateBy { it.itemId }
        val transfers = local.transfers.filter { it.profileId == s.profile.profileId }.associateBy { it.itemId }
        val progress = local.progress.associateBy { it.itemId }
        val catalogRows = listing.rows.map { book ->
            val edition = editions[book.itemId]
            RowBuilders.catalogRow(
                book = book,
                profileId = s.profile.profileId,
                libraryId = library.libraryId,
                edition = edition,
                editionDir = edition?.let { container.editionFiles.published(it.generationId) },
                transfer = transfers[book.itemId],
                progress = progress[book.itemId],
                live = local.live,
                canDownload = s.profile.canDownload,
                unsupported = false,
            )
        }
        val statusAsEmpty = status?.let { EmptyState(it.text, it.action, it.actionLabel) }
        val published = listing.lastRefreshAt != null
        val empty = when {
            catalogRows.isNotEmpty() -> null
            // A cached catalog stays searchable while the server is away; the status line stays above.
            searching.isNotBlank() && published -> EmptyState(Copy.NO_SEARCH_RESULTS, UiAction.CLEAR_SEARCH, Copy.CLEAR_SEARCH)
            notice != null -> statusAsEmpty
            listing.status == ListingStatus.FILLING -> null
            searching.isNotBlank() -> EmptyState(Copy.NO_SEARCH_RESULTS, UiAction.CLEAR_SEARCH, Copy.CLEAR_SEARCH)
            // A failed load never claims an empty library; the status line stays authoritative.
            listing.status == ListingStatus.INCOMPLETE -> null
            // A pending or typed query must not borrow the empty-library prompt.
            published && q.isBlank() -> EmptyState(Copy.EMPTY_LIBRARY, UiAction.CHECK_AGAIN, Copy.CHECK_AGAIN)
            else -> null
        }
        return LibraryUiState(
            libraries = s.libraries.map { LibraryChoice(it.libraryId, it.name) },
            selectedLibraryId = library.libraryId,
            query = q,
            sort = listing.sort,
            rows = catalogRows,
            // An empty state that carries the status replaces the status line.
            status = if (empty != null && empty == statusAsEmpty) null else status,
            empty = empty,
        )
    }

    private fun statusOf(notice: ServerNotice?, listing: CatalogListing): StatusLine? {
        if (notice != null) return when (notice) {
            ServerNotice.Unreachable -> StatusLine(Copy.UNREACHABLE + (listing.lastRefreshAt?.let { " " + Copy.lastRefresh(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))) } ?: ""), UiAction.RETRY, Copy.RETRY)
            ServerNotice.SignedOut -> StatusLine(Copy.SESSION_EXPIRED, UiAction.SIGN_IN, Copy.SIGN_IN)
            is ServerNotice.CertificateChanged -> StatusLine(Copy.certificateChanged(notice.label), UiAction.OPEN_ADDRESSES, Copy.SERVER_ADDRESSES)
            is ServerNotice.CertificateUnrecognized -> StatusLine(Copy.UNRECOGNIZED_CERTIFICATE, UiAction.OPEN_ADDRESSES, Copy.SERVER_ADDRESSES)
            is ServerNotice.LocalNetworkDenied -> StatusLine(Copy.localNetworkDenied(notice.label), UiAction.ALLOW_LOCAL_NETWORK, Copy.ALLOW_ACCESS)
        }
        return when (listing.status) {
            ListingStatus.FILLING -> StatusLine(Copy.LIBRARY_LOADING, showsProgress = true)
            ListingStatus.INCOMPLETE -> StatusLine(Copy.LIBRARY_INCOMPLETE, UiAction.RETRY, Copy.RETRY)
            ListingStatus.READY -> null
        }
    }

    private data class LocalState(
        val editions: List<LocalEditionEntity> = emptyList(),
        val transfers: List<TransferEntity> = emptyList(),
        val progress: List<BookProgressEntity> = emptyList(),
        val live: LivePlayback = LivePlayback(),
    )
}

/** Typing settles before the catalog is searched; clearing stays immediate. */
private const val SEARCH_DEBOUNCE_MS = 200L
