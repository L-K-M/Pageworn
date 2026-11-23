package ch.lkmc.pageworn.ui.state

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.pageworn.PagewornApp
import ch.lkmc.pageworn.data.db.CatalogStagingEntity
import ch.lkmc.pageworn.data.db.LibraryEntity
import ch.lkmc.pageworn.data.db.ProfileEntity
import ch.lkmc.pageworn.data.db.RefreshAttemptEntity
import ch.lkmc.pageworn.data.db.RefreshState
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.UiAction
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A01c: a failed first load keeps its staged rows and shows the incomplete status line. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryIncompleteStatusTest {
    private val screen = CoroutineScope(Job() + Dispatchers.Main)
    private lateinit var library: LibraryViewModel

    @Before
    fun setUp() {
        val container = ApplicationProvider.getApplicationContext<PagewornApp>().container
        runBlocking {
            val db = container.database
            db.profiles().upsert(
                ProfileEntity(PROFILE, "account", "Listener", SHELF, SHELF, "2.38.0", 1),
            )
            db.libraries().upsertAll(listOf(LibraryEntity(PROFILE, SHELF, "Shelf", 0)))
            db.catalog().insertAttempt(
                RefreshAttemptEntity(
                    refreshId = "attempt", profileId = PROFILE, libraryId = SHELF, sequence = 1,
                    startedAt = 1, state = RefreshState.FAILED, firstLoad = true,
                ),
            )
            db.catalog().stage(
                listOf(
                    CatalogStagingEntity(
                        refreshId = "attempt", itemId = "orchard", title = "The Night Orchard",
                        authors = "Elin Ward", narrators = null, sortIndex = 0, updatedAt = 1,
                        durationMs = 210_000, sizeBytes = 100, playable = true, addedAt = 1,
                    ),
                ),
            )
        }
        library = LibraryViewModel(container)
        screen.launch { library.state.collect {} }
    }

    @After
    fun tearDown() {
        screen.cancel()
    }

    @Test
    fun aFailedFirstLoadShowsTheStatusLineAboveItsRows() {
        val state = await("the incomplete status") {
            it.selectedLibraryId == SHELF && it.status?.text == Copy.LIBRARY_INCOMPLETE
        }

        assertThat(state.status?.action).isEqualTo(UiAction.RETRY)
        assertThat(state.empty).isNull()
        assertThat(state.rows.map { it.face.title }).containsExactly("The Night Orchard")
    }

    private fun await(what: String, condition: (ch.lkmc.pageworn.ui.model.LibraryUiState) -> Boolean): ch.lkmc.pageworn.ui.model.LibraryUiState {
        repeat(WAIT_STEPS) {
            shadowOf(Looper.getMainLooper()).idle()
            library.state.value.let { if (condition(it)) return it }
            Thread.sleep(WAIT_STEP_MS)
        }
        assertWithMessage("$what never showed: ${library.state.value}").fail()
        error("unreachable")
    }

    private companion object {
        const val PROFILE = "profile"
        const val SHELF = "shelf"
        const val WAIT_STEPS = 500
        const val WAIT_STEP_MS = 10L
    }
}
