package ch.lkmc.pageworn.ui

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.pageworn.MainActivity
import ch.lkmc.pageworn.data.db.AddressEntity
import ch.lkmc.pageworn.data.db.ProfileEntity
import ch.lkmc.pageworn.server.LocalNetworkAccess
import ch.lkmc.pageworn.server.TokenPair
import ch.lkmc.pageworn.testing.TestApp
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.Destination
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A17, nothing local: with no downloaded book and no cached catalog, a launch whose server
 * cannot be reached opens connection setup in the unreachable state with Retry. It does so once:
 * after the listener leaves for Downloaded, a later failed refresh leaves them where they are.
 */
@RunWith(AndroidJUnit4::class)
class LaunchBlockedTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        // 10.0.2.2 is local to the emulator; with the permission granted only the closed port fails.
        if (Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(TestApp.context.packageName, LocalNetworkAccess.PERMISSION)
        }
        TestApp.reset()
        val container = TestApp.container
        runBlocking {
            container.database.profiles().upsert(
                ProfileEntity(PROFILE, "account-1", "listener", pairingLibraryId = null, chosenLibraryId = null, serverVersion = null, createdAt = 0),
            )
            container.database.addresses().upsert(
                AddressEntity(ADDRESS, PROFILE, UNREACHABLE_URL, "Home", priority = 0, credentialRef = ADDRESS, cleartextOptIn = true),
            )
        }
        container.tokenVault.write(ADDRESS, TokenPair("access", "refresh"))
        container.settings.lastDestination = Destination.LIBRARY.name
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
        TestApp.reset()
    }

    @Test
    fun launchWithNothingLocalOpensConnectionSetupOnce() {
        shown(Copy.UNREACHABLE)
        shown(UNREACHABLE_URL)
        composeRule.onNodeWithText(Copy.RETRY).performClick()
        shown(Copy.UNREACHABLE)
        shown(UNREACHABLE_URL)

        composeRule.onNodeWithText(OPEN_DOWNLOADED).performClick()
        shown(Copy.NO_DOWNLOADS)

        composeRule.onAllNodesWithText("Library")[0].performClick()
        shown(Copy.RETRY)
        composeRule.onNodeWithText(Copy.RETRY).performClick()
        shown(Copy.UNREACHABLE)
        // Give a refreshed notice time to reopen connection setup, which it must not do.
        Thread.sleep(3_000)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Server address").assertDoesNotExist()
    }

    private fun shown(text: String) {
        composeRule.waitUntil(20_000) { composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodesWithText(text, substring = true)[0].assertIsDisplayed()
    }

    private companion object {
        const val PROFILE = "profile-1"
        const val ADDRESS = "address-1"
        /** The discard port on the host, closed, so every connection is refused at once. */
        const val UNREACHABLE_URL = "http://10.0.2.2:9/"
        const val OPEN_DOWNLOADED = "Open Downloaded"
    }
}
