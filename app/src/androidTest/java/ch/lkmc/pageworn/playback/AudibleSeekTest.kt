package ch.lkmc.pageworn.playback

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.FakeClock
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.testing.CueListener
import ch.lkmc.pageworn.testing.Fixtures
import ch.lkmc.pageworn.testing.ListeningRenderersFactory
import ch.lkmc.pageworn.testing.TestApp
import ch.lkmc.pageworn.testing.awaitUntil
import ch.lkmc.pageworn.testing.connectController
import ch.lkmc.pageworn.testing.onMain
import ch.lkmc.pageworn.testing.positionInCurrentPeriodMs
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A09 through the playback service: what is heard after each seek in the boundary book. The
 * service's audio is teed to a [CueListener], so the check is the decoded audio the player
 * renders from the target, not only the position it reports. Whether the phone's speaker plays it
 * is part of the owner's audible seek check.
 */
@RunWith(AndroidJUnit4::class)
class AudibleSeekTest {
    private val context get() = TestApp.context
    private val container get() = TestApp.container
    private lateinit var listener: CueListener
    private lateinit var book: BookKey
    private val cues = Fixtures.fixture(BOUNDARY).cues
    private val controllers = mutableListOf<MediaController>()

    @Before
    fun setUp() {
        TestApp.reset(FakeClock())
        listener = CueListener()
        container.playbackRenderersFactory = { ListeningRenderersFactory(it, listener) }
        book = TestApp.publishBoundaryBook()
    }

    @After
    fun tearDown() {
        onMain { controllers.forEach { it.release() } }
        TestApp.reset()
    }

    private suspend fun controller(): MediaController = connectController(context).also { controllers += it }

    private fun observe(text: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("observation", text) })

    private fun cueAt(atMs: Long) = cues.single { it.atMs == atMs }

    /**
     * Seeks to [targetMs] while paused, plays [listenMs] of audio and pauses, and returns the cues
     * heard with their onsets. The sink flushes on the seek, so the capture starts at the first
     * sample the player renders from the target.
     */
    private suspend fun MediaController.listenFrom(targetMs: Long, listenMs: Long = LISTEN_MS): Map<Int, Long> {
        val flushes = listener.flushes
        seekTo(targetMs)
        awaitUntil(5_000, "the seek to $targetMs flushed the audio") { listener.flushes > flushes && playbackState == Player.STATE_READY }
        play()
        awaitUntil(10_000, "$listenMs ms of audio from $targetMs") { listener.capturedMs >= listenMs }
        pause()
        awaitUntil(description = "paused") { !isPlaying }
        return listener.heard(cues.map { it.freqHz })
    }

    /** The cue at [cueMs] is heard first, where the seek target puts it, within the 1 s target. */
    private fun assertHeard(heard: Map<Int, Long>, targetMs: Long, cueMs: Long) {
        val cue = cueAt(cueMs)
        val expected = cue.atMs - targetMs
        val onset = heard[cue.freqHz]
        assertWithMessage("cue at $cueMs ms after a seek to $targetMs ms, heard $heard").that(onset).isNotNull()
        assertWithMessage("audible error at $targetMs ms").that(onset!! - expected).isIn(Range.closed(-MAX_AUDIBLE_ERROR_MS, MAX_AUDIBLE_ERROR_MS))
        assertWithMessage("only that cue after $targetMs ms").that(heard.keys).containsExactly(cue.freqHz)
        observe("seek to $targetMs ms: ${cue.freqHz} Hz heard at ${onset} ms, expected $expected ms (error ${onset - expected} ms)")
    }

    @Test
    fun a09EachSeekIsHeardInItsIntervalAndARewindPersists() = onMain {
        val c = controller()
        c.setMediaItem(MediaItem.Builder().setMediaId(book.mediaId).build(), 0)
        c.prepare()
        c.awaitUntil(10_000, "ready") { playbackState == Player.STATE_READY }

        // Chapter Two starts at 75 s; its cue follows 250 ms later.
        assertHeard(c.listenFrom(75_000), 75_000, 75_250)
        // Track B starts at 90 s: the player is at its start, and B's first cue sounds.
        assertHeard(c.listenFrom(90_000), 90_000, 90_250)
        // 115 s is 25 s into track B, between the cues at 100.25 and 140.25 s: only the bed sounds.
        val quiet = c.listenFrom(115_000)
        assertWithMessage("no cue near 115 s").that(quiet).isEmpty()
        c.seekTo(115_000)
        c.awaitUntil(description = "back at 115 s") { playbackState == Player.STATE_READY && currentPosition in 114_900..115_100 }
        assertThat(c.currentPeriodIndex).isEqualTo(1)
        assertThat(c.positionInCurrentPeriodMs()).isIn(Range.closed(24_900L, 25_100L))
        // Chapter Three starts at 140 s.
        assertHeard(c.listenFrom(140_000), 140_000, 140_250)

        // A deliberate rewind while playing: Back 15 s, then pause. The place is the rewound one,
        // no later sample overwrites it, and the next start after a restart is there.
        c.play()
        c.awaitUntil(description = "playing past 141 s") { isPlaying && currentPosition >= 141_000 }
        val before = c.currentPosition
        c.seekBack()
        c.awaitUntil(description = "rewound") { currentPosition < before - 14_000 }
        delay(500)
        c.pause()
        c.awaitUntil(description = "paused") { !isPlaying }
        delay(500)
        val rewound = checkNotNull(container.progressStore.read(book)).positionMs
        assertThat(rewound).isIn(Range.closed(before - 15_000, before - 13_500))
        delay(1_500)
        assertThat(checkNotNull(container.progressStore.read(book)).positionMs).isEqualTo(rewound)
        c.release()
        controllers.remove(c)
        context.stopService(Intent(context, PlaybackService::class.java))
        delay(1_000)
        val again = controller()
        again.setMediaItem(MediaItem.Builder().setMediaId(book.mediaId).build())
        again.prepare()
        again.play()
        again.awaitUntil(10_000, "resumed") { isPlaying }
        again.pause()
        assertThat(container.playbackHub.state.value.positionMs).isIn(Range.closed(rewound - 300, rewound + 1_000))

        // 210 s is the end of the book. An explicit seek there finishes it, also while paused,
        // where the player rests a millisecond before the end instead of ending.
        again.seekTo(210_000)
        val end = withTimeoutOrNull(5_000) {
            var progress = checkNotNull(container.progressStore.read(book))
            while (!progress.finished) {
                delay(100)
                progress = checkNotNull(container.progressStore.read(book))
            }
            progress
        }
        assertWithMessage("finished after a seek to the end, player at ${again.currentPosition} in state ${again.playbackState}").that(end).isNotNull()
        assertThat(end!!.positionMs).isEqualTo(210_000L)
        assertThat(end.stopKind).isEqualTo(StopKind.EXACT)
        assertThat(container.playbackHub.state.value.ended).isTrue()
    }

    private companion object {
        const val BOUNDARY = "boundary-book"
        const val LISTEN_MS = 1_500L

        /** The acceptance target for audible seek error on valid fixtures. */
        const val MAX_AUDIBLE_ERROR_MS = 1_000L
    }
}
