package ch.lkmc.pageworn.catalog

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.pageworn.data.db.LibraryEntity
import ch.lkmc.pageworn.data.db.PagewornDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A25: search over the published listing. Title and author match case-insensitively, LIKE
 * wildcards in the query match literally, and only the selected library is searched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogListingTest {
    private lateinit var db: PagewornDatabase
    private var idCounter = 0
    private lateinit var refresher: CatalogRefresher

    @Before
    fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PagewornDatabase::class.java).build()
        idCounter = 0
        refresher = CatalogRefresher(db, nowMs = { 1_000L }, newId = { "refresh-${++idCounter}" }, pageSize = 100)
        publish(
            "libA",
            FakeCatalogSource().apply {
                page(
                    0,
                    listOf(
                        entry("b1", title = "The Night Orchard", authors = "Ada Lovelace"),
                        entry("b2", title = "Field Notes on Rain", authors = "Grace Hopper"),
                        entry("b3", title = "100%_Sure", authors = "Ann_Lee"),
                        entry("b5", title = "a\\b", authors = null),
                    ),
                    total = 4,
                )
            },
        )
        publish(
            "libB",
            FakeCatalogSource().apply {
                page(0, listOf(entry("b4", title = "Night Watch", authors = "Ray")), total = 1)
            },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun publish(libraryId: String, source: FakeCatalogSource) {
        db.libraries().upsertAll(listOf(LibraryEntity(PROFILE, libraryId, libraryId, 0)))
        refresher.refresh(PROFILE, libraryId, source)
    }

    private suspend fun ids(libraryId: String, query: String): List<String> =
        refresher.observeListing(PROFILE, libraryId, query).first().rows.map { it.itemId }

    private suspend fun titles(libraryId: String, query: String): List<String> =
        refresher.observeListing(PROFILE, libraryId, query).first().rows.map { it.title }

    @Test
    fun searchMatchesTitleCaseInsensitively() = runBlocking<Unit> {
        assertThat(titles("libA", "night")).containsExactly("The Night Orchard")
        assertThat(titles("libA", "NIGHT ORCHARD")).containsExactly("The Night Orchard")
        assertThat(titles("libA", "orchard")).containsExactly("The Night Orchard")
    }

    @Test
    fun searchMatchesAuthorCaseInsensitively() = runBlocking<Unit> {
        assertThat(ids("libA", "hopper")).containsExactly("b2")
        assertThat(ids("libA", "ADA")).containsExactly("b1")
    }

    @Test
    fun searchStaysInsideTheSelectedLibrary() = runBlocking<Unit> {
        assertThat(ids("libB", "night")).containsExactly("b4")
        assertThat(ids("libA", "watch")).isEmpty()
    }

    @Test
    fun likeWildcardsAndEscapeCharacterInQueryMatchLiterally() = runBlocking<Unit> {
        // "%" matches only the row whose title contains a literal percent sign.
        assertThat(ids("libA", "%")).containsExactly("b3")
        // "_" matches only rows with a literal underscore in title or author.
        assertThat(ids("libA", "_")).containsExactly("b3")
        assertThat(ids("libA", "100%_Sure")).containsExactly("b3")
        // A backslash in the query matches a literal backslash, not an escape.
        assertThat(ids("libA", "\\")).containsExactly("b5")
        assertThat(ids("libA", "a\\b")).containsExactly("b5")
    }

    @Test
    fun searchWithoutMatchReturnsNoRows() = runBlocking<Unit> {
        val listing = refresher.observeListing(PROFILE, "libA", "zzz").first()

        assertThat(listing.rows).isEmpty()
        assertThat(listing.status).isEqualTo(ListingStatus.READY)
        assertThat(listing.lastRefreshAt).isEqualTo(1_000L)
    }

    @Test
    fun emptyQueryReturnsEveryRowInServerOrder() = runBlocking<Unit> {
        assertThat(ids("libA", "")).containsExactly("b1", "b2", "b3", "b5").inOrder()
    }

    companion object {
        const val PROFILE = "p1"
    }
}
