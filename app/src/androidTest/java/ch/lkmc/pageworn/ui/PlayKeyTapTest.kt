package ch.lkmc.pageworn.ui

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.pageworn.MainActivity
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.FakeClock
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.data.db.ProfileEntity
import ch.lkmc.pageworn.playback.LivePlayback
import ch.lkmc.pageworn.playback.SleepState
import ch.lkmc.pageworn.testing.FaultyFiles
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
import java.util.concurrent.CountDownLatch

/**
 * A16g: a tap on a Play key always does what the key shows, at once and visibly. The touches are
 * injected into the window like a finger's. Semantics clicks skip hit testing and wait for screen
 * transitions to finish, which is where taps were lost.
 */
@RunWith(AndroidJUnit4::class)
class PlayKeyTapTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val context get() = TestApp.context
    private val container get() = TestApp.container
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val controllers = mutableListOf<MediaController>()

    @Before
    fun setUp() {
        onMain { container.playerConnection.release() }
        TestApp.reset(FakeClock())
        runBlocking {
            container.database.profiles().upsert(
                ProfileEntity(TestApp.PROFILE, "fixture-account", "listener", null, null, "2.38.0", 0),
            )
        }
    }

    @After
    fun tearDown() {
        onMain {
            controllers.forEach { it.release() }
            container.playerConnection.release()
        }
        TestApp.reset()
    }

    @Test
    fun aKeyShowsPauseWhileTheBookItStartedWaitsForAudio() {
        val loaderHeld = CountDownLatch(1)
        val book = TestApp.publishBoundaryBook()
        val first = runBlocking { TestApp.publishedTrack(book, 0) }.first.name
        val files = FaultyFiles().apply { faults = listOf(FaultyFiles.FailReads(first, 0, times = 0, heldUntil = loaderHeld)) }
        container.playbackDataSourceFactory = files
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                tap(centre(rowKey("Play", BOOK)))
                awaitNode(rowKey("Pause", BOOK), timeoutMs = 2_000)
                loaderHeld.countDown()
                awaitLive("playing after one tap") { it.isPlaying }
            }
        } finally {
            loaderHeld.countDown()
        }
    }

    @Test
    fun aDoubleTapOnPlayIsOnePlay() {
        val loaderHeld = CountDownLatch(1)
        val book = TestApp.publishBoundaryBook()
        val first = runBlocking { TestApp.publishedTrack(book, 0) }.first.name
        container.playbackDataSourceFactory = FaultyFiles().apply {
            faults = listOf(FaultyFiles.FailReads(first, 0, times = 0, heldUntil = loaderHeld))
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val key = centre(rowKey("Play", BOOK))
                val lifted = tap(key)
                awaitNode(rowKey("Pause", BOOK), timeoutMs = 2_000)
                // While a book starts the second tap is handled late. When the finger lifted is
                // what makes it a double tap.
                tap(key, liftedAtMs = lifted + DOUBLE_TAP_GAP_MS)
                loaderHeld.countDown()
                awaitLive("playing after a double tap") { it.isPlaying }
            }
        } finally {
            loaderHeld.countDown()
        }
    }

    @Test
    fun aDoubleTapOnPlayIsOnePlayHoweverSoonItIsHeard() {
        TestApp.publishBoundaryBook()
        ActivityScenario.launch(MainActivity::class.java).use {
            val key = centre(rowKey("Play", BOOK))
            val lifted = tap(key)
            awaitLive("playing after the first tap") { it.isPlaying }
            awaitNode(rowKey("Pause", BOOK))
            tap(key, liftedAtMs = lifted + DOUBLE_TAP_GAP_MS)
            SystemClock.sleep(SETTLE_MS)
            awaitLive("still playing after the second tap") { it.isPlaying }
        }
    }

    @Test
    fun aKeyThatStillShowsPlayNeverPauses() {
        val book = TestApp.publishBoundaryBook()
        ActivityScenario.launch(MainActivity::class.java).use {
            val key = centre(rowKey("Play", BOOK))
            // No frame is drawn between the taps, as on a phone busy starting the player, so the
            // key under the finger still shows Play.
            composeRule.mainClock.autoAdvance = false
            tap(key)
            awaitLive("playback requested") { it.showsPause(book) }
            SystemClock.sleep(NOT_A_DOUBLE_TAP_MS)
            tap(key)
            SystemClock.sleep(SETTLE_MS)
            composeRule.mainClock.autoAdvance = true
            awaitLive("playing after two taps on Play") { it.isPlaying }
        }
    }

    @Test
    fun playRightAfterClosingThePlayerIsHeard() {
        val book = TestApp.publishBoundaryBook()
        load(book, 10_000)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val key = centre(miniKey(BOOK))
            tap(centre(hasContentDescription("Open player, $BOOK")))
            awaitNode(hasContentDescription("Collapse player"))
            // Frames come from the test's clock: draw as many as pass before the finger lands, and
            // do not let the rule wait for a transition to end as a finger would not.
            composeRule.mainClock.autoAdvance = false
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.mainClock.advanceTimeBy(FINGER_AFTER_BACK_MS)
            tap(key)
            composeRule.mainClock.autoAdvance = true
            awaitLive("playing after a tap that followed Back") { it.isPlaying }
        }
    }

    @Test
    fun theMiniPlayerKeepsTouchesFromTheRowsBeneathIt() {
        val books = (1..SHELF).map { TestApp.publishBoundaryBook(itemId = "shelf-$it", title = "Shelf $it") }
        container.settings.setLastActiveBook(books.first())
        ActivityScenario.launch(MainActivity::class.java).use {
            val mini = hasContentDescription("Open player, Shelf 1")
            awaitNode(mini)
            val band = composeRule.onNode(mini).fetchSemanticsNode().boundsInWindow
            val row = hasText("Shelf $MIDDLE_ROW") and hasAnyAncestor(hasScrollToIndexAction())
            val before = centre(row)
            // The margin left of the mini-player's own button is still the mini-player.
            drag(Offset(band.left / 4, band.bottom - 4), Offset(band.left / 4, band.top - DRAG_PX))
            composeRule.waitForIdle()
            assertThat(centre(row)).isEqualTo(before)
        }
    }

    @Test
    fun playDuringATransientInterruptionAsksForTheAudioBack() {
        val book = TestApp.publishBoundaryBook()
        load(book, 10_000)
        val audio = context.getSystemService(AudioManager::class.java)
        val interruption = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).build())
            .setOnAudioFocusChangeListener {}
            .build()
        ActivityScenario.launch(MainActivity::class.java).use {
            tap(centre(miniKey(BOOK)))
            awaitLive("playing") { it.isPlaying }
            SystemClock.sleep(FOCUS_SETTLE_MS)
            assertThat(audio.requestAudioFocus(interruption)).isEqualTo(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            try {
                awaitLive("interrupted") { !it.isPlaying && it.playWhenReady }
                tap(centre(miniKey(BOOK)))
                awaitLive("playing again after one tap") { it.isPlaying }
            } finally {
                audio.abandonAudioFocusRequest(interruption)
            }
        }
    }

    @Test
    fun playOnABookAwaitingAChoiceShowsTheChoice() {
        val book = TestApp.publishBoundaryBook()
        runBlocking {
            rest(book, 100_000)
            val saved = checkNotNull(container.database.progress().get(book.profileId, book.itemId))
            container.database.progress().upsert(saved.copy(pendingContinuityChoice = true))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            tap(centre(rowKey("Resume", BOOK)))
            awaitNode(hasText(Copy.USE_PREVIOUS_POSITION), timeoutMs = 3_000)
        }
    }

    @Test
    fun theSkipKeysMoveABookThatIsAtRest() {
        val book = TestApp.publishBoundaryBook()
        runBlocking { rest(book, 100_000) }
        container.settings.setLastActiveBook(book)
        ActivityScenario.launch(MainActivity::class.java).use {
            openPlayer()
            tap(centre(hasContentDescription("Forward 30 seconds")))
            runBlocking {
                withTimeout(3_000) {
                    container.database.progress().observe(book.profileId, book.itemId).first { it?.positionMs == 130_000L }
                }
            }
        }
    }

    @Test
    fun sleepChosenForABookAtRestTurnsTheModeOnWithoutStartingIt() {
        val book = TestApp.publishBoundaryBook()
        runBlocking { rest(book, 100_000) }
        container.settings.setLastActiveBook(book)
        ActivityScenario.launch(MainActivity::class.java).use {
            openPlayer()
            composeRule.onNode(hasContentDescription("Sleep, off")).performScrollTo().performClick()
            composeRule.onNode(hasText("15 minutes") and hasClickAction()).performClick()
            awaitNode(hasContentDescription("Sleep, on, 15 minutes"))
            assertThat(container.sleepSettings.settings.value.enabled).isTrue()
            assertThat(container.playbackHub.state.value.sleep).isEqualTo(SleepState.Off)
            assertThat(container.playbackHub.state.value.playWhenReady).isFalse()
        }
    }

    @Test
    fun theRowUnderTheFingerStaysThereWhenItsBookStarts() {
        val older = TestApp.publishBoundaryBook(itemId = "older", title = OLDER)
        TestApp.publishBoundaryBook(itemId = "newer", title = NEWER)
        runBlocking {
            rest(older, 100_000)
            val saved = checkNotNull(container.database.progress().get(older.profileId, older.itemId))
            container.database.progress().upsert(saved.copy(lastListenedAt = 1))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            val key = centre(rowKey("Play", NEWER))
            tap(key)
            awaitLive("playing") { it.isPlaying }
            runBlocking {
                withTimeout(8_000) {
                    container.database.progress().observeEverything().first { all -> all.any { it.itemId == "newer" && it.lastListenedAt != null } }
                }
            }
            // Its line changes from New to a place, which may move the key a little, never by a row.
            assertThat((centre(rowKey("Pause", NEWER)) - key).getDistance()).isLessThan(SAME_ROW_PX)
        }
    }

    private suspend fun rest(book: BookKey, positionMs: Long) {
        val plan = checkNotNull(container.editionStore.plan(book))
        container.progressStore.saveOutsideSession(book, positionMs, plan.timeline.durationMs, plan.fingerprint, StopKind.EXACT)
    }

    private fun load(key: BookKey, positionMs: Long) = onMain {
        val c = connectController(context).also { controllers += it }
        c.setMediaItem(MediaItem.Builder().setMediaId(key.mediaId).build(), positionMs)
        c.prepare()
        c.awaitUntil(10_000, "ready") { playbackState == Player.STATE_READY }
    }

    private fun openPlayer() {
        tap(centre(hasContentDescription("Open player, $BOOK")))
        awaitNode(hasContentDescription("Collapse player"))
    }

    private fun described(vararg labels: String) = SemanticsMatcher("described as one of ${labels.toList()}") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it in labels }
    }

    private fun rowKey(action: String, title: String) =
        described("$action $title") and hasClickAction() and hasAnyAncestor(hasScrollToIndexAction())

    private fun miniKey(title: String) =
        described("Play $title", "Resume $title", "Pause $title") and hasClickAction() and !hasAnyAncestor(hasScrollToIndexAction())

    private fun awaitNode(matcher: SemanticsMatcher, timeoutMs: Long = 10_000) =
        composeRule.waitUntil(matcher.description, timeoutMs) { composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun centre(matcher: SemanticsMatcher): Offset {
        awaitNode(matcher)
        return composeRule.onNode(matcher).fetchSemanticsNode().boundsInWindow.center
    }

    /** Taps [at], now or with the finger lifting at [liftedAtMs], and returns when it lifted. */
    private fun tap(at: Offset, liftedAtMs: Long? = null): Long {
        val down = liftedAtMs?.minus(TAP_MS) ?: SystemClock.uptimeMillis()
        touch(down, MotionEvent.ACTION_DOWN, at, down)
        if (liftedAtMs == null) SystemClock.sleep(TAP_MS)
        val up = liftedAtMs ?: SystemClock.uptimeMillis()
        touch(down, MotionEvent.ACTION_UP, at, up)
        return up
    }

    private fun drag(from: Offset, to: Offset) {
        val down = SystemClock.uptimeMillis()
        touch(down, MotionEvent.ACTION_DOWN, from)
        for (step in 1..DRAG_STEPS) {
            SystemClock.sleep(FRAME_MS)
            touch(down, MotionEvent.ACTION_MOVE, from + (to - from) * (step.toFloat() / DRAG_STEPS))
        }
        touch(down, MotionEvent.ACTION_UP, to)
    }

    private fun touch(downTime: Long, action: Int, at: Offset, eventTime: Long = SystemClock.uptimeMillis()) {
        val event = MotionEvent.obtain(downTime, eventTime, action, at.x, at.y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        automation.injectInputEvent(event, true)
        event.recycle()
    }

    private fun awaitLive(what: String, condition: (LivePlayback) -> Boolean): LivePlayback = runBlocking {
        try {
            withTimeout(6_000) { container.playbackHub.state.first(condition) }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting for $what: ${container.playbackHub.state.value}", e)
        }
    }

    private companion object {
        const val BOOK = "Boundary Book"
        const val OLDER = "Older Book"
        const val NEWER = "Newer Book"
        const val SHELF = 9
        const val MIDDLE_ROW = 4
        const val TAP_MS = 40L
        const val DOUBLE_TAP_GAP_MS = 150L
        const val NOT_A_DOUBLE_TAP_MS = 500L
        const val SETTLE_MS = 1_000L
        const val SAME_ROW_PX = 30f
        const val FRAME_MS = 16L
        const val DRAG_STEPS = 12
        const val DRAG_PX = 500f
        const val FINGER_AFTER_BACK_MS = 250L
        const val FOCUS_SETTLE_MS = 500L
    }
}
