package ch.lkmc.pageworn.catalog

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.pageworn.data.SettingsStore
import ch.lkmc.pageworn.data.db.LibraryEntity
import ch.lkmc.pageworn.data.db.PagewornDatabase
import ch.lkmc.pageworn.server.ServerResult
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A18b: the orders of Library, the same for published, staged and searched rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogSortTest {
    private lateinit var db: PagewornDatabase
    private lateinit var refresher: CatalogRefresher
    private val source = FakeCatalogSource()

    /** As the server walks them, in title order. */
    private val entries = with(source) {
        listOf(
            entry("a", title = "Apricot", authors = "Zora Hale", addedAt = 200),
            entry("b", title = "Bramble", authors = null, addedAt = 300),
            entry("c", title = "Cedar", authors = "ada bell", addedAt = null),
            entry("d", title = "Damson", authors = "\u00c9lise Rowe", addedAt = 100),
            entry("e", title = "Elder", authors = "Ada Bell", addedAt = 200),
            entry("f", title = "Fig", authors = "Fern Orr", addedAt = null),
        )
    }

    @Before
    fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PagewornDatabase::class.java).build()
        refresher = CatalogRefresher(db, nowMs = { 1_000L }, pageSize = 100)
        db.libraries().upsertAll(listOf(LibraryEntity(PROFILE, LIBRARY, LIBRARY, 0)))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun publish() {
        source.page(0, entries, total = entries.size)
        refresher.refresh(PROFILE, LIBRARY, source)
    }

    private suspend fun order(sort: CatalogSort, query: String = "") =
        refresher.observeListing(PROFILE, LIBRARY, query, sort).first().rows.joinToString("") { it.itemId }

    @Test
    fun a18bNewestIsFirstByDefault() = runBlocking<Unit> {
        publish()
        assertThat(CatalogSort.DEFAULT).isEqualTo(CatalogSort.DATE_ADDED_NEWEST)
        // Equal dates keep title order, and books without a date come last.
        assertThat(order(CatalogSort.DEFAULT)).isEqualTo("baedcf")
    }

    @Test
    fun a18bTheOtherOrders() = runBlocking<Unit> {
        publish()
        assertThat(order(CatalogSort.DATE_ADDED_OLDEST)).isEqualTo("daebcf")
        assertThat(order(CatalogSort.TITLE)).isEqualTo("abcdef")
        // Case and accents do not split authors, and books without one come last.
        assertThat(order(CatalogSort.AUTHOR)).isEqualTo("cedfab")
    }

    @Test
    fun a18bSearchKeepsTheOrder() = runBlocking<Unit> {
        publish()
        assertThat(order(CatalogSort.DATE_ADDED_NEWEST, "ada")).isEqualTo("ec")
        assertThat(order(CatalogSort.TITLE, "ada")).isEqualTo("ce")
    }

    @Test
    fun a18bAFirstLoadShowsItsStagedRowsInTheSameOrder() = runBlocking<Unit> {
        val held = CompletableDeferred<ServerResult<CatalogPage>>()
        val paged = CatalogRefresher(db, nowMs = { 1_000L }, pageSize = entries.size)
        source.page(0, entries, total = entries.size + 1)
        source.page(1, held)
        val refresh = launch { paged.refresh(PROFILE, LIBRARY, source) }
        val staged = paged.observeListing(PROFILE, LIBRARY, "", CatalogSort.DEFAULT).first { it.rows.size == entries.size }
        assertThat(staged.status).isEqualTo(ListingStatus.FILLING)
        assertThat(staged.rows.joinToString("") { it.itemId }).isEqualTo("baedcf")
        held.complete(ServerResult.Ok(CatalogPage(listOf(source.entry("g", title = "Gorse", addedAt = 400)), entries.size + 1)))
        refresh.join()
        assertThat(order(CatalogSort.DEFAULT)).isEqualTo("gbaedcf")
    }

    @Test
    fun a18bTheChoiceSurvivesARelaunch() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val settings = SettingsStore(context)
        assertThat(settings.librarySort.value).isEqualTo(CatalogSort.DATE_ADDED_NEWEST)
        settings.setLibrarySort(CatalogSort.AUTHOR)
        assertThat(SettingsStore(context).librarySort.value).isEqualTo(CatalogSort.AUTHOR)
    }

    private companion object {
        const val PROFILE = "profile-1"
        const val LIBRARY = "library"
    }
}
