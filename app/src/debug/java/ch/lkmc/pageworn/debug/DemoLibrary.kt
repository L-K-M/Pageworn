package ch.lkmc.pageworn.debug

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.DrawableRes
import ch.lkmc.pageworn.AppContainer
import ch.lkmc.pageworn.R
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.core.timeline.RawChapter
import ch.lkmc.pageworn.data.db.CatalogBookEntity
import ch.lkmc.pageworn.data.db.LibraryEntity
import ch.lkmc.pageworn.data.db.ProfileEntity
import ch.lkmc.pageworn.data.db.TransferEntity
import ch.lkmc.pageworn.data.db.TransferState
import java.io.File
import java.util.UUID

/**
 * Debug and test only: a profile with one library whose books show each local state from the
 * generated fixtures (M1). Two books are ready, one is transferring and the rest are available.
 * Nothing here talks to a server; the catalog is the synthetic one a refresh would publish.
 */
object DemoLibrary {
    private const val DAY_MS = 86_400_000L
    const val PROFILE = "00000000-0000-4000-8000-0000000000aa"
    const val LIBRARY = "demo-audiobooks"
    private const val REFRESH = "demo-refresh"

    val boundaryBook = BookKey(PROFILE, "boundary-book")
    val nightOrchard = BookKey(PROFILE, "night-orchard")
    val fieldNotes = BookKey(PROFILE, "field-notes-on-rain")
    val smallHours = BookKey(PROFILE, "small-hours")
    val glassCoast = BookKey(PROFILE, "glass-coast")

    /** The fixture files of one fixture ID in manifest order, and the probed duration of a file. */
    interface FixtureSource {
        fun files(fixtureId: String): List<File>
        fun durationMs(file: File): Long
    }

    private data class Book(val key: BookKey, val title: String, val authors: String, val durationMs: Long, val sizeBytes: Long)

    private val catalog = listOf(
        Book(boundaryBook, "Boundary Book", "Fixture Author", 210_000, 1_726_330),
        Book(fieldNotes, "Field Notes on Rain", "Ada Bell", 210_000, 1_726_330),
        Book(glassCoast, "The Glass Coast", "Mara Ellis", 9_480_000, 152_000_000),
        Book(nightOrchard, "The Night Orchard", "Elin Ward", 300_000, 2_492_735),
        Book(smallHours, "Small Hours", "Kit Mercer", 150_000, 1_800_901),
    )

    /** Newest first, which is the gallery's order. */
    private val addedOrder get() = listOf(nightOrchard, glassCoast, fieldNotes, smallHours, boundaryBook)

    suspend fun seed(context: Context, container: AppContainer, fixtures: FixtureSource, now: Long = System.currentTimeMillis()) {
        val db = container.database
        db.profiles().upsert(
            ProfileEntity(PROFILE, accountId = "demo-account", accountName = "listener", pairingLibraryId = LIBRARY, chosenLibraryId = LIBRARY, serverVersion = "2.38.0", createdAt = now),
        )
        db.libraries().upsertAll(listOf(LibraryEntity(PROFILE, LIBRARY, "Audiobooks", 0, lastRefreshAt = now, publishedRefreshId = REFRESH)))
        db.catalog().clearLibrary(PROFILE, LIBRARY)
        db.catalog().insertAll(
            catalog.sortedBy { it.title }.mapIndexed { i, b ->
                CatalogBookEntity(PROFILE, LIBRARY, b.key.itemId, b.title, b.authors, null, i, now, b.durationMs, b.sizeBytes, REFRESH, addedAt = now - addedOrder.indexOf(b.key) * DAY_MS)
            },
        )

        // Ready: the boundary book with its server chapters and a saved place, and the M4B.
        val boundary = fixtures.files("boundary-book")
        LocalEditions.publish(
            container, boundaryBook, "Boundary Book", "Fixture Author",
            boundary.map { LocalEditions.Track(it, fixtures.durationMs(it), it.nameWithoutExtension) },
            serverChapters = listOf(RawChapter(0, 75_000, "Chapter One"), RawChapter(75_000, 140_000, "Chapter Two"), RawChapter(140_000, 210_000, "Chapter Three")),
        )
        val orchard = fixtures.files("single-m4b").single()
        LocalEditions.publish(
            container, nightOrchard, "The Night Orchard", "Elin Ward",
            listOf(LocalEditions.Track(orchard, fixtures.durationMs(orchard))),
            embeddedChapters = listOf(listOf(RawChapter(0, 100_000, "The Orchard Gate"), RawChapter(100_000, 200_000, "Under the Pear Tree"), RawChapter(200_000, 300_000, "Moonrise"))),
            cover = coverFile(context, R.drawable.pw_cover_night_orchard, "orchard"),
            narrators = "Jon Hale",
        )
        val plan = container.editionStore.anyPlan(boundaryBook)
        container.progressStore.saveOutsideSession(boundaryBook, 115_000, plan?.timeline?.durationMs ?: 210_000, plan?.fingerprint, StopKind.ORDINARY)
        container.settings.setLastActiveBook(boundaryBook)

        // Transferring: a job part way through, as the transfer worker would leave it.
        db.transfers().upsert(
            TransferEntity(
                jobId = "demo-transfer", profileId = PROFILE, itemId = fieldNotes.itemId, stagingGenerationId = UUID.randomUUID().toString(),
                manifestFingerprint = null, state = TransferState.TRANSFERRING, activeMarker = 1, createdAt = now, updatedAt = now,
                bytesTotal = 1_726_330, bytesDone = 1_070_324, title = "Field Notes on Rain", authors = "Ada Bell",
            ),
        )
    }

    @SuppressLint("ResourceType") // The fixture drawables are PNG files copied byte for byte.
    private fun coverFile(context: Context, @DrawableRes res: Int, name: String): File {
        val out = File(context.cacheDir, "demo-covers/$name.png")
        out.parentFile?.mkdirs()
        context.resources.openRawResource(res).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }
}
