package ch.lkmc.pageworn.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.pageworn.MainActivity
import ch.lkmc.pageworn.debug.DemoLibrary
import ch.lkmc.pageworn.testing.Fixtures
import ch.lkmc.pageworn.testing.TestApp
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Automates the parts of visual checks V01-V05 an emulator can observe.
 *
 * Covered: one section or book heading per state and no app-name line (V01); bookmark Role.Tab and
 * Selected semantics with touch and keyboard switching (V02); transport key labels, system media
 * pause and a separate download pause control (V03); speed, chapter and sleep sheets with their
 * spoken values and exactly one selected radio, night theme utilities and a player screenshot
 * (V04); 200 percent text, high contrast, reduced motion and landscape with reachable targets
 * (V05).
 *
 * Manual: fabric colours and active tail movement, TalkBack focus order and announcements beyond
 * the asserted semantics, notification appearance, pencil icon legibility, animation smoothness
 * and vacant row layout.
 */
@RunWith(AndroidJUnit4::class)
class VisualChecksTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val restoredSettings = mutableListOf<Setting>()
    private var nightModeChanged = false

    private data class Setting(val namespace: String, val key: String, val previous: String)

    @Before
    fun setUp() {
        TestApp.reset()
        runBlocking { seedDemoLibrary() }
    }

    @After
    fun tearDown() {
        try {
            if (::scenario.isInitialized) scenario.close()
            TestApp.reset()
        } finally {
            restoredSettings.forEach(::restore)
            restoredSettings.clear()
            if (nightModeChanged) shell("cmd uimode night no")
        }
    }

    @Test
    fun v01EachStateShowsOneSectionOrBookHeadingAndNoAppName() {
        launchMain()
        openDestination("Library")
        assertSingleHeading("Library")
        assertNoAppName()

        openDestination("Downloaded")
        assertSingleHeading("Downloaded")
        assertNoAppName()

        openFullPlayer()
        assertSingleHeading("Boundary Book")
        assertNoAppName()

        openSleepSheet()
        assertSingleHeading("Sleep timer")
        assertNoAppName()
    }

    @Test
    fun v02BookmarksExposeTabRoleAndSelectionAndSwitchByTouchAndKeyboard() {
        launchMain()
        openDestination("Library")
        assertTabSemantics("Library", selected = true)
        assertTabSemantics("Downloaded", selected = false)

        openDestination("Downloaded")
        assertTabSemantics("Downloaded", selected = true)
        assertTabSemantics("Library", selected = false)

        // A hardware keyboard: Tab moves focus through the screen until it reaches the Library
        // bookmark, and Enter selects it. The first key event also leaves touch mode.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var presses = 0
        while (!isFocused(bookmark("Library"))) {
            check(presses++ < MAX_TAB_PRESSES) { "Tab never reached the Library bookmark" }
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB)
            composeRule.waitForIdle()
        }
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER)
        waitForSelected("Library")
        bookmark("Library").assertIsSelected()
        bookmark("Downloaded").assertIsNotSelected()
        assertTabSemantics("Library", selected = true)
        assertTabSemantics("Downloaded", selected = false)
    }

    private fun isFocused(node: androidx.compose.ui.test.SemanticsNodeInteraction): Boolean =
        node.fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.Focused) { null } == true

    @Test
    fun v03TransportKeysAgreeWithSystemMediaPauseAndTransferControlStaysSeparate() {
        launchMain()
        assertMiniAction("Resume")
        clickMiniPlayPause()
        waitForMiniAction("Pause")

        openFullPlayer()
        waitForPlaybackDescription("Pause Boundary Book")

        shell("cmd media_session dispatch pause")
        waitForPlaybackDescription("Resume Boundary Book")

        composeRule.onNodeWithContentDescription("Collapse player").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasContentDescription("Open player, Boundary Book")).fetchSemanticsNodes().isNotEmpty()
        }
        waitForMiniAction("Resume")

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasContentDescription("Pause download of Field Notes on Rain")).fetchSemanticsNodes().isNotEmpty()
        }
        val transferPause = composeRule.onNodeWithContentDescription("Pause download of Field Notes on Rain").fetchSemanticsNode()
        assertWithMessage("transfer pause role").that(
            transferPause.config.getOrElseNullable(SemanticsProperties.Role) { null }
        ).isEqualTo(Role.Button)
        assertWithMessage("mini playback key is not the transfer pause node").that(miniPlayPauseNode().id)
            .isNotEqualTo(transferPause.id)
    }

    @Test
    fun v04SheetsAnnounceSpeedAndTimerChoiceAndNightUtilitiesStayVisible() {
        launchMain()
        openFullPlayer()

        composeRule.onNodeWithContentDescription("Playback speed, 1 times").performClick()
        waitForSheetText("1.5×")
        composeRule.onNodeWithText("1.5×").performClick()
        waitForSheetClosed()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasContentDescription("Playback speed, 1.5 times")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasContentDescription("Playback speed", substring = true))
            .assertContentDescriptionContains("1.5 times", substring = true)

        composeRule.onNodeWithContentDescription("Playback speed, 1.5 times").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(selectedRadio()).fetchSemanticsNodes().size == 1
        }
        composeRule.onAllNodes(selectedRadio()).assertCountEquals(1)
        dismissSheet()

        composeRule.onNodeWithContentDescription("Chapters").performClick()
        waitForSheetText("Chapter Three")
        composeRule.onNodeWithText("Chapter Three").performClick()
        waitForSheetClosed()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText("Chapter Three")).fetchSemanticsNodes().isNotEmpty()
        }
        assertShown(hasText("Chapter Three"))

        composeRule.onNodeWithContentDescription("Sleep, off").performClick()
        waitForHeading("Sleep timer")
        composeRule.onAllNodes(selectedRadio()).assertCountEquals(1)
        dismissSheet()

        nightModeChanged = true
        shell("cmd uimode night yes")
        waitForNight()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Playback speed, 1.5 times").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Chapters").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Sleep, off").assertIsDisplayed()
        composeRule.onNodeWithText("Speed").assertIsDisplayed()
        composeRule.onNodeWithText("Chapters").assertIsDisplayed()
        composeRule.onNodeWithText("Sleep").assertIsDisplayed()
        capturePlayerNight()
    }

    @Test
    fun v05LargeTextHighContrastReducedMotionLandscapeKeepsControlsReachable() {
        applyV05Environment()
        launchMain()

        bookmark("Library").assertIsDisplayed()
        bookmark("Downloaded").assertIsDisplayed()
        openDestination("Library")
        assertSingleHeading("Library")
        assertNoAppName()
        assertTouchTargets()

        openDestination("Downloaded")
        assertTouchTargets()

        assertMiniAction("Resume")
        clickMiniPlayPause()
        waitForMiniAction("Pause")
        assertMiniAction("Pause")
        clickMiniPlayPause()
        waitForMiniAction("Resume")
        assertMiniAction("Resume")
    }

    private suspend fun seedDemoLibrary() {
        DemoLibrary.seed(TestApp.context, TestApp.container, object : DemoLibrary.FixtureSource {
            override fun files(fixtureId: String): List<File> = Fixtures.install(fixtureId)
            override fun durationMs(file: File): Long = TestApp.probeDuration(file)
        })
    }

    private fun launchMain() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText("Downloading")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun bookmark(label: String) =
        composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(label))

    private fun openDestination(label: String) {
        bookmark(label).performClick()
        waitForSelected(label)
    }

    private fun waitForSelected(label: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.Selected, true) and hasText(label)
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertTabSemantics(label: String, selected: Boolean) {
        val node = bookmark(label).fetchSemanticsNode()
        assertWithMessage("$label role").that(node.config.getOrElseNullable(SemanticsProperties.Role) { null }).isEqualTo(Role.Tab)
        assertWithMessage("$label selected").that(node.config.getOrElseNullable(SemanticsProperties.Selected) { null }).isEqualTo(selected)
    }

    private fun waitForHeading(title: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText(title) and isHeading()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertSingleHeading(title: String) {
        composeRule.onAllNodes(hasText(title) and isHeading()).assertCountEquals(1)
    }

    private fun assertNoAppName() {
        composeRule.onAllNodes(hasText("Pageworn", substring = true)).assertCountEquals(0)
    }

    private fun openFullPlayer() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasContentDescription("Open player, Boundary Book")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Open player, Boundary Book").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasContentDescription("Collapse player")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openSleepSheet() {
        composeRule.onNodeWithContentDescription("Sleep, off").performClick()
        waitForHeading("Sleep timer")
    }

    private fun waitForSheetText(text: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForSheetClosed() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun dismissSheet() {
        shell("input keyevent KEYCODE_BACK")
        waitForSheetClosed()
    }

    private fun selectedRadio(): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and isSelected()

    private fun playbackDescriptions() = listOf("Play Boundary Book", "Pause Boundary Book", "Resume Boundary Book")

    private fun playbackKeyMatcher(): SemanticsMatcher =
        playbackDescriptions().map { hasContentDescription(it) }.reduce { first, second -> first.or(second) }

    /** The mini key sits lowest on screen, just above the bookmarks, below every list row key. */
    private fun miniPlayPauseNode(): SemanticsNode {
        val nodes = composeRule.onAllNodes(playbackKeyMatcher()).fetchSemanticsNodes()
        return checkNotNull(nodes.maxByOrNull { it.boundsInRoot.bottom }) {
            "No Play, Pause or Resume key for Boundary Book is shown"
        }
    }

    private fun clickMiniPlayPause() {
        val matcher = playbackKeyMatcher()
        val nodes = composeRule.onAllNodes(matcher).fetchSemanticsNodes()
        val index = checkNotNull(nodes.indices.maxByOrNull { nodes[it].boundsInRoot.bottom }) {
            "No Play, Pause or Resume key for Boundary Book is shown"
        }
        composeRule.onAllNodes(matcher)[index].performClick()
    }

    private fun miniKeyDescription(): String =
        miniPlayPauseNode().config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.first().orEmpty()

    private fun assertMiniAction(action: String) {
        assertWithMessage("mini player key action").that(miniKeyDescription()).isEqualTo("$action Boundary Book")
    }

    private fun waitForMiniAction(action: String) {
        composeRule.waitUntil(10_000) { miniKeyDescription() == "$action Boundary Book" }
    }

    private fun waitForPlaybackDescription(description: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForNight() {
        composeRule.waitUntil(10_000) {
            var night = false
            scenario.onActivity { activity ->
                night = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            }
            night
        }
    }

    private fun capturePlayerNight() {
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(checkNotNull(TestApp.context.getExternalFilesDir(null)), "screens")
        check(directory.isDirectory || directory.mkdirs()) { "Could not create $directory" }
        val output = File(directory, "v04-player-night-api${Build.VERSION.SDK_INT}.png")
        output.outputStream().use { stream -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
    }

    private fun applyV05Environment() {
        recordSetting("system", "font_scale", "2.0")
        recordSetting("secure", "high_text_contrast_enabled", "1")
        recordSetting("global", "animator_duration_scale", "0")
        recordSetting("global", "transition_animation_scale", "0")
        recordSetting("global", "window_animation_scale", "0")
        recordSetting("system", "accelerometer_rotation", "0")
        recordSetting("system", "user_rotation", "1")
    }

    private fun recordSetting(namespace: String, key: String, value: String) {
        restoredSettings += Setting(namespace, key, shell("settings get $namespace $key").trim())
        shell("settings put $namespace $key $value")
    }

    private fun restore(setting: Setting) {
        if (setting.previous.isBlank() || setting.previous == "null") {
            shell("settings delete ${setting.namespace} ${setting.key}")
        } else {
            shell("settings put ${setting.namespace} ${setting.key} ${setting.previous}")
        }
    }

    private fun assertTouchTargets() {
        val density = TestApp.context.resources.displayMetrics.density
        composeRule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
            // Only targets fully on screen: the list clips rows that are scrolled partly or wholly
            // out of view, and those are measured when a later scroll brings them in.
            val visible = node.boundsInRoot
            if (visible.width < node.size.width - 1 || visible.height < node.size.height - 1) return@forEach
            val bounds = node.touchBoundsInRoot
            val name = node.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.joinToString()
                ?: node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString()
                ?: node.children.firstNotNullOfOrNull { it.config.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString() }
                ?: "node ${node.id}"
            assertWithMessage("touch width of $name").that(bounds.width / density).isAtLeast(48f)
            assertWithMessage("touch height of $name").that(bounds.height / density).isAtLeast(48f)
        }
    }

    private fun assertShown(matcher: SemanticsMatcher) {
        assertWithMessage(matcher.description).that(composeRule.onAllNodes(matcher).fetchSemanticsNodes()).isNotEmpty()
    }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { String(it.readBytes()) }
    }

    private companion object {
        /** Enough Tab presses to visit every control on the Downloaded screen. */
        const val MAX_TAB_PRESSES = 40
    }
}
