package ch.lkmc.pageworn.catalog

import androidx.room.withTransaction
import ch.lkmc.pageworn.data.db.CatalogBookEntity
import ch.lkmc.pageworn.data.db.CatalogStagingEntity
import ch.lkmc.pageworn.data.db.PagewornDatabase
import ch.lkmc.pageworn.data.db.RefreshAttemptEntity
import ch.lkmc.pageworn.data.db.RefreshState
import ch.lkmc.pageworn.server.ServerError
import ch.lkmc.pageworn.server.ServerResult
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Atomic refresh of one library's catalog, following "Publishing a library refresh" in
 * docs/04-server-and-downloads.md. Each attempt stages its pages under its own refresh ID and
 * publishes in one Room transaction that re-checks it is still the newest attempt, so a newer
 * refresh always wins and a failed or superseded attempt never touches the published listing.
 * Nothing here touches editions or progress: removing a remote listing preserves local books.
 */
class CatalogRefresher(
    private val db: PagewornDatabase,
    private val nowMs: () -> Long,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val pageSize: Int = 100,
) {
    /**
     * Walks the library's pages, refetches once when the walk's unique item count differs from
     * the last response's total, then publishes atomically. The library row must exist.
     */
    suspend fun refresh(profileId: String, libraryId: String, source: CatalogSource): RefreshOutcome {
        val library = db.libraries().get(profileId, libraryId)
            ?: throw IllegalArgumentException("no library $libraryId for profile $profileId")
        val refreshId = newId()
        val firstLoad = library.publishedRefreshId == null
        // The sequence is read and claimed in one transaction, so concurrent refreshes of the
        // same library never share one.
        val sequence = db.withTransaction {
            val sequence = db.catalog().maxSequence(profileId, libraryId) + 1
            db.catalog().insertAttempt(
                RefreshAttemptEntity(
                    refreshId = refreshId,
                    profileId = profileId,
                    libraryId = libraryId,
                    sequence = sequence,
                    startedAt = nowMs(),
                    state = RefreshState.RUNNING,
                    firstLoad = firstLoad,
                )
            )
            // A failed first load keeps its staged rows on screen only until a new attempt starts.
            db.catalog().discardOtherStaging(profileId, libraryId, refreshId)
            sequence
        }

        val seen = LinkedHashMap<String, CatalogEntry>()
        var lastTotal = -1
        var pageIndex = 0
        while (true) {
            when (val result = source.page(pageIndex, pageSize)) {
                is ServerResult.Failed -> return fail(profileId, libraryId, refreshId, sequence, firstLoad, result.error)
                is ServerResult.Ok -> {
                    lastTotal = result.value.total
                    stagePage(refreshId, seen, result.value.entries)
                    if (isSuperseded(profileId, libraryId, sequence)) return supersede(refreshId)
                    if (result.value.entries.size < pageSize || seen.size >= lastTotal) break
                    // A walk that keeps receiving full pages past the total has drifted; the
                    // completeness check below refetches the library instead.
                    if (pageIndex > lastTotal / pageSize + 1) break
                    pageIndex++
                }
            }
        }

        // The walk is complete only when the unique item IDs received equal the last total.
        if (seen.size != lastTotal) {
            when (val result = source.all()) {
                is ServerResult.Failed -> return fail(profileId, libraryId, refreshId, sequence, firstLoad, result.error)
                is ServerResult.Ok -> {
                    lastTotal = result.value.total
                    restageAll(refreshId, seen, result.value.entries)
                    if (isSuperseded(profileId, libraryId, sequence)) return supersede(refreshId)
                }
            }
        }
        return publish(profileId, libraryId, refreshId, sequence, lastTotal)
    }

    /**
     * The listing the Library screen shows. A published refresh always wins, so a running or
     * failed later refresh does not change what is shown; without one, the latest attempt's
     * playable staging is shown while it fills or after it failed.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeListing(profileId: String, libraryId: String, query: String, sort: CatalogSort = CatalogSort.DEFAULT): Flow<CatalogListing> {
        val escaped = escapeLike(query)
        return combine(
            db.libraries().observeOne(profileId, libraryId),
            db.catalog().observeLatestAttempt(profileId, libraryId),
        ) { library, attempt -> library to attempt }
            .flatMapLatest { (library, attempt) ->
                val rowsFlow: Flow<List<CatalogRow>>
                val status: ListingStatus
                if (library?.publishedRefreshId != null) {
                    status = ListingStatus.READY
                    rowsFlow = db.catalog().observe(profileId, libraryId, escaped).map { books -> books.map { it.toRow() } }
                } else if (attempt == null) {
                    status = ListingStatus.READY
                    rowsFlow = flowOf(emptyList())
                } else {
                    status = when (attempt.state) {
                        RefreshState.RUNNING -> ListingStatus.FILLING
                        RefreshState.FAILED -> ListingStatus.INCOMPLETE
                        RefreshState.PUBLISHED, RefreshState.SUPERSEDED -> ListingStatus.READY
                    }
                    rowsFlow = db.catalog().observeStaged(attempt.refreshId, escaped).map { staged -> staged.map { it.toRow() } }
                }
                rowsFlow
                    .map { rows -> CatalogListing(sort.order(rows), status, library?.lastRefreshAt, sort) }
                    // A large library's collation is work for a background thread, not the collector's.
                    .flowOn(Dispatchers.Default)
            }
    }

    /** Stages one page; an item ID seen twice is staged once, with its first sort index. */
    private suspend fun stagePage(refreshId: String, seen: LinkedHashMap<String, CatalogEntry>, entries: List<CatalogEntry>) {
        val fresh = ArrayList<CatalogStagingEntity>(entries.size)
        for (entry in entries) {
            if (seen.putIfAbsent(entry.itemId, entry) != null) continue
            fresh += entry.toStaging(refreshId, seen.size - 1)
        }
        if (fresh.isNotEmpty()) db.catalog().stage(fresh)
    }

    /** Replaces this attempt's staging with the whole-library refetch's entries. */
    private suspend fun restageAll(refreshId: String, seen: LinkedHashMap<String, CatalogEntry>, entries: List<CatalogEntry>) {
        seen.clear()
        db.withTransaction {
            db.catalog().discardStaging(refreshId)
            val fresh = ArrayList<CatalogStagingEntity>(entries.size)
            for (entry in entries) {
                if (seen.putIfAbsent(entry.itemId, entry) != null) continue
                fresh += entry.toStaging(refreshId, seen.size - 1)
            }
            if (fresh.isNotEmpty()) db.catalog().stage(fresh)
        }
    }

    private suspend fun isSuperseded(profileId: String, libraryId: String, sequence: Long): Boolean =
        db.catalog().maxSequence(profileId, libraryId) > sequence

    private suspend fun supersede(refreshId: String): RefreshOutcome {
        db.withTransaction {
            db.catalog().setAttemptState(refreshId, RefreshState.SUPERSEDED)
            db.catalog().discardStaging(refreshId)
        }
        return RefreshOutcome.Superseded
    }

    /**
     * Marks a failed attempt. A failed first load keeps its staged rows on screen; any other
     * failure discards them. The published listing and lastRefreshAt are never touched here.
     */
    private suspend fun fail(
        profileId: String,
        libraryId: String,
        refreshId: String,
        sequence: Long,
        firstLoad: Boolean,
        error: ServerError,
    ): RefreshOutcome {
        if (isSuperseded(profileId, libraryId, sequence)) return supersede(refreshId)
        db.withTransaction {
            db.catalog().setAttemptState(refreshId, RefreshState.FAILED)
            if (!firstLoad) db.catalog().discardStaging(refreshId)
        }
        return RefreshOutcome.Failed(error, firstLoad)
    }

    /**
     * Publishes in one transaction. If this attempt is no longer the newest or no longer RUNNING,
     * it is superseded instead and its staging is discarded; otherwise the library's catalog rows
     * are replaced with the playable staged entries and the refresh time advances. A successful
     * empty result clears the listing.
     */
    private suspend fun publish(
        profileId: String,
        libraryId: String,
        refreshId: String,
        sequence: Long,
        serverTotal: Int,
    ): RefreshOutcome = db.withTransaction {
        val attempt = db.catalog().attempt(refreshId)
        val newest = db.catalog().maxSequence(profileId, libraryId)
        if (attempt == null || attempt.state != RefreshState.RUNNING || sequence < newest) {
            db.catalog().setAttemptState(refreshId, RefreshState.SUPERSEDED)
            db.catalog().discardStaging(refreshId)
            return@withTransaction RefreshOutcome.Superseded
        }
        val playable = db.catalog().staged(refreshId).filter { it.playable }
        db.catalog().clearLibrary(profileId, libraryId)
        db.catalog().insertAll(playable.map { it.toBook(profileId, libraryId, refreshId) })
        db.libraries().markPublished(profileId, libraryId, refreshId, nowMs())
        db.catalog().setAttemptState(refreshId, RefreshState.PUBLISHED, serverTotal)
        db.catalog().discardStaging(refreshId)
        RefreshOutcome.Published(playable.size)
    }

    private fun CatalogEntry.toStaging(refreshId: String, sortIndex: Int) = CatalogStagingEntity(
        refreshId = refreshId,
        itemId = itemId,
        title = title,
        authors = authors,
        narrators = narrators,
        sortIndex = sortIndex,
        updatedAt = updatedAt,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        playable = numTracks > 0,
        addedAt = addedAt,
    )

    private fun CatalogStagingEntity.toBook(profileId: String, libraryId: String, refreshId: String) = CatalogBookEntity(
        profileId = profileId,
        libraryId = libraryId,
        itemId = itemId,
        title = title,
        authors = authors,
        narrators = narrators,
        sortIndex = sortIndex,
        updatedAt = updatedAt,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        refreshId = refreshId,
        addedAt = addedAt,
    )

    private fun CatalogBookEntity.toRow() = CatalogRow(itemId, title, authors, narrators, updatedAt, durationMs, sizeBytes, addedAt)

    private fun CatalogStagingEntity.toRow() = CatalogRow(itemId, title, authors, narrators, updatedAt, durationMs, sizeBytes, addedAt)

    companion object {
        /** Escapes the LIKE wildcards and the escape character so the query matches literally. */
        private fun escapeLike(query: String): String {
            val escaped = StringBuilder(query.length)
            for (c in query) {
                if (c == '\\' || c == '%' || c == '_') escaped.append('\\')
                escaped.append(c)
            }
            return escaped.toString()
        }
    }
}
