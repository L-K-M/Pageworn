package ch.lkmc.pageworn.catalog

/** One row of the Library listing, from the published refresh or from staged pages. */
data class CatalogRow(
    val itemId: String,
    val title: String,
    val authors: String?,
    val narrators: String?,
    /** Item `updatedAt` in epoch milliseconds; the cover cache key. */
    val updatedAt: Long,
    val durationMs: Long?,
    val sizeBytes: Long?,
    val addedAt: Long? = null,
    val description: String? = null,
)

/** What the Library screen shows above its rows. */
enum class ListingStatus {
    /** A complete refresh is published, or there is nothing to load yet. */
    READY,

    /** No refresh was ever published and the current attempt is still walking pages. */
    FILLING,

    /** The first load failed; its staged pages stay on screen until Retry. */
    INCOMPLETE,
}

/** The listing of one library: rows in [sort] order, the status and the last publish time. */
data class CatalogListing(
    val rows: List<CatalogRow>,
    val status: ListingStatus,
    val lastRefreshAt: Long?,
    val sort: CatalogSort = CatalogSort.DEFAULT,
)
