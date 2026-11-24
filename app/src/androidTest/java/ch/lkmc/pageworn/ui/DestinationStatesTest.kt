package ch.lkmc.pageworn.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.pageworn.MainActivity
import ch.lkmc.pageworn.catalog.CatalogSort
import ch.lkmc.pageworn.debug.DemoLibrary
import ch.lkmc.pageworn.testing.Fixtures
import ch.lkmc.pageworn.testing.TestApp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** M1 destination states from the local demo catalog, including A17 launch and A18 ordering. */
@RunWith(AndroidJUnit4::class)
class DestinationStatesTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        TestApp.reset()
        TestApp.container.settings.setLibrarySort(CatalogSort.DEFAULT)
        runBlocking {
            DemoLibrary.seed(TestApp.context, TestApp.container, object : DemoLibrary.FixtureSource {
                override fun files(fixtureId: String): List<File> = Fixtures.install(fixtureId)
                override fun durationMs(file: File): Long = TestApp.probeDuration(file)
            })
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText("Downloading")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
        TestApp.container.settings.setLibrarySort(CatalogSort.DEFAULT)
        TestApp.reset()
    }

    @Test
    fun seededDestinationsSearchAndPlayerShowLocalStates() {
        bookmark("Downloaded").assertIsSelected()
        composeRule.onNode(hasText("Downloading") and isHeading()).assertIsDisplayed()
        visibleBook("Field Notes on Rain")
        assertShown(hasText("61%"))
        assertShown(hasContentDescription("Pause download of Field Notes on Rain"))
        assertShown(hasContentDescription("Cancel download of Field Notes on Rain"))

        visibleBook("The Night Orchard")
        // The shelf shows covers only, so the local state is what the cover tells TalkBack.
        assertShown(hasStateDescription("New"))
        visibleBook("Boundary Book")
        assertShown(SemanticsMatcher("state description mentions Chapter Two") { node ->
            node.config.getOrNull(SemanticsProperties.StateDescription)?.contains("Chapter Two") == true
        })
        assertShown(hasContentDescription("Resume Boundary Book"))
        assertBefore("The Night Orchard", "Boundary Book")

        bookmark("Library").performClick()
        bookmark("Library").assertIsSelected()
        // Newest first.
        val titles = listOf("The Night Orchard", "The Glass Coast", "Field Notes on Rain", "Small Hours", "Boundary Book")
        waitForBook("The Night Orchard")
        titles.forEach(::visibleBook)
        titles.zipWithNext { first, second ->
            visibleBook(second)
            assertThat(bookTop(first)).isLessThan(bookTop(second))
        }
        visibleBook("Boundary Book")
        assertShown(hasStateDescription("Downloaded") and hasText("Boundary Book"))
        assertShown(hasContentDescription("Resume Boundary Book"))
        visibleBook("Field Notes on Rain")
        assertShown(hasText("61%"))
        visibleBook("Small Hours")
        assertShown(hasContentDescription("Download Small Hours"))
        visibleBook("The Glass Coast")
        assertShown(hasContentDescription("Download The Glass Coast"))
        visibleBook("The Night Orchard")
        assertShown(hasContentDescription("Play The Night Orchard"))

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        val search = composeRule.onNode(hasSetTextAction())
        search.performTextInput("Ward")
        // Every other book must have left, not only one: a row below the screen is absent before
        // the search applies, as Boundary Book is once a status line pushes the list down.
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasContentDescription("Open The Night Orchard")).fetchSemanticsNodes().isNotEmpty() &&
                titles.filterNot { it == "The Night Orchard" }.all { composeRule.onAllNodes(hasContentDescription("Open $it")).fetchSemanticsNodes().isEmpty() }
        }
        visibleBook("The Night Orchard")
        titles.filterNot { it == "The Night Orchard" }.forEach { title ->
            composeRule.onNodeWithContentDescription("Open $title").assertDoesNotExist()
        }
        search.performTextReplacement("zzzz")
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText("No books match your search.")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("No books match your search.").assertIsDisplayed()
        composeRule.onNodeWithText("Clear the search").performClick()
        waitForBook("Boundary Book")
        titles.forEach(::visibleBook)

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasContentDescription("Open player, Boundary Book")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Open player, Boundary Book").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasContentDescription("Collapse player")).fetchSemanticsNodes().isNotEmpty()
        }
        assertShown(hasText("Boundary Book"))
        assertShown(hasText("Chapter Two", substring = true))
        composeRule.onNodeWithContentDescription("Collapse player").performClick()
        bookmark("Library").assertIsSelected()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        assertShown(hasText("Search title or author"))
    }

    @Test
    fun a18bTheSortSheetChangesTheOrderAndItSurvivesARelaunch() {
        bookmark("Library").performClick()
        waitForBook("The Night Orchard")
        val control = composeRule.onNode(hasContentDescription("Sort Library"))
        control.assertHasClickAction().assertHeightIsAtLeast(48.dp)
        composeRule.onNode(hasContentDescription("Sort Library") and hasStateDescription("Date added, newest first")).assertIsDisplayed()
        control.performClick()
        composeRule.onNode(hasText("Date added, newest first") and isSelected()).assertIsDisplayed()
        composeRule.onNode(hasText("Title") and isSelectable()).performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasContentDescription("Sort Library") and hasStateDescription("Title")).fetchSemanticsNodes().isNotEmpty()
        }
        visibleBook("Boundary Book")
        visibleBook("Field Notes on Rain")
        assertThat(bookTop("Boundary Book")).isLessThan(bookTop("Field Notes on Rain"))

        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        // With a download running the app opens on Downloaded.
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(hasText("Downloading")).fetchSemanticsNodes().isNotEmpty() }
        bookmark("Library").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasContentDescription("Sort Library") and hasStateDescription("Title")).fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(TestApp.container.settings.librarySort.value).isEqualTo(CatalogSort.TITLE)
    }

    /** At least one node matches: the player footer repeats a row's chapter and Resume action. */
    private fun assertShown(matcher: SemanticsMatcher) {
        assertWithMessage(matcher.description).that(composeRule.onAllNodes(matcher).fetchSemanticsNodes()).isNotEmpty()
    }

    private fun bookmark(label: String) = composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(label))

    private fun visibleBook(title: String) {
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("Open $title"))
        composeRule.onNodeWithContentDescription("Open $title").assertIsDisplayed()
    }

    private fun waitForBook(title: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasContentDescription("Open $title")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun bookTop(title: String) = composeRule.onNodeWithContentDescription("Open $title")
        .fetchSemanticsNode().boundsInRoot.top

    /** Two books in one row of the shelf share a top within a pixel of rounding. */
    private companion object {
        const val SAME_ROW_PX = 1f
    }

    /**
     * A18's order on the shelf, in reading order: a later row first, then a later column within
     * the row it shares.
     */
    private fun assertBefore(first: String, second: String) {
        val before = composeRule.onNodeWithContentDescription("Open $first").fetchSemanticsNode().boundsInRoot
        val after = composeRule.onNodeWithContentDescription("Open $second").fetchSemanticsNode().boundsInRoot
        val laterRow = after.top > before.top + SAME_ROW_PX
        val laterColumn = abs(after.top - before.top) <= SAME_ROW_PX && after.left > before.left + SAME_ROW_PX
        assertWithMessage("$first before $second (first=$before, after=$after)").that(laterRow || laterColumn).isTrue()
    }
}
