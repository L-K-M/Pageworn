package ch.lkmc.pageworn.ui

import android.os.Build
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.pageworn.MainActivity
import ch.lkmc.pageworn.data.db.AddressEntity
import ch.lkmc.pageworn.debug.DemoLibrary
import ch.lkmc.pageworn.server.LocalNetworkAccess
import ch.lkmc.pageworn.server.TokenPair
import ch.lkmc.pageworn.testing.Fixtures
import ch.lkmc.pageworn.testing.TestApp
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.Destination
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * A17, with downloaded books: while the server cannot be reached, Library shows its cached rows
 * under the unreachable state, and a Download tap on a remote book starts no transfer and
 * repeats that state with Retry.
 */
@RunWith(AndroidJUnit4::class)
class DownloadTapUnreachableTest {
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
            DemoLibrary.seed(TestApp.context, container, object : DemoLibrary.FixtureSource {
                override fun files(fixtureId: String): List<File> = Fixtures.install(fixtureId)
                override fun durationMs(file: File): Long = TestApp.probeDuration(file)
            })
            container.database.addresses().upsert(
                AddressEntity(ADDRESS, DemoLibrary.PROFILE, UNREACHABLE_URL, "Home", priority = 0, credentialRef = ADDRESS, cleartextOptIn = true),
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
    fun downloadTapStartsNothingAndRepeatsTheUnreachableState() {
        // With books downloaded, the cold launch opens Downloaded.
        waitFor(LIBRARY)
        composeRule.onAllNodesWithText(LIBRARY)[0].performClick()
        waitFor(Copy.UNREACHABLE)
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Download Small Hours"))
        composeRule.onNode(hasContentDescription("Download Small Hours")).performClick()
        // The tap reads a manifest first; give it time to fail and report.
        Thread.sleep(TAP_SETTLE_MS)
        waitFor(Copy.UNREACHABLE)
        composeRule.onAllNodesWithText(Copy.RETRY)[0].assertExists()
        val job = runBlocking { TestApp.container.database.transfers().active(DemoLibrary.smallHours.profileId, DemoLibrary.smallHours.itemId) }
        assertThat(job).isNull()
    }

    private fun waitFor(text: String) {
        composeRule.waitUntil(20_000) { composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val ADDRESS = "address-home"
        const val UNREACHABLE_URL = "http://10.0.2.2:9/"
        const val TAP_SETTLE_MS = 3_000L
        const val LIBRARY = "Library"
    }
}
