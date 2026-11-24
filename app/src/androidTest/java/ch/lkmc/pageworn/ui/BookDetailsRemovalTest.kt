package ch.lkmc.pageworn.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.pageworn.MainActivity
import ch.lkmc.pageworn.data.db.CatalogBookEntity
import ch.lkmc.pageworn.data.db.ProfileEntity
import ch.lkmc.pageworn.testing.TestApp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookDetailsRemovalTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val container get() = TestApp.container

    @Before
    fun setUp() {
        TestApp.reset()
        runBlocking {
            container.database.profiles().upsert(
                ProfileEntity(TestApp.PROFILE, "fixture-account", "listener", null, null, "2.38.0", 0),
            )
        }
    }

    @After
    fun tearDown() = TestApp.reset()

    @Test
    fun removingALocalOnlyBookReturnsToDownloaded() {
        TestApp.publishBoundaryBook()
        ActivityScenario.launch(MainActivity::class.java).use {
            removeBoundaryBookFromDetails()
            waitFor(hasText("Downloaded") and isHeading())
        }
    }

    @Test
    fun removingAudioKeepsDetailsOpenWhenTheBookIsStillInTheCatalog() {
        val book = TestApp.publishBoundaryBook()
        runBlocking {
            container.database.catalog().insertAll(listOf(CatalogBookEntity(
                profileId = book.profileId, libraryId = "fixture-library", itemId = book.itemId,
                title = "Boundary Book", authors = null, narrators = null, sortIndex = 0, updatedAt = 0,
                durationMs = null, sizeBytes = null, refreshId = "fixture-refresh",
            )))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            removeBoundaryBookFromDetails()
            waitFor(hasContentDescription("Download Boundary Book"))
        }
    }

    private fun removeBoundaryBookFromDetails() {
        val open = hasContentDescription("Open Boundary Book")
        waitFor(open)
        composeRule.onNode(open).performClick()
        waitFor(hasText("Boundary Book") and isHeading())

        val remove = hasContentDescription("Remove download of Boundary Book")
        val lazyList = hasScrollToNodeAction()
        if (composeRule.onAllNodes(lazyList).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNode(lazyList).performScrollToNode(remove)
        } else {
            composeRule.onNode(remove).performScrollTo()
        }
        composeRule.onNode(remove).performClick()
    }

    private fun waitFor(matcher: SemanticsMatcher) {
        composeRule.waitUntil(10_000) { composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }
}
