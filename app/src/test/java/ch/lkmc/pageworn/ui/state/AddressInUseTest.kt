package ch.lkmc.pageworn.ui.state

import ch.lkmc.pageworn.data.db.AddressEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AddressInUseTest {
    private val home = address("home", lastSuccessAt = 200)
    private val away = address("away", lastSuccessAt = 100)

    @Test
    fun a19AutomaticShowsTheLastAddressThatAnswered() {
        assertThat(addressInUse(listOf(home, away), pinnedId = null)).isEqualTo("home")
    }

    @Test
    fun a19APinnedAddressIsTheOnlyOneThatCanBeInUse() {
        assertThat(addressInUse(listOf(home, away), pinnedId = "away")).isEqualTo("away")
    }

    @Test
    fun a19APinnedAddressThatNeverAnsweredLeavesNoneInUse() {
        val unreached = address("away", lastSuccessAt = null)

        assertThat(addressInUse(listOf(home, unreached), pinnedId = "away")).isNull()
    }

    private fun address(id: String, lastSuccessAt: Long?) =
        AddressEntity(id, "profile", "https://$id.invalid/", id, priority = 0, lastSuccessAt = lastSuccessAt)
}
