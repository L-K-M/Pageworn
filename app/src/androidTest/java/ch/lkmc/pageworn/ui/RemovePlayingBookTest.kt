package ch.lkmc.pageworn.ui

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.pageworn.MainActivity
import ch.lkmc.pageworn.data.db.ProfileEntity
import ch.lkmc.pageworn.testing.TestApp
import ch.lkmc.pageworn.testing.awaitUntil
import ch.lkmc.pageworn.testing.connectController
import ch.lkmc.pageworn.testing.onMain
import ch.lkmc.pageworn.ui.model.Copy
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Rule 5 of docs/03-android-and-data.md: removing a book pauses it and releases its readers first. */
@RunWith(AndroidJUnit4::class)
class RemovePlayingBookTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val container get() = TestApp.container
    private var controller: MediaController? = null

    @Before
    fun setUp() {
        onMain { container.playerConnection.release() }
        TestApp.reset()
        runBlocking {
            container.database.profiles().upsert(
                ProfileEntity(TestApp.PROFILE, "fixture-account", "listener", null, null, "2.38.0", 0),
            )
        }
    }

    @After
    fun tearDown() {
        onMain {
            controller?.release()
            container.playerConnection.release()
        }
        TestApp.reset()
    }

    @Test
    fun removingThePlayingBookStopsItBeforeItsAudioGoes() {
        val book = TestApp.publishBoundaryBook()
        onMain {
            val c = connectController(TestApp.context).also { controller = it }
            c.setMediaItem(MediaItem.Builder().setMediaId(book.mediaId).build(), 20_000)
            c.prepare()
            c.play()
            c.awaitUntil(description = "playing") { isPlaying && currentPosition > 20_500 }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            val open = hasContentDescription("Open Boundary Book")
            composeRule.waitUntil(10_000) { composeRule.onAllNodes(open).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNode(open).performClick()
            val remove = hasContentDescription("Remove download of Boundary Book")
            val detailHeading = hasText("Boundary Book") and isHeading()
            composeRule.waitUntil(10_000) { composeRule.onAllNodes(detailHeading).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(remove)
            composeRule.onNode(remove).performClick()
            runBlocking {
                withTimeout(5_000) { container.playbackHub.state.first { !it.isPlaying && it.key == null } }
                // The checkpoint of the pause is written off the main thread.
                val saved = withTimeout(5_000) {
                    container.database.progress().observe(book.profileId, book.itemId).first { (it?.positionMs ?: 0) >= 20_400 }
                }
                assertThat(checkNotNull(saved).finished).isFalse()
            }
            // This locally published fixture has no catalog entry to show after removal.
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodes(hasText(Copy.NO_DOWNLOADS)).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }
}
