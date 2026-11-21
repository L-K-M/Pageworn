package ch.lkmc.pageworn.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.pageworn.MainActivity
import ch.lkmc.pageworn.debug.DemoLibrary
import ch.lkmc.pageworn.testing.Fixtures
import ch.lkmc.pageworn.testing.TestApp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** M1 visual and touch-target check at 200% system text in both system themes. */
@RunWith(AndroidJUnit4::class)
class LargeTextScreensTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        shell("settings put system font_scale 2.0")
        shell("cmd uimode night no")
        TestApp.reset()
        runBlocking {
            DemoLibrary.seed(TestApp.context, TestApp.container, object : DemoLibrary.FixtureSource {
                override fun files(fixtureId: String): List<File> = Fixtures.install(fixtureId)
                override fun durationMs(file: File): Long = TestApp.probeDuration(file)
            })
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForTheme(Configuration.UI_MODE_NIGHT_NO)
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasText("Downloading")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @After
    fun tearDown() {
        try {
            if (::scenario.isInitialized) scenario.close()
            TestApp.reset()
        } finally {
            shell("settings put system font_scale 1.0")
            shell("cmd uimode night no")
        }
    }

    @Test
    fun lightAndDarkDestinationsKeepLargeTextVisibleAndTargetsTouchable() {
        val light = checkAndCapture("Downloaded", listOf("Field Notes on Rain", "The Night Orchard", "Boundary Book"), "m1-downloaded-light-200")
        bookmark("Library").performClick()
        checkAndCapture("Library", listOf("Boundary Book", "Field Notes on Rain", "Small Hours", "The Glass Coast", "The Night Orchard"), "m1-library-light-200")

        shell("cmd uimode night yes")
        waitForTheme(Configuration.UI_MODE_NIGHT_YES)
        composeRule.waitForIdle()
        bookmark("Downloaded").performClick()
        val dark = checkAndCapture("Downloaded", listOf("Field Notes on Rain", "The Night Orchard", "Boundary Book"), "m1-downloaded-dark-200")
        bookmark("Library").performClick()
        checkAndCapture("Library", listOf("Boundary Book", "Field Notes on Rain", "Small Hours", "The Glass Coast", "The Night Orchard"), "m1-library-dark-200")

        assertThat(centreLuminance(dark)).isLessThan(centreLuminance(light))
    }

    private fun checkAndCapture(heading: String, titles: List<String>, name: String): Bitmap {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasContentDescription("Open ${titles.first()}")).fetchSemanticsNodes().isNotEmpty()
        }
        val list = composeRule.onNode(hasScrollToIndexAction())
        titles.forEach { title ->
            list.performScrollToNode(hasContentDescription("Open $title"))
            composeRule.onNode(hasText(title) and hasAnyAncestor(hasScrollToIndexAction())).assertIsDisplayed()
            assertTouchTargets()
        }
        list.performScrollToIndex(0)
        composeRule.onNode(hasText(heading) and isHeading()).assertIsDisplayed()
        bookmark("Library").assertIsDisplayed()
        bookmark("Downloaded").assertIsDisplayed()
        assertTouchTargets()
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(checkNotNull(TestApp.context.getExternalFilesDir(null)), "screens")
        check(directory.isDirectory || directory.mkdirs()) { "Could not create $directory" }
        val output = File(directory, "$name-api${Build.VERSION.SDK_INT}.png")
        output.outputStream().use { stream -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
        return bitmap
    }

    private fun bookmark(label: String) = composeRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(label))

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

    private fun waitForTheme(mode: Int) {
        composeRule.waitUntil(10_000) {
            var actual = 0
            var fontScale = 0f
            scenario.onActivity { activity ->
                actual = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                fontScale = activity.resources.configuration.fontScale
            }
            actual == mode && fontScale >= 1.99f
        }
    }

    private fun shell(command: String) {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private fun centreLuminance(bitmap: Bitmap): Double {
        var total = 0.0
        var count = 0
        for (y in bitmap.height / 3 until bitmap.height * 2 / 3 step 8) {
            for (x in bitmap.width / 4 until bitmap.width * 3 / 4 step 8) {
                val pixel = bitmap.getPixel(x, y)
                total += .2126 * Color.red(pixel) + .7152 * Color.green(pixel) + .0722 * Color.blue(pixel)
                count++
            }
        }
        return total / count
    }
}
