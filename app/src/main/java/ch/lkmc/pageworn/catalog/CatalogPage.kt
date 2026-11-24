package ch.lkmc.pageworn.catalog

/**
 * One entry of a library listing as the server returns it (`minified=1`). Entries whose
 * [numTracks] is zero count toward the walk's completeness but are never published.
 */
data class CatalogEntry(
    val itemId: String,
    val title: String,
    val authors: String?,
    val narrators: String?,
    /** Item `updatedAt` in epoch milliseconds; the cover cache key. */
    val updatedAt: Long,
    val durationMs: Long?,
    val sizeBytes: Long?,
    val numTracks: Int,
    /** Item `addedAt` in epoch milliseconds: when the book came to the server. */
    val addedAt: Long? = null,
    /** Item description, plain text preferred; shown on details before a download. */
    val description: String? = null,
)

/** One page of `GET /api/libraries/{id}/items`, or the whole library for a `limit=0` request. */
data class CatalogPage(val entries: List<CatalogEntry>, val total: Int)

/** One book library the account can read. */
data class RemoteLibrary(val libraryId: String, val name: String, val displayOrder: Int)
