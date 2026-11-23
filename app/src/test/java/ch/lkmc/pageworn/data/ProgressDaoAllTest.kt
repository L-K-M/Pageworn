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

    @After
    fun tearDown() {
        context.deleteDatabase(PagewornDatabase.NAME)
    }

    @Test
    fun allReturnsEverySavedPlaceInOneQuery() = runBlocking<Unit> {
        val db = PagewornDatabase.build(context)
        db.progress().upsert(place("a", 1_000))
        db.progress().upsert(place("b", 2_000))

        assertThat(db.progress().all().map { it.itemId }.toSet()).containsExactly("a", "b")
    }

    private fun place(itemId: String, positionMs: Long) = BookProgressEntity(
        profileId = "profile", itemId = itemId, positionMs = positionMs, durationAtSaveMs = 60_000,
        editionFingerprint = "print-$itemId", finished = false, updatedAt = 7,
        stopKind = StopKind.EXACT, lastListenedAt = null, playbackGeneration = 1, writeSequence = 1,
    )
}
