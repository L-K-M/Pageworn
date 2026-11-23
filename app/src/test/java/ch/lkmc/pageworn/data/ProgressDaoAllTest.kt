package ch.lkmc.pageworn.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.data.db.BookProgressEntity
import ch.lkmc.pageworn.data.db.PagewornDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgressDaoAllTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: PagewornDatabase

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        context.deleteDatabase(PagewornDatabase.NAME)
    }

    @Test
    fun allReturnsEverySavedPlaceInOneQuery() = runBlocking<Unit> {
        db = PagewornDatabase.build(context)
        db.progress().upsert(place("a", 1_000))
        db.progress().upsert(place("b", 2_000))
        db.progress().upsert(place("c", 3_000, profileId = "other-profile"))

        // The query has no ORDER BY, so compare sets, which ignore row order.
        val rows = db.progress().all().map { it.profileId to it.itemId }.toSet()
        assertThat(rows).isEqualTo(
            setOf(
                "profile" to "a",
                "profile" to "b",
                "other-profile" to "c",
            ),
        )
    }

    private fun place(
        itemId: String,
        positionMs: Long,
        profileId: String = "profile",
    ) = BookProgressEntity(
        profileId = profileId, itemId = itemId, positionMs = positionMs, durationAtSaveMs = 60_000,
        editionFingerprint = "print-$itemId", finished = false, updatedAt = 7,
        stopKind = StopKind.EXACT, lastListenedAt = null, playbackGeneration = 1, writeSequence = 1,
    )
}
