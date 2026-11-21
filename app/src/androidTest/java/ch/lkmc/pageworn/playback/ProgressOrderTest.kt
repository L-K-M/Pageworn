package ch.lkmc.pageworn.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.FakeClock
import ch.lkmc.pageworn.debug.LocalEditions
import ch.lkmc.pageworn.testing.FaultyFiles
import ch.lkmc.pageworn.testing.Fixtures
import ch.lkmc.pageworn.testing.TestApp
import ch.lkmc.pageworn.testing.awaitUntil
import ch.lkmc.pageworn.testing.connectController
import ch.lkmc.pageworn.testing.onMain
import ch.lkmc.pageworn.ui.state.DownloadedViewModel
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per-book places through the playback service with an injected clock: the resume rewind (A29,
 * except its process-death part in [RestartTest]) and the Downloaded order with Replay (A18).
 */
@RunWith(AndroidJUnit4::class)
class ProgressOrderTest {
    private val context get() = TestApp.context
    private val container get() = TestApp.container
    private lateinit var clock: FakeClock
    private lateinit var boundary: BookKey

    @Before
    fun setUp() {
        clock = FakeClock()
        TestApp.reset(clock)
        boundary = TestApp.publishBoundaryBook()
    }

    @After
    fun tearDown() = TestApp.reset()

    private fun item(key: BookKey) = MediaItem.Builder().setMediaId(key.mediaId).build()

    private suspend fun stored(key: BookKey) = container.progressStore.read(key)!!

    /** Loads [key] at [position], plays briefly and pauses: an ordinary stop near [position]. */
    private suspend fun MediaController.stopAt(key: BookKey, position: Long): Long {
        setMediaItem(item(key), position)
        prepare()
        play()
        awaitUntil(description = "playing ${key.itemId}") { isPlaying }
        pause()
        awaitUntil(description = "paused") { !isPlaying }
        delay(300)
        return stored(key).positionMs
    }

    /**
     * Presses Play and returns the position at which audio starts, as the service's player reports
     * it. The controller's own position at its first isPlaying event can still be the one from
     * before the rewind seek, because the session sends the seek and the start in separate updates.
     */
    private suspend fun MediaController.startPosition(): Long = coroutineScope {
        val started = async(start = CoroutineStart.UNDISPATCHED) {
            container.playbackHub.state.first { it.isPlaying }.positionMs
        }
        play()
        val startedAt = withTimeout(10_000) { started.await() }
        pause()
        awaitUntil(description = "paused") { !isPlaying }
        delay(300)
        startedAt
    }

    private fun assertStart(actual: Long, expected: Long, case: String) {
        assertWithMessage(case).that(actual).isIn(Range.closed(expected - 300, expected + 300))
    }

    @Test
    fun a29RestUnderFiveMinutesResumesExactly() = onMain {
        val c = connectController(context)
        val stop = c.stopAt(boundary, 115_000)
        clock.advance(4 * 60_000L + 59_000)
        assertThat(stored(boundary).positionMs).isEqualTo(stop)
        assertStart(c.startPosition(), stop, "after 4 min 59 s")
        c.release()
    }

    @Test
    fun a29RestOfFiveMinutesRewindsTenSeconds() = onMain {
        val c = connectController(context)
        val stop = c.stopAt(boundary, 120_000)
        clock.advance(5 * 60_000L)
        assertThat(stored(boundary).positionMs).isEqualTo(stop)
        assertStart(c.startPosition(), stop - 10_000, "after 5 min")
        c.release()
    }

    @Test
    fun a29RewindStopsAtTheStartOfTheBook() = onMain {
        val c = connectController(context)
        c.stopAt(boundary, 8_000)
        clock.advance(10 * 60_000L)
        assertStart(c.startPosition(), 0, "pause at 8 s, rest 10 min")
        c.release()
    }

    @Test
    fun a29SeekWhilePausedMakesTheNextStartExact() = onMain {
        val c = connectController(context)
        c.stopAt(boundary, 115_000)
        clock.advance(10 * 60_000L)
        c.seekTo(140_000)
        c.awaitUntil(description = "at 140 s") { playbackState == Player.STATE_READY && currentPosition in 139_900..140_100 }
        delay(300)
        assertThat(stored(boundary).positionMs).isIn(Range.closed(139_900L, 140_100L))
        assertStart(c.startPosition(), 140_000, "seek to 140 s while paused")
        c.release()
    }

