package ch.lkmc.pageworn.ui

import android.os.Build
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.pageworn.MainActivity
import ch.lkmc.pageworn.server.LocalNetworkAccess
import ch.lkmc.pageworn.testing.TestApp
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.Destination
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Connects through the reference server, then checks the cached Library across sign-out. */
@RunWith(AndroidJUnit4::class)
class ConnectFlowTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var listener: String
    private lateinit var listenerPassword: String
    private lateinit var browser: String

    @Before
    fun setUp() {
        val args = InstrumentationRegistry.getArguments()
        listener = requiredArg(args.getString("absListener"), "absListener")
        listenerPassword = requiredArg(args.getString("absListenerPassword"), "absListenerPassword")
        browser = requiredArg(args.getString("absBrowser"), "absBrowser")
        requiredArg(args.getString("absBrowserPassword"), "absBrowserPassword")
        if (Build.VERSION.SDK_INT >= 37) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(TestApp.context.packageName, LocalNetworkAccess.PERMISSION)
        }
        TestApp.reset()
        // A section remembered from an earlier profile must not hide the catalog after a first sign-in.
        TestApp.container.settings.lastDestination = Destination.DOWNLOADED.name
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
        TestApp.reset()
    }

    @Test
    fun connectBrowseSignOutAndSignInAgain() {
        shown("Connect to server")
        composeRule.onNodeWithText("Server address").performTextReplacement("not a URL")
        click(Copy.CONTINUE)
        shown(Copy.INVALID_ADDRESS)

        composeRule.onNodeWithText("Server address").performTextReplacement("http://10.0.2.2:13378/")
        composeRule.onNodeWithText("Label (optional)").performTextReplacement("Home")
        shown(Copy.CLEARTEXT_TRADEOFF)
        click(Copy.CONTINUE)
        shown(Copy.CLEARTEXT_TRADEOFF)
        composeRule.onNodeWithText("Username").assertDoesNotExist()
        click(Copy.ALLOW_CLEARTEXT)
        // An unreachable host has its own message, and the listener can correct the address.
        composeRule.onNodeWithText("Server address").performTextReplacement(UNREACHABLE_URL)
        click(Copy.CONTINUE)
        shown(Copy.UNREACHABLE)
        composeRule.onNodeWithText("Server address").performTextReplacement("http://10.0.2.2:13378/")
        click(Copy.CONTINUE)
        shown("Username")

        composeRule.onNodeWithText("Username").performTextReplacement(listener)
        composeRule.onNodeWithText("Password").performTextReplacement("wrong-password")
        click(Copy.CONTINUE)
        shown(Copy.WRONG_CREDENTIALS)
        assertThat(runBlocking { TestApp.container.database.profiles().current() }).isNull()
        composeRule.onNodeWithText("Password").performTextReplacement(listenerPassword)
        click(Copy.CONTINUE)
        composeRule.waitUntil(20_000) { composeRule.onAllNodesWithText("Library").fetchSemanticsNodes().size >= 2 }
        shown("Boundary Book")
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Same Title"))
        composeRule.waitUntil(20_000) { composeRule.onAllNodesWithText("Same Title").fetchSemanticsNodes().size >= 2 }
        composeRule.onAllNodesWithText("Same Title").assertCountEquals(2)

        // Nothing is downloaded yet: Downloaded says so and leads back to the catalog.
        composeRule.onAllNodesWithText("Downloaded")[0].performClick()
        shown(Copy.NO_DOWNLOADS)
        click(Copy.BROWSE_LIBRARY)
        shown("Same Title")

        composeRule.onNodeWithContentDescription("Settings").performClick()
        click(Copy.SIGN_OUT)
        shown(Copy.SIGN_OUT_CONFIRM)
        val signOutButtons = composeRule.onAllNodesWithText(Copy.SIGN_OUT)
        signOutButtons[signOutButtons.fetchSemanticsNodes().lastIndex].performClick()
        shown(Copy.SESSION_EXPIRED)
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Boundary Book"))
        shown("Boundary Book")

        click(Copy.SIGN_IN)
        shown("http://10.0.2.2:13378/")
        click(Copy.CONTINUE)
        shown("Username")
        composeRule.onNodeWithText("Username").performTextReplacement(browser)
        composeRule.onNodeWithText("Password").performTextReplacement(requiredArg(InstrumentationRegistry.getArguments().getString("absBrowserPassword"), "absBrowserPassword"))
        click(Copy.CONTINUE)
        shown(Copy.differentAccount(listener))
        composeRule.onNodeWithText("Username").performTextReplacement(listener)
        composeRule.onNodeWithText("Password").performTextReplacement(listenerPassword)
        click(Copy.CONTINUE)
        shown("Boundary Book")
        composeRule.waitUntil(20_000) { composeRule.onAllNodesWithText(Copy.SESSION_EXPIRED).fetchSemanticsNodes().isEmpty() }

        composeRule.onNodeWithContentDescription("Settings").performClick()
        click(Copy.SERVER_ADDRESSES)
        shown("Home")
        shown("http://10.0.2.2:13378/")
    }

    private fun shown(text: String) {
        try {
            composeRule.waitUntil(20_000) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            // What the screen showed instead, for the failure report.
            composeRule.onAllNodes(isRoot()).onFirst().printToLog("ConnectFlowTest")
            throw e
        }
        composeRule.onAllNodesWithText(text)[0].assertIsDisplayed()
    }

    private fun click(text: String) { composeRule.onNodeWithText(text).performClick() }

    private companion object {
        /** The discard port on the host, closed, so the connection is refused at once. */
        const val UNREACHABLE_URL = "http://10.0.2.2:9/"
    }

    private fun requiredArg(value: String?, name: String): String {
        assumeTrue("needs -e $name", value != null)
        return value!!
    }
}
