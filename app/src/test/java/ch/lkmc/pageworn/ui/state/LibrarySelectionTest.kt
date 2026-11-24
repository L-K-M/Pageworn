package ch.lkmc.pageworn.ui.state

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.pageworn.AppContainer
import ch.lkmc.pageworn.PagewornApp
import ch.lkmc.pageworn.data.db.CatalogBookEntity
import ch.lkmc.pageworn.data.db.LibraryEntity
import ch.lkmc.pageworn.data.db.ProfileEntity
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.LibraryUiState
import ch.lkmc.pageworn.ui.model.UiAction
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A17b and A25: several libraries, one of them never loaded, with no server to ask. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibrarySelectionTest {
    private val screen = CoroutineScope(Job() + Dispatchers.Main)
    private lateinit var container: AppContainer
    private lateinit var library: LibraryViewModel

    @Before
    fun setUp() {
        container = ApplicationProvider.getApplicationContext<PagewornApp>().container
        runBlocking {
            val db = container.database
            db.profiles().upsert(ProfileEntity(PROFILE, "account", "Listener", LOADED, LOADED, "2.38.0", 1))
            db.libraries().upsertAll(listOf(
                LibraryEntity(PROFILE, LOADED, "Loaded", 0, lastRefreshAt = 1, publishedRefreshId = "refresh"),
                LibraryEntity(PROFILE, NEVER_LOADED, "Never loaded", 1),
            ))
            db.catalog().insertAll(listOf(
                CatalogBookEntity(PROFILE, LOADED, "orchard", "The Night Orchard", "Elin Ward", null, 0, 1, null, null, "refresh"),
                CatalogBookEntity(PROFILE, LOADED, "rain", "Field Notes on Rain", "Ada Pell", null, 1, 1, null, null, "refresh"),
            ))
        }
        library = LibraryViewModel(container)
        screen.launch { library.state.collect {} }
    }

    @After
    fun tearDown() {
        screen.cancel()
    }

    @Test
    fun a25SearchStaysInsideTheSelectedLibrary() {
        await("both books") { it.rows.size == 2 && it.libraries.size == 2 }

        library.setQuery("pell")
        assertThat(await("the author's book") { it.rows.size == 1 }.rows.single().face.key.itemId).isEqualTo("rain")

        library.setQuery("nowhere")
        assertThat(await("no match") { it.empty != null }.empty?.text).isEqualTo(Copy.NO_SEARCH_RESULTS)
    }

    @Test
    fun a17bANeverLoadedLibrarySaysTheServerIsUnreachable() {
        await("both books") { it.rows.size == 2 }

        library.selectLibrary(NEVER_LOADED)

        val state = await("the unreachable state") { it.selectedLibraryId == NEVER_LOADED && it.empty?.text == Copy.UNREACHABLE }
        assertThat(state.empty?.action).isEqualTo(UiAction.RETRY)
        assertThat(state.showSelectorAndSearch).isTrue()
        assertThat(state.libraries).hasSize(2)
    }

    @Test
    fun a17bANeverLoadedLibrarySaysTheSessionExpired() {
        await("both books") { it.rows.size == 2 }
        runBlocking {
            container.accounts.chooseLibrary(NEVER_LOADED)
            container.accounts.signOut()
        }

        val state = await("the signed-out state") { it.selectedLibraryId == NEVER_LOADED && it.empty?.text == Copy.SESSION_EXPIRED }
        assertThat(state.empty?.action).isEqualTo(UiAction.SIGN_IN)
        assertThat(state.showSelectorAndSearch).isTrue()
    }

    private fun await(what: String, condition: (LibraryUiState) -> Boolean): LibraryUiState {
        repeat(WAIT_STEPS) {
            shadowOf(Looper.getMainLooper()).idle()
            library.state.value.let { if (condition(it)) return it }
            Thread.sleep(WAIT_STEP_MS)
            // The search query settles through a debounce on the main looper clock, which does
            // not advance with wall-clock sleeps alone.
            shadowOf(Looper.getMainLooper()).idleFor(WAIT_STEP_MS, TimeUnit.MILLISECONDS)
        }
        assertWithMessage("$what never showed: ${library.state.value}").fail()
        error("unreachable")
    }

    private companion object {
        const val PROFILE = "profile"
        const val LOADED = "loaded"
        const val NEVER_LOADED = "never-loaded"
        const val WAIT_STEPS = 500
        const val WAIT_STEP_MS = 10L
    }
}
