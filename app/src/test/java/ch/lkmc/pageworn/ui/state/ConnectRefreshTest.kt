package ch.lkmc.pageworn.ui.state

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConnectRefreshTest {
    @Test
    fun signingInAgainRefreshesTheCatalogSoTheCacheCannotStayStale() {
        assertThat(refreshesCatalog(ConnectMode.SIGN_IN)).isTrue()
        assertThat(refreshesCatalog(ConnectMode.FIRST)).isTrue()
        assertThat(refreshesCatalog(ConnectMode.LAUNCH_BLOCKED)).isTrue()
    }

    @Test
    fun addingAnotherAddressKeepsTheExistingCatalog() {
        assertThat(refreshesCatalog(ConnectMode.ADD)).isFalse()
    }
}
