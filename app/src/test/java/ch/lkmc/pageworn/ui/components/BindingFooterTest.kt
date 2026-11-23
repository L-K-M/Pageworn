package ch.lkmc.pageworn.ui.components

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import ch.lkmc.pageworn.ui.model.Destination
import ch.lkmc.pageworn.ui.theme.PagewornTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BindingFooterTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun bookmarksLeaveClothBelowThemWithoutNavigationInsets() {
        compose.setContent {
            PagewornTheme {
                MainScaffold(Destination.DOWNLOADED, {}, null, {}, {}) {}
            }
        }

        val bottom = compose.onRoot().getUnclippedBoundsInRoot().bottom
        val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        for (label in listOf("Library", "Downloaded")) {
            val tabBottom = compose.onNode(isTab and hasText(label)).getUnclippedBoundsInRoot().bottom
            assertThat((bottom - tabBottom).value).isAtLeast(12f)
        }
    }
}
