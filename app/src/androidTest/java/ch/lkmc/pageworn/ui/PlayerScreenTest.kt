package ch.lkmc.pageworn.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.pageworn.MainActivity
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.FakeClock
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.core.timeline.RawChapter
import ch.lkmc.pageworn.data.db.ProfileEntity
import ch.lkmc.pageworn.debug.LocalEditions
import ch.lkmc.pageworn.playback.LivePlayback
import ch.lkmc.pageworn.testing.Fixtures
import ch.lkmc.pageworn.testing.TestApp
import ch.lkmc.pageworn.testing.awaitUntil
import ch.lkmc.pageworn.testing.connectController
import ch.lkmc.pageworn.testing.onMain
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.state.PlayerStates
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The full player through its screen, with books published on the device as a download publishes
 * them: the Chapters list with generated segments (A28b), a chapter choice, the skip keys, the
 * scrubber and the speed (M4), the Tracks list and a book without chapters (A24), and Replay from
 * a Downloaded row (A18, A16). How the generated segments' secondary ink looks is part of the
 * visual pass.
 */
@RunWith(AndroidJUnit4::class)
class PlayerScreenTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val context get() = TestApp.context
    private val container get() = TestApp.container
    private val controllers = mutableListOf<MediaController>()

    @Before
    fun setUp() {
        TestApp.reset(FakeClock())
        runBlocking {
            container.database.profiles().upsert(
                ProfileEntity(TestApp.PROFILE, "fixture-account", "listener", null, null, "2.38.0", 0),
            )
        }
    }

    @After
    fun tearDown() {
        onMain { controllers.forEach { it.release() } }
        TestApp.reset()
    }

    /** Loads [key] in the service, paused at [positionMs], so the mini-player offers it. */
    private fun load(key: BookKey, positionMs: Long) = onMain {
        val c = connectController(context).also { controllers += it }
        c.setMediaItem(MediaItem.Builder().setMediaId(key.mediaId).build(), positionMs)
        c.prepare()
        c.awaitUntil(10_000, "ready") { playbackState == Player.STATE_READY }
        c
    }

    private fun awaitNode(matcher: SemanticsMatcher) =
        composeRule.waitUntil(matcher.description, 10_000) {
            composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }

    private fun awaitLive(what: String, condition: (LivePlayback) -> Boolean): LivePlayback = runBlocking {
        try {
            withTimeout(5_000) { container.playbackHub.state.first(condition) }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting for $what: ${container.playbackHub.state.value}", e)
        }
    }

    private fun openPlayer(title: String) {
        awaitNode(hasContentDescription("Open player, $title"))
        composeRule.onNodeWithContentDescription("Open player, $title").performClick()
        awaitNode(hasContentDescription("Collapse player"))
    }

    /** The clickable rows of the open sheet whose titles are among [titles], top to bottom. */
    private fun rowsInOrder(titles: Set<String>): List<String> {
        val matcher = hasClickAction() and SemanticsMatcher("one of $titles") { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text in titles }
        }
        awaitNode(matcher)
        return composeRule.onAllNodes(matcher).fetchSemanticsNodes()
            .sortedBy { it.boundsInRoot.top }
            .map { node -> node.config[SemanticsProperties.Text].first { it.text in titles }.text }
    }

    private fun row(title: String) = composeRule.onNode(hasClickAction() and hasText(title))

    private fun scrubber(name: String) = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and hasContentDescription(name)

    private fun downloadedKey(label: String) = hasContentDescription(label) and hasClickAction() and
        hasAnyAncestor(hasScrollToIndexAction())

    @Test
    fun a28bTheChaptersListAndEveryWayOfMovingThroughTheBook() {
        // A28b: server chapters at 30–75 s and 100–150 s leave three spans that nothing covers.
        val book = TestApp.publishBoundaryBook(chapters = listOf(RawChapter(30_000, 75_000, null), RawChapter(100_000, 150_000, null)))
        val controller = load(book, 80_000)
        val plan = checkNotNull(runBlocking { container.editionStore.plan(book) })
        val segments = PlayerStates.player(plan, runBlocking { container.database.editions().edition(book.profileId, book.itemId) }, null, container.playbackHub.state.value).segments
        // The generated segments are drawn in secondary ink, apart from the server's chapters.
        assertThat(segments.map { it.title to it.generated }).containsExactly(
            "Opening" to true, "Chapter 1" to false, "Interlude" to true, "Chapter 2" to false, "Interlude" to true,
        ).inOrder()

        ActivityScenario.launch(MainActivity::class.java).use {
            openPlayer(BOOK)
            composeRule.onNode(hasContentDescription("Chapters") and hasClickAction()).performClick()
            assertThat(rowsInOrder(setOf("Opening", "Chapter 1", "Interlude", "Chapter 2")))
                .containsExactly("Opening", "Chapter 1", "Interlude", "Chapter 2", "Interlude").inOrder()
            // 80 s is in the Interlude at 75–100 s.
            composeRule.onAllNodes(hasClickAction() and hasText("Interlude"))[0].assertExists()
            val current = composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Current chapter")).fetchSemanticsNodes()
            assertThat(current.map { node -> node.config[SemanticsProperties.Text].first().text }).containsExactly("Interlude")

            // A chapter choice starts playback at that chapter.
            row("Chapter 2").performClick()
            awaitLive("playing Chapter 2") { it.isPlaying && it.positionMs in 100_000..101_000 }
            composeRule.onNodeWithContentDescription("Pause $BOOK").performClick()
            awaitLive("paused") { !it.playWhenReady }
            onMain { controller.seekTo(104_000) }
            awaitLive("at the skip starting point") { it.positionMs == 104_000L }

            // The skip keys.
            composeRule.onNode(hasContentDescription("Forward 30 seconds") and hasClickAction()).performClick()
            awaitLive("forward 30 s") { it.positionMs in 133_900..134_100 }
            composeRule.onNode(hasContentDescription("Back 15 seconds") and hasClickAction()).performClick()
            awaitLive("back 15 s") { it.positionMs in 118_900..119_100 }

            // The scrubber spans the current chapter, 100–150 s: its middle is 125 s.
            composeRule.onNode(scrubber("Chapter position")).performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
            awaitLive("scrubbed to 125 s") { it.positionMs in 124_500..125_500 }

            // Speed: chosen in its sheet, applied to the player and kept for the next start.
            composeRule.onNode(hasContentDescription("Playback speed, 1 times") and hasClickAction()).performClick()
            row("1.5×").performClick()
            awaitLive("1.5x") { it.speed == 1.5f }
            assertThat(container.settings.speed.value).isEqualTo(1.5f)
            awaitNode(hasContentDescription("Playback speed, 1.5 times"))
        }
    }

    @Test
    fun a24AMultiFileBookListsTracksAndASingleFileListsParts() {
        val tracks = TestApp.publishBoundaryBook(itemId = "tracks-book", title = TRACK_BOOK, chapters = emptyList(), fixtureId = "no-chapters-multi")
        load(tracks, 10_000)
        ActivityScenario.launch(MainActivity::class.java).use {
            openPlayer(TRACK_BOOK)
            composeRule.onNode(hasContentDescription("Tracks") and hasClickAction()).assertIsEnabled().performClick()
            assertThat(rowsInOrder(setOf("01-part", "02-part"))).containsExactly("01-part", "02-part").inOrder()
            Espresso.pressBack()
            composeRule.onNode(hasText("Sleep") and hasClickAction()).performClick()
            awaitNode(hasText("End of track"))
            Espresso.pressBack()
        }

        val file = Fixtures.install(SINGLE_FILE).single()
        val single = TestApp.key("single-file")
        runBlocking {
            LocalEditions.publish(container, single, SINGLE_BOOK, "Fixture Author", listOf(LocalEditions.Track(file, TestApp.probeDuration(file), null)))
        }
        onMain { controllers.forEach { it.stop(); it.clearMediaItems() } }
        load(single, 10_000)
        ActivityScenario.launch(MainActivity::class.java).use {
            openPlayer(SINGLE_BOOK)
            // Shorter than ten minutes is one part, and still has both scales.
            composeRule.onNode(scrubber("Part position")).assertIsEnabled()
            composeRule.onNode(scrubber("Book position")).assertIsEnabled()
            composeRule.onNode(hasContentDescription("Parts") and hasClickAction()).assertIsEnabled().performClick()
            assertThat(rowsInOrder(setOf("Part 1"))).containsExactly("Part 1")
            Espresso.pressBack()
            composeRule.onNode(hasText("Sleep") and hasClickAction()).performClick()
            awaitNode(hasText("End of part"))
            Espresso.pressBack()
        }
    }

    @Test
    fun a18ReplayFromTheDownloadedRowStartsAtZero() {
        val book = TestApp.publishBoundaryBook()
        val c = load(book, 200_000)
        onMain {
            c.seekTo(210_000)
            c.awaitUntil(description = "at the end") { currentPosition >= 209_000 }
        }
        runBlocking {
            withTimeout(5_000) {
                container.database.progress().observe(book.profileId, book.itemId).first { it?.finished == true }
            }
        }
        onMain {
            c.stop()
            c.clearMediaItems()
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            // The shelf shows the book's cover, and a finished book's key offers Replay.
            awaitNode(downloadedKey("$REPLAY $BOOK"))
            composeRule.onNode(hasContentDescription("Open $BOOK")).assert(hasStateDescription("Finished"))
            composeRule.onNode(downloadedKey("$REPLAY $BOOK")).performClick()
            val started = awaitLive("replaying") { it.isPlaying }
            assertThat(started.positionMs).isLessThan(1_500L)
            val replayed = checkNotNull(runBlocking {
                withTimeout(5_000) {
                    container.database.progress().observe(book.profileId, book.itemId).first { it?.finished == false }
                }
            })
            assertThat(replayed.finished).isFalse()
            awaitNode(downloadedKey("Pause $BOOK"))
            // Clicks are timed by the test's clock, and within a double tap's time are one tap.
            composeRule.mainClock.advanceTimeBy(NOT_A_DOUBLE_TAP_MS)
            composeRule.onNode(downloadedKey("Pause $BOOK")).performClick()
            awaitLive("paused") { !it.isPlaying }
        }
    }

    @Test
    fun a24ABookWithoutChaptersHasAScaleForThePartAndOneForTheBook() {
        // At rest only the stored duration matters, so a short file can stand for 25 minutes.
        val file = Fixtures.install(SINGLE_FILE).single()
        val book = TestApp.key("parts-book")
        runBlocking {
            LocalEditions.publish(container, book, SINGLE_BOOK, "Fixture Author", listOf(LocalEditions.Track(file, 25 * MINUTE, null)))
            val plan = checkNotNull(container.editionStore.plan(book))
            container.progressStore.saveOutsideSession(book, 12 * MINUTE, plan.timeline.durationMs, plan.fingerprint, StopKind.EXACT)
        }
        container.settings.setLastActiveBook(book)
        fun awaitPlace(what: String, range: LongRange) = runBlocking {
            assertWithMessage(what).that(
                withTimeout(5_000) { container.database.progress().observe(book.profileId, book.itemId).first { it != null && it.positionMs in range } },
            ).isNotNull()
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            openPlayer(SINGLE_BOOK)
            awaitNode(scrubber("Part position") and hasStateDescription("2:00 of 10:00"))
            awaitNode(scrubber("Book position") and hasStateDescription("Book · 12 min of 25 min"))
            assertThat(composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).fetchSemanticsNodes()).hasSize(2)

            // A touch three quarters along the book's scale lands in the part before the last.
            composeRule.onNode(scrubber("Book position")).performTouchInput { click(Offset(width * 0.75f, centerY)) }
            awaitPlace("three quarters of the book", 18 * MINUTE..19 * MINUTE + 30_000)

            // The middle of that part's scale is 15 minutes into the book.
            composeRule.onNode(scrubber("Part position")).performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
            awaitPlace("the middle of the part", 15 * MINUTE - 1_000..15 * MINUTE + 1_000)
            awaitNode(scrubber("Book position") and hasStateDescription("Book · 15 min of 25 min"))

            composeRule.onNode(hasContentDescription("Parts") and hasClickAction()).performClick()
            assertThat(rowsInOrder(setOf("Part 1", "Part 2", "Part 3"))).containsExactly("Part 1", "Part 2", "Part 3").inOrder()
            awaitNode(hasText("Part 2") and hasStateDescription("Current part"))
        }
    }

    private companion object {
        const val MINUTE = 60_000L
        const val BOOK = "Boundary Book"
        const val TRACK_BOOK = "Track Book"
        const val SINGLE_BOOK = "Single File"
        const val SINGLE_FILE = "fmt-m4a-aac"
        const val REPLAY = Copy.REPLAY
        const val NOT_A_DOUBLE_TAP_MS = 500L
    }
}
