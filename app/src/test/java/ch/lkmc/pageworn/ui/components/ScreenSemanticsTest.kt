package ch.lkmc.pageworn.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import ch.lkmc.pageworn.ui.connect.ConnectScreen
import ch.lkmc.pageworn.ui.library.LibraryScreen
import ch.lkmc.pageworn.ui.model.AddressRow
import ch.lkmc.pageworn.ui.model.AddressesUiState
import ch.lkmc.pageworn.ui.model.ConnectUiState
import ch.lkmc.pageworn.ui.model.LibraryUiState
import ch.lkmc.pageworn.ui.model.SettingsUiState
import ch.lkmc.pageworn.ui.settings.AddressesScreen
import ch.lkmc.pageworn.ui.settings.LicencesScreen
import ch.lkmc.pageworn.ui.settings.SettingsScreen
import ch.lkmc.pageworn.ui.theme.PagewornColors
import ch.lkmc.pageworn.ui.theme.PagewornTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the redesigned screens tell TalkBack, and how far their outlines stand out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun a15SearchKeepsItsNameOnceItHoldsAQuery() {
        val query = mutableStateOf("")
        compose.setContent {
            PagewornTheme(darkTheme = false) {
                LibraryScreen(LibraryUiState(query = query.value), rememberLazyListState(), {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, PaddingValues())
            }
        }

        compose.runOnIdle { query.value = "harbour" }

        compose.onNode(hasSetTextAction()).assert(hasContentDescription("Search title or author"))
    }

    @Test
    fun a15ALicenceSaysWhetherItIsOpen() {
        compose.setContent { PagewornTheme(darkTheme = false) { LicencesScreen(listOf("Spectral" to "Licence text"), {}) } }
        val row = compose.onNodeWithText("Spectral")

        row.assert(hasState("Collapsed"))
        row.performClick()

        row.assert(hasState("Expanded"))
    }

    @Test
    fun anAddressSaysWhatItsDateAndItsExceptionMean() {
        val row = AddressRow("id", "Home", "http://home.invalid/", true, false, true, "22.11.26, 14:02", null, null, false, false, false)
        val state = AddressesUiState(listOf(row), automatic = true, pinnedAddressId = null)
        compose.setContent { PagewornTheme(darkTheme = false) { AddressesScreen(state, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }

        compose.onNodeWithText("Unencrypted access allowed").assertExists()
        compose.onNodeWithText("Last reached 22.11.26, 14:02").assertExists()
    }

    @Test
    fun signOutAsksOnce() {
        val state = SettingsUiState("listener", signedIn = true, versionText = "Pageworn", confirmSignOut = true)
        compose.setContent { PagewornTheme(darkTheme = false) { SettingsScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }

        compose.onAllNodesWithText("Sign out?", substring = true).assertCountEquals(1)
    }

    @Test
    fun connectLeadsBackWhenItWasOpenedFromAnotherScreen() {
        var left = false
        compose.setContent {
            PagewornTheme(darkTheme = false) {
                ConnectScreen(ConnectUiState(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, onOpenDownloaded = null, onBack = { left = true })
            }
        }

        compose.onNodeWithText("Back").performClick()

        assertThat(left).isTrue()
    }

    @Test
    fun v03FieldOutlinesStandOutByDay() = assertFieldOutlineStandsOut(darkTheme = false)

    @Test
    fun v03FieldOutlinesStandOutByNight() = assertFieldOutlineStandsOut(darkTheme = true)

    private fun assertFieldOutlineStandsOut(darkTheme: Boolean) {
        lateinit var colors: PagewornColors
        compose.setContent { PagewornTheme(darkTheme) { colors = PagewornTheme.colors } }
        compose.waitForIdle()

        listOf(colors.paper, colors.raisedSheet).forEach { ground ->
            val edge = colors.controlEdge(highContrast = false).compositeOver(ground)
            assertThat(contrast(edge, colors.paper)).isAtLeast(MIN_OUTLINE_CONTRAST)
            assertThat(contrast(edge, ground)).isAtLeast(MIN_OUTLINE_CONTRAST)
        }
    }

    private fun contrast(first: Color, second: Color): Float {
        val (dark, light) = listOf(first.luminance(), second.luminance()).sorted()
        return (light + WCAG_FLARE) / (dark + WCAG_FLARE)
    }

    private fun hasState(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    private companion object {
        const val MIN_OUTLINE_CONTRAST = 3f
        const val WCAG_FLARE = 0.05f
    }
}
