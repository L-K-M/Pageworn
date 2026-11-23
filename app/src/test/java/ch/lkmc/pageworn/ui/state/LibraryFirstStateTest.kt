package ch.lkmc.pageworn.ui.state

import androidx.test.core.app.ApplicationProvider
import ch.lkmc.pageworn.PagewornApp
import ch.lkmc.pageworn.catalog.CatalogSort
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryFirstStateTest {
    @Test
    fun theFirstFrameHasTheChosenOrderSoARestoredScrollPlaceHolds() {
        val container = ApplicationProvider.getApplicationContext<PagewornApp>().container
        container.settings.setLibrarySort(CatalogSort.entries.last())

        assertThat(LibraryViewModel(container).state.value.sort).isEqualTo(CatalogSort.entries.last())
    }
}
