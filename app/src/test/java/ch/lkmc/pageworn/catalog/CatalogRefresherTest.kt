package ch.lkmc.pageworn.catalog

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.data.db.BookProgressEntity
import ch.lkmc.pageworn.data.db.EditionState
import ch.lkmc.pageworn.data.db.LibraryEntity
import ch.lkmc.pageworn.data.db.LocalEditionEntity
import ch.lkmc.pageworn.data.db.PagewornDatabase
import ch.lkmc.pageworn.data.db.RefreshState
import ch.lkmc.pageworn.server.ServerError
import ch.lkmc.pageworn.server.ServerResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A01, A01b and A01c: the atomic library refresh. In-memory Room under Robolectric, a fake
 * [CatalogSource] whose pages can fail or stay held, and a deterministic clock and ID source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogRefresherTest {
    private lateinit var db: PagewornDatabase
    private var clock = 1_000L
    private var idCounter = 0
    private lateinit var refresher: CatalogRefresher

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PagewornDatabase::class.java).build()
        clock = 1_000L
        idCounter = 0
        refresher = CatalogRefresher(db, nowMs = { clock }, newId = { "refresh-${++idCounter}" }, pageSize = 2)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun addLibrary(libraryId: String) {
        db.libraries().upsertAll(listOf(LibraryEntity(PROFILE, libraryId, libraryId, 0)))
    }

    private suspend fun listing(libraryId: String, query: String = ""): CatalogListing =
        refresher.observeListing(PROFILE, libraryId, query).first()

    private suspend fun rowIds(libraryId: String, query: String = ""): List<String> =
        listing(libraryId, query).rows.map { it.itemId }

    // --- A01 -----------------------------------------------------------------------------------

    @Test
    fun a01_walkPublishesAllPagesInServerOrderAndKeepsEqualTitlesApart() = runBlocking {
        addLibrary("lib")
        val source = FakeCatalogSource().apply {
            page(0, listOf(entry("a", "Alpha"), entry("b", "Beta")), total = 5)
            page(1, listOf(entry("c", "Gamma"), entry("d", "Same")), total = 5)
            page(2, listOf(entry("e", "Same")), total = 5)
        }

        val outcome = refresher.refresh(PROFILE, "lib", source)

        assertThat(outcome).isEqualTo(RefreshOutcome.Published(5))
        assertThat(source.pageCalls).containsExactly(0 to 2, 1 to 2, 2 to 2).inOrder()
        assertThat(source.allCalls).isEqualTo(0)
        assertThat(rowIds("lib")).containsExactly("a", "b", "c", "d", "e").inOrder()
        val equalTitles = listing("lib").rows.filter { it.title == "Same" }
        assertThat(equalTitles.map { it.itemId }).containsExactly("d", "e").inOrder()
        val library = db.libraries().get(PROFILE, "lib")!!
        assertThat(library.publishedRefreshId).isEqualTo("refresh-1")
        assertThat(library.lastRefreshAt).isEqualTo(clock)
        val attempt = db.catalog().attempt("refresh-1")!!
        assertThat(attempt.state).isEqualTo(RefreshState.PUBLISHED)
        assertThat(attempt.serverTotal).isEqualTo(5)
        assertThat(db.catalog().stagedCount("refresh-1")).isEqualTo(0)
    }

    @Test
    fun a01_itemAddedDuringWalkTriggersExactlyOneAllCallWhoseResultIsPublished() = runBlocking {
        addLibrary("lib")
        val source = FakeCatalogSource().apply {
            page(0, listOf(entry("a"), entry("b")), total = 3)
            page(1, listOf(entry("c")), total = 4) // the server grew during the walk
            all(listOf(entry("a"), entry("b"), entry("c"), entry("d")), total = 4)
        }

        val outcome = refresher.refresh(PROFILE, "lib", source)

        assertThat(outcome).isEqualTo(RefreshOutcome.Published(4))
        assertThat(source.allCalls).isEqualTo(1)
        assertThat(source.pageCalls).containsExactly(0 to 2, 1 to 2).inOrder()
        assertThat(rowIds("lib")).containsExactly("a", "b", "c", "d").inOrder()
    }

    @Test
    fun a01_duplicateIdAcrossPagesWithShortCountIsRefetchedNeverPublishedAsIs() = runBlocking {
        addLibrary("lib")
        val source = FakeCatalogSource().apply {
            page(0, listOf(entry("a"), entry("b")), total = 4)
            page(1, listOf(entry("b")), total = 4) // duplicate ID on a short page
            all(listOf(entry("a"), entry("b"), entry("c"), entry("d")), total = 4)
        }

        val outcome = refresher.refresh(PROFILE, "lib", source)

        assertThat(outcome).isEqualTo(RefreshOutcome.Published(4))
        assertThat(source.allCalls).isEqualTo(1)
        assertThat(rowIds("lib")).containsExactly("a", "b", "c", "d").inOrder()
    }

    @Test
    fun a25_zeroTrackEntriesCountTowardCompletenessButAreNotPublished() = runBlocking {
        addLibrary("lib")
        val source = FakeCatalogSource().apply {
            page(0, listOf(entry("a"), entry("ebook", numTracks = 0)), total = 3)
            page(1, listOf(entry("c")), total = 3)
        }

        val outcome = refresher.refresh(PROFILE, "lib", source)

        // The ebook counts toward the walk, so no refetch happens, but only playable rows publish.
        assertThat(outcome).isEqualTo(RefreshOutcome.Published(2))
        assertThat(source.allCalls).isEqualTo(0)
        assertThat(rowIds("lib")).containsExactly("a", "c").inOrder()
    }

    // --- A01b ----------------------------------------------------------------------------------

    @Test
    fun a01b_failedRefreshKeepsPublishedRowsAndNewerEmptyRefreshWinsOverHeldOlderAttempt() = runBlocking {
        addLibrary("libA")
        addLibrary("libB")
        clock = 1_000L
        val first = FakeCatalogSource().apply { page(0, listOf(entry("a1"), entry("a2")), total = 2) }
        assertThat(refresher.refresh(PROFILE, "libA", first)).isEqualTo(RefreshOutcome.Published(2))
        val other = FakeCatalogSource().apply { page(0, listOf(entry("b1")), total = 1) }
        assertThat(refresher.refresh(PROFILE, "libB", other)).isEqualTo(RefreshOutcome.Published(1))
        db.editions().upsertEdition(downloadedEdition("a1"))
        db.progress().upsert(savedPlace("a1"))

        // A new refresh of A whose second page fails.
        clock = 2_000L
        val failing = FakeCatalogSource().apply {
            page(0, listOf(entry("x1"), entry("x2")), total = 3)
            page(1, ServerResult.Failed(ServerError.Unreachable("boom")))
        }
        val failOutcome = refresher.refresh(PROFILE, "libA", failing)

        assertThat(failOutcome).isEqualTo(RefreshOutcome.Failed(ServerError.Unreachable("boom"), firstLoad = false))
        val libAfterFail = db.libraries().get(PROFILE, "libA")!!
        assertThat(libAfterFail.publishedRefreshId).isEqualTo("refresh-1")
        assertThat(libAfterFail.lastRefreshAt).isEqualTo(1_000L)
        assertThat(rowIds("libA")).containsExactly("a1", "a2").inOrder()
        assertThat(rowIds("libB")).containsExactly("b1")
        val shownAfterFail = listing("libA")
        assertThat(shownAfterFail.status).isEqualTo(ListingStatus.READY)
        assertThat(shownAfterFail.lastRefreshAt).isEqualTo(1_000L)
        assertThat(db.catalog().attempt("refresh-3")!!.state).isEqualTo(RefreshState.FAILED)
        assertThat(db.catalog().stagedCount("refresh-3")).isEqualTo(0)
        assertThat(db.editions().edition(PROFILE, "a1")).isNotNull()
        assertThat(db.progress().get(PROFILE, "a1")).isNotNull()

        // An older attempt with a held page, then a newer refresh that publishes an empty result.
        clock = 3_000L
        val heldPage = CompletableDeferred<ServerResult<CatalogPage>>()
        val held = FakeCatalogSource().apply { page(0, heldPage) }
        val heldOutcome = CompletableDeferred<RefreshOutcome>()
        val heldJob = launch { heldOutcome.complete(refresher.refresh(PROFILE, "libA", held)) }
        withTimeout(5_000) { while (db.catalog().maxSequence(PROFILE, "libA") < 3) delay(10) }
        val empty = FakeCatalogSource().apply { page(0, emptyList(), total = 0) }
        assertThat(refresher.refresh(PROFILE, "libA", empty)).isEqualTo(RefreshOutcome.Published(0))

        // The held page arrives afterwards and must not disturb the empty published result.
        heldPage.complete(ServerResult.Ok(CatalogPage(listOf(entry("z1")), total = 1)))
        heldJob.join()

        assertThat(heldOutcome.await()).isEqualTo(RefreshOutcome.Superseded)
        assertThat(rowIds("libA")).isEmpty()
        assertThat(db.catalog().attempt("refresh-4")!!.state).isEqualTo(RefreshState.SUPERSEDED)
        assertThat(db.catalog().stagedCount("refresh-4")).isEqualTo(0)
        val libAfterEmpty = db.libraries().get(PROFILE, "libA")!!
        assertThat(libAfterEmpty.publishedRefreshId).isEqualTo("refresh-5")
        assertThat(libAfterEmpty.lastRefreshAt).isEqualTo(3_000L)
        assertThat(rowIds("libB")).containsExactly("b1")
        assertThat(db.editions().edition(PROFILE, "a1")).isNotNull()
        assertThat(db.progress().get(PROFILE, "a1")).isNotNull()
    }

    // --- A01c ----------------------------------------------------------------------------------

    @Test
    fun a01c_firstLoadWithHeldPageShowsFillingThenPublishesReady() = runBlocking {
        addLibrary("lib")
        val heldPage = CompletableDeferred<ServerResult<CatalogPage>>()
        val source = FakeCatalogSource().apply {
            page(0, listOf(entry("p1"), entry("p2")), total = 3)
            page(1, heldPage)
        }
        val outcome = CompletableDeferred<RefreshOutcome>()
        val job = launch { outcome.complete(refresher.refresh(PROFILE, "lib", source)) }
        withTimeout(5_000) { while (db.catalog().stagedCount("refresh-1") < 2) delay(10) }

        val filling = listing("lib")
        assertThat(filling.status).isEqualTo(ListingStatus.FILLING)
        assertThat(filling.rows.map { it.itemId }).containsExactly("p1", "p2").inOrder()
        assertThat(filling.lastRefreshAt).isNull()

        heldPage.complete(ServerResult.Ok(CatalogPage(listOf(entry("p3")), total = 3)))
        job.join()

        assertThat(outcome.await()).isEqualTo(RefreshOutcome.Published(3))
        val ready = listing("lib")
        assertThat(ready.status).isEqualTo(ListingStatus.READY)
        assertThat(ready.rows.map { it.itemId }).containsExactly("p1", "p2", "p3").inOrder()
        assertThat(ready.lastRefreshAt).isEqualTo(clock)
    }

    @Test
    fun a01c_firstLoadWithFailedPageShowsIncompleteThenRetryCompletes() = runBlocking {
        addLibrary("lib")
        val source = FakeCatalogSource().apply {
            page(0, listOf(entry("p1"), entry("p2")), total = 3)
            page(1, ServerResult.Failed(ServerError.Unreachable("down")))
        }

        val outcome = refresher.refresh(PROFILE, "lib", source)

        assertThat(outcome).isEqualTo(RefreshOutcome.Failed(ServerError.Unreachable("down"), firstLoad = true))
        val incomplete = listing("lib")
        assertThat(incomplete.status).isEqualTo(ListingStatus.INCOMPLETE)
        assertThat(incomplete.rows.map { it.itemId }).containsExactly("p1", "p2").inOrder()
        assertThat(incomplete.lastRefreshAt).isNull()
        assertThat(db.catalog().stagedCount("refresh-1")).isEqualTo(2)

        val retry = FakeCatalogSource().apply {
            page(0, listOf(entry("p1"), entry("p2")), total = 3)
            page(1, listOf(entry("p3")), total = 3)
        }
        assertThat(refresher.refresh(PROFILE, "lib", retry)).isEqualTo(RefreshOutcome.Published(3))

        val ready = listing("lib")
        assertThat(ready.status).isEqualTo(ListingStatus.READY)
        assertThat(ready.rows.map { it.itemId }).containsExactly("p1", "p2", "p3").inOrder()
        assertThat(db.catalog().attempt("refresh-1")!!.state).isEqualTo(RefreshState.FAILED)
        assertThat(db.catalog().stagedCount("refresh-1")).isEqualTo(0)
    }

    @Test
    fun libraryWithoutAnyAttemptListsReadyAndEmpty() = runBlocking {
        addLibrary("lib")

        val listing = listing("lib")

        assertThat(listing.status).isEqualTo(ListingStatus.READY)
        assertThat(listing.rows).isEmpty()
        assertThat(listing.lastRefreshAt).isNull()
    }

    @Test
    fun refreshRequiresTheLibraryRow() = runBlocking {
        val source = FakeCatalogSource()

        val error = try {
            refresher.refresh(PROFILE, "missing", source)
            null
        } catch (e: IllegalArgumentException) {
            e
        }

        assertThat(error).isNotNull()
        assertThat(source.pageCalls).isEmpty()
    }

    @Test
    fun descriptionFlowsFromListingEntryToPublishedRow() = runBlocking {
        addLibrary("lib")
        val source = FakeCatalogSource().apply {
            page(0, listOf(
                CatalogEntry(
                    itemId = "a", title = "Alpha", authors = null, narrators = null, updatedAt = 42L,
                    durationMs = 60_000L, sizeBytes = 1_000L, numTracks = 1, description = "A plain tale",
                ),
            ), total = 1)
        }

        val outcome = refresher.refresh(PROFILE, "lib", source)

        assertThat(outcome).isEqualTo(RefreshOutcome.Published(1))
        assertThat(db.catalog().find(PROFILE, "a")?.description).isEqualTo("A plain tale")
    }

    private fun entry(id: String) = CatalogEntry(
        itemId = id,
        title = id,
        authors = null,
        narrators = null,
        updatedAt = 42L,
        durationMs = 60_000L,
        sizeBytes = 1_000L,
        numTracks = 1,
    )

    private fun downloadedEdition(itemId: String) = LocalEditionEntity(
        profileId = PROFILE,
        itemId = itemId,
        generationId = "gen-$itemId",
        fingerprint = "fp-$itemId",
        state = EditionState.READY,
        durationMs = 60_000L,
        relativeDir = "gen-$itemId",
        coverFile = null,
        title = "Title $itemId",
        authors = null,
        narrators = null,
        description = null,
        chapterSource = null,
        publishedAt = 500L,
        sizeBytes = 1_234L,
    )

    private fun savedPlace(itemId: String) = BookProgressEntity(
        profileId = PROFILE,
        itemId = itemId,
        positionMs = 12_000L,
        durationAtSaveMs = 60_000L,
        editionFingerprint = "fp-$itemId",
        finished = false,
        updatedAt = 600L,
        stopKind = StopKind.ORDINARY,
        lastListenedAt = 600L,
        playbackGeneration = 1,
        writeSequence = 1,
    )

    companion object {
        const val PROFILE = "p1"
    }
}