    @Test
    fun a29PlayWhileASeekIsStillLoadingStartsAtTheSeek() = onMain {
        // The service reads through a store that holds the seek's first read of audio until Play
        // has been handled, so Play arrives before the seek settles, as a quick press after a skip
        // or a scrub can. The place saved before the seek must not undo it.
        val faulty = FaultyFiles()
        container.playbackDataSourceFactory = faulty
        val c = connectController(context)
        c.stopAt(boundary, 115_000)
        val (trackA, _) = TestApp.publishedTrack(boundary, 0)
        val loaderHeld = CountDownLatch(1)
        faulty.faults = listOf(FaultyFiles.FailReads(trackA.name, FaultyFiles.mp4OffsetAt(trackA, 40_000), times = 0, heldUntil = loaderHeld))
        c.seekTo(40_000)
        withTimeout(5_000) { container.playbackHub.state.first { it.positionMs in 39_900..40_100 } }
        val startedAt = coroutineScope {
            val started = async(start = CoroutineStart.UNDISPATCHED) {
                container.playbackHub.state.first { it.isPlaying }.positionMs
            }
            c.play()
            withTimeout(5_000) { container.playbackHub.state.first { it.playWhenReady } }
            delay(300)
            loaderHeld.countDown()
            withTimeout(10_000) { started.await() }
        }
        assertStart(startedAt, 40_000, "Play while the seek to 40 s loads")
        c.pause()
        c.awaitUntil(description = "paused") { !isPlaying }
        c.release()
    }

    @Test
    fun a29AnotherBookForTenMinutesRewindsTheRestingOne() = onMain {
        val orchard = TestApp.publishSingleM4b()
        val c = connectController(context)
        val stop = c.stopAt(boundary, 115_000)
        c.setMediaItem(item(orchard), 50_000)
        c.prepare()
        c.play()
        c.awaitUntil(description = "other book playing") { isPlaying }
        clock.advance(10 * 60_000L)
        c.pause()
        c.awaitUntil(description = "paused") { !isPlaying }
        delay(300)
        assertThat(stored(boundary).positionMs).isEqualTo(stop)
        c.setMediaItem(item(boundary))
        c.prepare()
        c.awaitUntil(description = "boundary ready") { playbackState == Player.STATE_READY && currentMediaItem?.mediaId == boundary.mediaId }
        assertStart(c.startPosition(), stop - 10_000, "after another book for 10 min")
        c.release()
    }

    /**
     * A18: a finished book, a partly listened one and a never-played download. Downloaded sorts by
     * last-listened time, then never-played books by download completion; Replay starts at zero and
     * the partly listened book resumes at its place.
     */
    @Test
    fun a18DownloadedOrderAndReplay() {
        val orchard = TestApp.publishSingleM4b()
        clock.advance(60_000)
        val smallHours = runBlocking {
            val key = TestApp.key("small-hours")
            val file = Fixtures.install("no-chapters-single").single()
            LocalEditions.publish(container, key, "Small Hours", "Kit Mercer", listOf(LocalEditions.Track(file, TestApp.probeDuration(file))))
            key
        }

        val orchardPlace = onMain {
            val c = connectController(context)
            // Finish the boundary book.
            c.setMediaItem(item(boundary), 207_000)
            c.prepare()
            c.play()
            withTimeout(15_000) {
                container.database.progress().observe(boundary.profileId, boundary.itemId).first { it?.finished == true }
            }
            clock.advance(60_000)
            // Listen to part of the M4B later.
            val place = c.stopAt(orchard, 50_000)
            c.release()
            place
        }
        runBlocking {
            assertThat(stored(boundary).finished).isTrue()
            assertThat(stored(orchard).finished).isFalse()
        }

        // Relaunch: playback stops and a new view model reads the stored state.
        TestApp.context.stopService(android.content.Intent(context, PlaybackService::class.java))
        val books = onMain {
            val vm = DownloadedViewModel(container)
            withTimeout(10_000) { vm.state.first { it.books.size == 3 }.books }
        }
        assertThat(books.map { it.face.key }).containsExactly(orchard, boundary, smallHours).inOrder()

        onMain {
            val c = connectController(context)
            c.setMediaItem(item(boundary))
            c.prepare()
            c.awaitUntil(description = "boundary ready") { playbackState == Player.STATE_READY && currentMediaItem?.mediaId == boundary.mediaId }
            assertStart(c.startPosition(), 0, "Replay of the finished book")
            c.setMediaItem(item(orchard))
            c.prepare()
            c.awaitUntil(description = "orchard ready") { playbackState == Player.STATE_READY && currentMediaItem?.mediaId == orchard.mediaId }
            assertStart(c.startPosition(), orchardPlace, "the partly listened book")
            c.release()
        }
    }
}
