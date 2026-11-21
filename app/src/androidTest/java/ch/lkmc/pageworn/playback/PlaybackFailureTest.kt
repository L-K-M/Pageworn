package ch.lkmc.pageworn.playback

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.FileDataSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.FakeClock
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.data.db.EditionState
import ch.lkmc.pageworn.testing.FaultyFiles
import ch.lkmc.pageworn.testing.TestApp
import ch.lkmc.pageworn.testing.awaitUntil
import ch.lkmc.pageworn.testing.connectController
import ch.lkmc.pageworn.testing.onMain
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.state.PlayerStates
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Playback failure handling (A10d, A10e) through the playback service, with every track read through
 * [FaultyFiles]. Track A of the boundary book is 0 to 90 s and track B 90 to 210 s; B is `001.m4a`.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackFailureTest {
    private val container get() = TestApp.container
    private lateinit var clock: FakeClock
    private lateinit var faulty: FaultyFiles
    private val controllers = mutableListOf<MediaController>()

    @Before
    fun setUp() {
        clock = FakeClock()
        TestApp.reset(clock)
        faulty = FaultyFiles()
        container.playbackDataSourceFactory = faulty
    }

    @After
    fun tearDown() {
        // A test that failed early leaves its controller connected, which would keep this
        // service, with this test's file store, alive for the next test.
        onMain { controllers.forEach { it.release() } }
        TestApp.reset()
        container.playbackDataSourceFactory = FileDataSource.Factory()
    }

    private fun item(key: BookKey) = MediaItem.Builder().setMediaId(key.mediaId).build()

    private suspend fun stored(key: BookKey) = checkNotNull(container.progressStore.read(key))

    private suspend fun edition(key: BookKey) = checkNotNull(container.database.editions().edition(key.profileId, key.itemId))

    private suspend fun trackFile(key: BookKey, ordinal: Int): Pair<File, Long> = TestApp.publishedTrack(key, ordinal)

    /** Track [ordinal]'s published file name, and the byte offset that holds [atMs] of book time in it. */
    private suspend fun track(key: BookKey, ordinal: Int, atMs: Long? = null): Pair<String, Long> {
        val (file, start) = TestApp.publishedTrack(key, ordinal)
        return file.name to (atMs?.let { FaultyFiles.mp4OffsetAt(file, it - start) } ?: 0L)
    }

    private suspend fun trackB(key: BookKey, atMs: Long? = null) = track(key, 1, atMs)

    private suspend fun controller(): MediaController = connectController(TestApp.context).also { controllers += it }

    /** Loads [key] at [positionMs] and waits until the service holds it, so commands apply to it. */
    private suspend fun MediaController.load(key: BookKey, positionMs: Long) {
        setMediaItem(item(key), positionMs)
        prepare()
        withTimeout(10_000) { container.playbackHub.state.first { it.key == key && it.plan != null } }
    }

    private suspend fun MediaController.sleep(mode: String, minutes: Int = 0) {
        val args = Bundle().apply {
            putString(PagewornCommands.ARG_SLEEP_MODE, mode)
            putInt(PagewornCommands.ARG_SLEEP_MINUTES, minutes)
        }
        val result = sendCustomCommand(SessionCommand(PagewornCommands.SET_SLEEP, Bundle.EMPTY), args).await()
        assertWithMessage("sleep command").that(result.resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
    }

    private suspend fun awaitFailure(timeoutMs: Long = 30_000) = withTimeout(timeoutMs) { container.playbackHub.state.first { it.failed } }

    private fun observe(text: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("observation", text) })

    @Test
    fun a10dASingleReadErrorIsRetriedAndTheTimerKeepsItsDeadline() {
        val book = TestApp.publishBoundaryBook()
        onMain {
            val (b, offset) = trackB(book, atMs = 100_000)
            faulty.faults = listOf(FaultyFiles.FailReads(b, offset, times = 1))
            val c = controller()
            c.load(book, 95_000)
            c.sleep(PagewornCommands.SLEEP_TIMED, minutes = 15)
            val deadline = (container.playbackHub.state.value.sleep as SleepState.Timed).deadlineElapsedMs
            c.play()
            c.awaitUntil(20_000, "past 102 s") { currentPosition > 102_000 }
            assertThat(faulty.failures).containsExactly(b)
            assertThat(c.playerError).isNull()
            assertThat(c.isPlaying).isTrue()
            assertThat((container.playbackHub.state.value.sleep as SleepState.Timed).deadlineElapsedMs).isEqualTo(deadline)
            assertThat(edition(book).state).isEqualTo(EditionState.READY)
            c.release()
        }
    }

    @Test
    fun a10dRepeatedReadErrorsPauseNearTheFailureAndLeaveTheTimerArmed() {
        val book = TestApp.publishBoundaryBook()
        onMain {
            // Every read from 155 s on fails. The loader is held at those bytes until playback runs,
            // then its reads fail ahead of playback; the failure must surface only when playback
            // reaches the audio it could not read. (Failing from the start instead surfaces the
            // error before the player is ready, and arming it later races a loader that may have
            // read the whole book.) 2x keeps the wait short; the five-second bound holds at every
            // speed.
            val (b, offset) = trackB(book, atMs = FAILING_FROM_MS)
            val loaderHeld = CountDownLatch(1)
            faulty.faults = listOf(FaultyFiles.FailReads(b, offset, heldUntil = loaderHeld))
            val c = controller()
            c.load(book, 100_000)
            c.setPlaybackSpeed(2f)
            c.sleep(PagewornCommands.SLEEP_TIMED, minutes = 15)
            val deadline = (container.playbackHub.state.value.sleep as SleepState.Timed).deadlineElapsedMs
            c.play()
            c.awaitUntil(10_000, "playing") { isPlaying }
            loaderHeld.countDown()
            val live = awaitFailure(60_000)
            delay(500)
            val saved = stored(book)
            val (file, start) = trackFile(book, 1)
            val unreadMs = start + FaultyFiles.mp4TimeAt(file, faulty.failedAt.first().second)
            observe("repeated read errors at 2x: the first unread audio is at $unreadMs ms, saved ${saved.positionMs} ms, ${faulty.failedAt.size} failed reads")
            // A read that crosses the fault's first byte fails whole, so the unread audio may begin
            // one sample earlier.
            assertThat(unreadMs).isIn(Range.closed(FAILING_FROM_MS - ONE_SAMPLE_MS, FAILING_FROM_MS))
            assertThat(saved.positionMs).isIn(Range.closed(unreadMs - 5_000, unreadMs))
            assertThat(saved.finished).isFalse()
            assertThat(faulty.failures.size).isAtLeast(2)
            assertThat(c.playWhenReady).isFalse()
            assertThat(edition(book).state).isEqualTo(EditionState.REPAIR_NEEDED)
            val ui = PlayerStates.player(live.plan!!, edition(book), saved, container.playbackHub.state.value)
            assertThat(ui.failureMessage).isEqualTo(Copy.PLAYBACK_STOPPED)
            assertThat(ui.transportEnabled).isFalse()
            assertThat(ui.positionMs).isEqualTo(saved.positionMs)
            // The failure pause leaves the timer armed; when its deadline passes, it clears and the
            // saved place stays as it was.
            assertThat((container.playbackHub.state.value.sleep as SleepState.Timed).deadlineElapsedMs).isEqualTo(deadline)
            clock.advance(15 * 60_000L + 1_000)
            withTimeout(5_000) { container.playbackHub.state.first { it.sleep == SleepState.Off } }
            delay(500)
            assertThat(stored(book).positionMs).isEqualTo(saved.positionMs)
            assertThat(stored(book).stopKind).isEqualTo(saved.stopKind)
            assertThat(c.isPlaying).isFalse()
            c.release()
        }
    }

    @Test
    fun a10dATrackThatCannotOpenEndsPlaybackAtItsBoundary() {
        val book = TestApp.publishBoundaryBook()
        onMain {
            val (b, _) = trackB(book)
            faulty.faults = listOf(FaultyFiles.FailOpen(b))
            val c = controller()
            c.load(book, 84_000)
            c.play()
            var furthest = 0L
            withTimeout(30_000) {
                while (!container.playbackHub.state.value.failed) {
                    furthest = maxOf(furthest, c.currentPosition)
                    delay(20)
                }
            }
            delay(500)
            observe("track B cannot open: furthest controller position ${furthest} ms, saved ${stored(book).positionMs} ms")
            assertThat(stored(book).positionMs).isEqualTo(B_START_MS)
            assertThat(stored(book).finished).isFalse()
            // The player's clock runs on for a few milliseconds past the boundary; no audio of B
            // was read, so none was heard.
            assertThat(furthest).isAtMost(B_START_MS + SNAP_MS)
            assertThat(faulty.deliveredFrom(b)).isEqualTo(0L)
            assertThat(edition(book).state).isEqualTo(EditionState.REPAIR_NEEDED)
            c.release()
        }
    }

    @Test
    fun a10dASeekIntoATrackThatCannotOpenPlaysNothingAndKeepsThePlace() {
        val book = TestApp.publishBoundaryBook()
        onMain {
            val (b, _) = trackB(book)
            faulty.faults = listOf(FaultyFiles.FailOpen(b))
            val c = controller()
            c.load(book, 60_000)
            c.play()
            c.awaitUntil(10_000, "playing from 60 s") { isPlaying }
            delay(300)
            val before = stored(book).positionMs
            c.seekTo(150_000)
            awaitFailure()
            delay(500)
            observe("seek into a track that cannot open: place before ${before} ms, after ${stored(book).positionMs} ms")
            assertThat(stored(book).positionMs).isEqualTo(before)
            assertThat(before).isIn(Range.closed(60_000L, 61_000L))
            assertThat(faulty.deliveredFrom(b)).isEqualTo(0L)
            assertThat(c.isPlaying).isFalse()
            c.release()
        }
    }

    @Test
    fun a10dEndingAnInterruptionAfterAFailureDoesNotResume() {
        val book = TestApp.publishBoundaryBook()
        onMain {
            val c = controller()
            c.load(book, 105_000)
            c.play()
            c.awaitUntil(10_000, "playing") { isPlaying }
            val audio = TestApp.context.getSystemService(AudioManager::class.java)
            val interruption = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build()
            assertThat(audio.requestAudioFocus(interruption)).isEqualTo(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            try {
                c.awaitUntil(5_000, "interrupted") { playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS }
                delay(300)
                val before = stored(book).positionMs
                // Behind the playing position, so the seek must read the failing bytes again: the
                // player keeps no back buffer, while ahead it may already hold the rest of the book.
                val target = 95_000L
                assertThat(c.currentPosition).isAtLeast(target + 5_000)
                val (b, offset) = trackB(book, atMs = target - 2_000)
                faulty.faults = listOf(FaultyFiles.FailReads(b, offset))
                c.seekTo(target)
                try {
                    awaitFailure()
                } catch (timeout: Exception) {
                    throw AssertionError("no failure while interrupted: state=${c.playbackState} position=${c.currentPosition} " +
                        "suppression=${c.playbackSuppressionReason} error=${c.playerError} fault byte $offset, failed reads ${faulty.failedAt}", timeout)
                }
                assertThat(stored(book).positionMs).isEqualTo(before)
            } finally {
                audio.abandonAudioFocusRequest(interruption)
            }
            delay(3_000)
            assertThat(c.isPlaying).isFalse()
            assertThat(c.playWhenReady).isFalse()
            assertThat(edition(book).state).isEqualTo(EditionState.REPAIR_NEEDED)
            c.release()
        }
    }

    @Test
    fun a10dATimerThatExpiresDuringADelayedRetryStopsPlayback() {
        val book = TestApp.publishBoundaryBook()
        onMain {
            val c = controller()
            c.load(book, 100_000)
            c.sleep(PagewornCommands.SLEEP_TIMED, minutes = 15)
            c.play()
            c.awaitUntil(10_000, "playing") { isPlaying }
            delay(1_000)
            // Back into track A, which nothing has buffered: the seek loads from the file, the first
            // read fails and its retry waits six seconds.
            val (a, offset) = track(book, 0, atMs = 38_000)
            faulty.faults = listOf(FaultyFiles.FailReads(a, offset, times = 1, retryDelayMs = 6_000))
            c.seekTo(40_000)
            c.awaitUntil(5_000, "waiting for the retry") { faulty.failures.isNotEmpty() && !isPlaying }
            clock.advance(15 * 60_000L + 1_000)
            withTimeout(5_000) { container.playbackHub.state.first { it.sleep == SleepState.Off } }
            assertThat(c.playWhenReady).isFalse()
            // The retry completes and the player is ready, and nothing starts playback.
            c.awaitUntil(15_000, "retry complete") { playbackState == Player.STATE_READY }
            delay(3_000)
            assertThat(c.isPlaying).isFalse()
            assertThat(c.playerError).isNull()
            val saved = stored(book)
            observe("timer during a delayed retry: saved ${saved.positionMs} ms as ${saved.stopKind}")
            assertThat(saved.positionMs).isIn(Range.closed(39_500L, 40_500L))
            assertThat(saved.stopKind).isEqualTo(StopKind.TIMED)
            assertThat(edition(book).state).isEqualTo(EditionState.READY)
            c.release()
        }
    }

    @Test
    fun a10eEndOfTrackAtAFailingBoundaryCompletesAndTheNextPlaySurfacesTheFailure() {
        val book = TestApp.publishBoundaryBook(itemId = "no-chapters-multi", title = "Field Notes on Rain", chapters = emptyList(), fixtureId = "no-chapters-multi")
        onMain {
            val (b, _) = trackB(book)
            faulty.faults = listOf(FaultyFiles.FailOpen(b))
            val c = controller()
            c.load(book, 89_000)
            c.sleep(PagewornCommands.SLEEP_END_OF_SEGMENT)
            assertThat(container.playbackHub.state.value.sleep).isInstanceOf(SleepState.EndOf::class.java)
            c.play()
            withTimeout(15_000) { container.playbackHub.state.first { it.sleep == SleepState.Off && !it.playWhenReady } }
            delay(500)
            val stop = stored(book)
            observe("End of track at a boundary whose next track cannot open: saved ${stop.positionMs} ms as ${stop.stopKind}, edition ${edition(book).state}")
            assertThat(stop.positionMs).isEqualTo(B_START_MS)
            assertThat(stop.stopKind).isEqualTo(StopKind.EXACT)
            assertThat(stop.finished).isFalse()
            assertThat(edition(book).state).isEqualTo(EditionState.READY)
            assertThat(container.playbackHub.state.value.failed).isFalse()

            // The next play surfaces the failure: no track B audio, Repair needed, the place at 90 s.
            c.play()
            awaitFailure()
            delay(500)
            assertThat(edition(book).state).isEqualTo(EditionState.REPAIR_NEEDED)
            assertThat(stored(book).positionMs).isEqualTo(B_START_MS)
            assertThat(faulty.deliveredFrom(b)).isEqualTo(0L)
            assertThat(c.isPlaying).isFalse()
            c.release()
        }
    }

    /** Ends the service as a new run of the app finds it: no book in the player. */
    private suspend fun stopService() {
        val controller = connectController(TestApp.context)
        controller.stop()
        controller.clearMediaItems()
        controller.release()
        TestApp.context.stopService(android.content.Intent(TestApp.context, PlaybackService::class.java))
        delay(1_000)
    }

    private companion object {
        const val B_START_MS = 90_000L
        const val FAILING_FROM_MS = 155_000L

        /** Longer than one AAC frame of the fixtures (1024 samples at 44.1 kHz, 23 ms). */
        const val ONE_SAMPLE_MS = 50L

        /** A failure this close to a track boundary is saved as the boundary itself. */
        const val SNAP_MS = 500L
    }
}
