package ch.lkmc.pageworn.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.ui.connect.ConnectScreen
import ch.lkmc.pageworn.ui.library.LibraryScreen
import ch.lkmc.pageworn.ui.model.AddressRow
import ch.lkmc.pageworn.ui.model.AddressesUiState
import ch.lkmc.pageworn.ui.model.BookFace
import ch.lkmc.pageworn.ui.model.CatalogAction
import ch.lkmc.pageworn.ui.model.CatalogRow
import ch.lkmc.pageworn.ui.model.ConnectUiState
import ch.lkmc.pageworn.ui.model.CoverImage
import ch.lkmc.pageworn.ui.model.LibraryUiState
import ch.lkmc.pageworn.ui.model.SettingsUiState
import ch.lkmc.pageworn.ui.model.TransferPhase
import ch.lkmc.pageworn.ui.model.TransferUi
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
            PagewornTheme {
                LibraryScreen(LibraryUiState(query = query.value), rememberLazyListState(), {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, PaddingValues())
            }
        }

        compose.runOnIdle { query.value = "harbour" }

        compose.onNode(hasSetTextAction()).assert(hasContentDescription("Search title or author"))
    }

    @Test
    fun libraryTransferRowsShowAPausedStateAtTheDefaultTextSize() {
        assertStateTextIsShownOnce(TransferPhase.PAUSED, "Paused")
    }

    @Test
    fun libraryTransferRowsShowADownloadingStateAtTheDefaultTextSize() {
        assertStateTextIsShownOnce(TransferPhase.TRANSFERRING, "Downloading")
    }

    @Test
    fun libraryTransferRowsShowAWaitingStateAtTheDefaultTextSize() {
        assertStateTextIsShownOnce(TransferPhase.WAITING, "Waiting for connection")
    }

    @Test
    fun libraryTransferRowsNameTheWaitingRingAtLargeText() {
        assertStateTextIsShownOnce(TransferPhase.WAITING, "Waiting for connection", fontScale = 2f)
    }

    @Test
    fun libraryTransferRowsNameThePausedRingAtLargeText() {
        assertStateTextIsShownOnce(TransferPhase.PAUSED, "Paused", fontScale = 2f)
    }

    /**
     * The default Robolectric font scale renders the non-compact branch, whose compact-only gate
     * this change removed. [onNodeWithText] also fails on two matches, so each phase text is
     * asserted to appear exactly once and be displayed.
     */
    private fun assertStateTextIsShownOnce(phase: TransferPhase, stateText: String, fontScale: Float = 1f) {
        val row = CatalogRow(
            BookFace(BookKey("profile", "item"), "The Night Orchard", "A. Author", CoverImage.Cloth),
            CatalogAction.Transferring(
                TransferUi(
                    jobId = "job", phase = phase, bytesDone = 10, bytesTotal = 100,
                    stateText = stateText, message = null, canPause = phase == TransferPhase.TRANSFERRING,
                    canResume = phase == TransferPhase.PAUSED, canRetry = false, canCancel = true,
                ),
            ),
        )
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                PagewornTheme {
                    LibraryScreen(
                        LibraryUiState(rows = listOf(row)), rememberLazyListState(),
                        {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, PaddingValues(),
                    )
                }
            }
        }

        compose.onAllNodesWithText(stateText).assertCountEquals(1)
        compose.onNodeWithText(stateText).assertIsDisplayed()
        compose.onNode(hasStateDescription("$stateText, 10%")).assertExists()
    }

    @Test
    fun a15ALicenceSaysWhetherItIsOpen() {
        compose.setContent { PagewornTheme { LicencesScreen(listOf("Spectral" to "Licence text"), {}) } }
        val row = compose.onNodeWithText("Spectral")

        row.assert(hasState("Collapsed"))
        row.performClick()

        row.assert(hasState("Expanded"))
    }

    @Test
    fun anAddressSaysWhatItsDateAndItsExceptionMean() {
        val row = AddressRow("id", "Home", "http://home.invalid/", true, false, true, "22.11.26, 14:02", null, null, false, false, false)
        val state = AddressesUiState(listOf(row), automatic = true, pinnedAddressId = null)
        compose.setContent { PagewornTheme { AddressesScreen(state, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }

        compose.onNodeWithText("Unencrypted access allowed").assertExists()
        compose.onNodeWithText("Last reached 22.11.26, 14:02").assertExists()
    }

    @Test
    fun signOutAsksOnce() {
        val state = SettingsUiState("listener", signedIn = true, versionText = "Pageworn", confirmSignOut = true)
        compose.setContent { PagewornTheme { SettingsScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }

        compose.onAllNodesWithText("Sign out?", substring = true).assertCountEquals(1)
    }

    @Test
    fun soundEffectsTogglesFromItsRowAndAnnouncesBothStates() {
        val enabled = mutableStateOf(true)
        val requested = mutableListOf<Boolean>()
        compose.setContent {
            PagewornTheme {
                SettingsScreen(
                    SettingsUiState("listener", signedIn = true, versionText = "Pageworn", uiSoundsEnabled = enabled.value),
                    onBack = {},
                    onOpenAddresses = {},
                    onOpenSleep = {},
                    onSetUiSoundsEnabled = { next -> requested += next; enabled.value = next },
                    onSignOut = {},
                    onConfirmSignOut = {},
                    onDismissSignOut = {},
                    onSignIn = {},
                    onExportDiagnostics = {},
                    onOpenLicences = {},
                )
            }
        }

        compose.onAllNodesWithText("Sound effects").assertCountEquals(1)
        compose.onNodeWithText("Interface").assertExists()
        compose.onNodeWithText("Sound effects").assertIsOn()

        compose.onNodeWithText("Sound effects").performClick()
        compose.runOnIdle {
            assertThat(requested).containsExactly(false)
        }
        compose.onNodeWithText("Sound effects").assertIsOff()

        compose.onNodeWithText("Sound effects").performClick()
        compose.runOnIdle {
            assertThat(requested).containsExactly(false, true)
        }
        compose.onNodeWithText("Sound effects").assertIsOn()
    }

    @Test
    fun connectLeadsBackWhenItWasOpenedFromAnotherScreen() {
        var left = false
        compose.setContent {
            PagewornTheme {
                ConnectScreen(ConnectUiState(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, onOpenDownloaded = null, onBack = { left = true })
            }
        }

        compose.onNodeWithText("Back").performClick()

        assertThat(left).isTrue()
    }

    @Test
    fun v03FieldOutlinesStandOut() {
        lateinit var colors: PagewornColors
        compose.setContent { PagewornTheme { colors = PagewornTheme.colors } }
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
