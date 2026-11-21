package ch.lkmc.pageworn.catalog

import ch.lkmc.pageworn.server.ServerError
import ch.lkmc.pageworn.server.ServerResult

/**
 * The catalog endpoints of one reachable address. [page] walks `GET /api/libraries/{id}/items`
 * with a positive limit; [all] is the single `limit=0` request that returns the whole library.
 */
interface CatalogSource {
    suspend fun page(page: Int, limit: Int): ServerResult<CatalogPage>

    suspend fun all(): ServerResult<CatalogPage>
}

/** How one [CatalogRefresher.refresh] call ended. */
sealed interface RefreshOutcome {
    /** The attempt was still the newest when it committed; [count] playable rows are published. */
    data class Published(val count: Int) : RefreshOutcome

    /** A page or the whole-library refetch failed. [firstLoad] is true when no refresh of this library had been published. */
    data class Failed(val error: ServerError, val firstLoad: Boolean) : RefreshOutcome

    /** A newer attempt of the same library started while this one was walking or publishing. */
    data object Superseded : RefreshOutcome
}
