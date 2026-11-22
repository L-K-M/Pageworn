package ch.lkmc.pageworn.ui

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.pageworn.MainActivity
import ch.lkmc.pageworn.PagewornApp
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.data.EditionManifest
import ch.lkmc.pageworn.data.ManifestTrack
import ch.lkmc.pageworn.data.db.ProfileEntity
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The links of the notification and the widgets lead there again after the listener left. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class OpenPlayerIntentTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val key = BookKey("profile", "item")
    private lateinit var app: PagewornApp

    @Before
    fun setUp() = runBlocking<Unit> {
        app = ApplicationProvider.getApplicationContext()
        val container = app.container
        container.database.profiles().upsert(ProfileEntity(key.profileId, "account", "Listener", null, null, "2.38.0", 1))
        container.editionFiles.ensureRoots()
        val staged = container.editionFiles.staging("generation").also { it.mkdirs() }
        File(staged, "000.mp3").writeBytes(byteArrayOf(1))
        container.editionStore.publish(
            EditionManifest(
                generationId = "generation", jobId = null, profileId = key.profileId, itemId = key.itemId, fingerprint = "print", title = "Book",
                tracks = listOf(ManifestTrack(0, "file", 1, 1, "000.mp3", 0, 90_000, extension = "mp3")), chapters = emptyList(),
            ),
        )
        container.settings.setLastActiveBook(key)
    }

    @Test
    fun aSecondOpenPlayerIntentOpensItAgain() {
        val intent = Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
        val activity = Robolectric.buildActivity(MainActivity::class.java, Intent(intent)).setup()
        awaitPlayer()

        compose.onNodeWithContentDescription(COLLAPSE).performClick()
        compose.waitUntil(WAIT_MS) { !playerShown() }
        activity.newIntent(Intent(intent))

        awaitPlayer()
    }

    @Test
    fun aBookLinkOpensItsDetailsEachTime() {
        val intent = MainActivity.openBookIntent(app, key)
        val activity = Robolectric.buildActivity(MainActivity::class.java, Intent(intent)).setup()
        awaitDetails()

        compose.onNodeWithText(BACK).performClick()
        compose.waitUntil(WAIT_MS) { !detailsShown() }
        activity.newIntent(Intent(intent))

        awaitDetails()
    }

    @Test
    fun aLinkToABookThatIsNotHereShowsDownloaded() {
        val intent = MainActivity.openBookIntent(app, BookKey(key.profileId, "removed"))
        Robolectric.buildActivity(MainActivity::class.java, intent).setup()

        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText("Book").fetchSemanticsNodes().isNotEmpty() }
        assertThat(detailsShown()).isFalse()
    }

    private fun awaitDetails() = compose.waitUntil(WAIT_MS) { detailsShown() }

    private fun detailsShown() = compose.onAllNodesWithText(BACK).fetchSemanticsNodes().isNotEmpty()

    private fun awaitPlayer() = compose.waitUntil(WAIT_MS) { playerShown() }

    private fun playerShown() = compose.onAllNodesWithContentDescription(COLLAPSE).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val BACK = "Back"
        const val COLLAPSE = "Collapse player"
        const val WAIT_MS = 5_000L
    }
}
