package ch.lkmc.pageworn.catalog

import ch.lkmc.pageworn.server.ServerResult
import kotlinx.coroutines.CompletableDeferred

/**
 * A [CatalogSource] whose pages return fixed results, fail, or stay held until the test releases
 * them with a [CompletableDeferred]. Pages without a handler answer an empty page of total 0.
 */
class FakeCatalogSource : CatalogSource {
    private val pageHandlers = HashMap<Int, suspend () -> ServerResult<CatalogPage>>()
    private var allHandler: (suspend () -> ServerResult<CatalogPage>)? = null

    val pageCalls = mutableListOf<Pair<Int, Int>>()
    var allCalls = 0

    fun page(index: Int, entries: List<CatalogEntry>, total: Int) {
        pageHandlers[index] = { ServerResult.Ok(CatalogPage(entries, total)) }
    }

    fun page(index: Int, result: ServerResult<CatalogPage>) {
        pageHandlers[index] = { result }
    }

    fun page(index: Int, held: CompletableDeferred<ServerResult<CatalogPage>>) {
        pageHandlers[index] = { held.await() }
    }

    fun all(entries: List<CatalogEntry>, total: Int) {
        allHandler = { ServerResult.Ok(CatalogPage(entries, total)) }
    }

    override suspend fun page(page: Int, limit: Int): ServerResult<CatalogPage> {
        pageCalls += page to limit
        return pageHandlers[page]?.invoke() ?: ServerResult.Ok(CatalogPage(emptyList(), 0))
    }

    override suspend fun all(): ServerResult<CatalogPage> {
        allCalls++
        return allHandler?.invoke() ?: ServerResult.Ok(CatalogPage(emptyList(), 0))
    }

    fun entry(
        id: String,
        title: String = id,
        authors: String? = "Author of $id",
        narrators: String? = null,
        numTracks: Int = 1,
        addedAt: Long? = null,
    ) = CatalogEntry(
        itemId = id,
        title = title,
        authors = authors,
        narrators = narrators,
        updatedAt = 42L,
        durationMs = 60_000L,
        sizeBytes = 1_000L,
        numTracks = numTracks,
        addedAt = addedAt,
    )
}
