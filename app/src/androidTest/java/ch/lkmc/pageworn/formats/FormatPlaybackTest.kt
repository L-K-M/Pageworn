package ch.lkmc.pageworn.formats

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.pageworn.core.FakeClock
import ch.lkmc.pageworn.core.timeline.ChapterOrigin
import ch.lkmc.pageworn.core.timeline.Division
import ch.lkmc.pageworn.debug.LocalEditions
import ch.lkmc.pageworn.playback.PlaybackService
import ch.lkmc.pageworn.testing.Fixtures
import ch.lkmc.pageworn.testing.TestApp
import ch.lkmc.pageworn.testing.awaitUntil
import ch.lkmc.pageworn.testing.connectController
import ch.lkmc.pageworn.testing.onMain
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
 * A14 through the full player for each of the listener's formats: the downloaded edition takes its
 * chapters from the markers the file carries when the server has none, and a pause near 90 % of
 * the book resumes at that place after the playback service has stopped. Decode, late-seek
 * accuracy and durations per format are [FormatMatrixTest]'s.
 */
@RunWith(AndroidJUnit4::class)
class FormatPlaybackTest {
    private val context get() = TestApp.context
    private val container get() = TestApp.container

    @Before
    fun setUp() = TestApp.reset(FakeClock())

    @After
    fun tearDown() = TestApp.reset()

    private fun observe(text: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("observation", text) })

    /** Presses Play and returns where audio starts, as the service's player reports it. */
    private suspend fun MediaController.startPosition(): Long = coroutineScope {
        val started = async(start = CoroutineStart.UNDISPATCHED) { container.playbackHub.state.first { it.isPlaying }.positionMs }
        play()
        withTimeout(10_000) { started.await() }
    }

    @Test
    fun eachListenerFormatTakesItsMarkersAndResumesNearItsEnd() {
        val seen = LISTENER_FORMATS.map { id -> runBlocking { check(id) } }
        observe(seen.joinToString("; "))
    }

    private suspend fun check(id: String): String {
        val file = Fixtures.install(id).single()
        val probe = MediaProbe(context).probe(file)
        assertWithMessage("$id opens").that(probe.playable).isTrue()
        val key = TestApp.key(id)
        // As a download publishes it: the verifier's markers, and no usable server list.
        LocalEditions.publish(
            container, key, id, "Fixture Author",
            listOf(LocalEditions.Track(file, probe.durationMs!!, null)),
            serverChapters = emptyList(),
            embeddedChapters = listOf(probe.chapters),
        )
        val timeline = checkNotNull(container.editionStore.plan(key)).timeline
        if (probe.chapters.isEmpty()) {
            assertWithMessage("$id without markers").that(timeline.division).isEqualTo(Division.WHOLE_BOOK)
        } else {
            assertWithMessage("$id chapters").that(timeline.chapters.map { it.startMs to it.title })
                .containsExactlyElementsIn(probe.chapters.map { it.startMs to it.title }).inOrder()
            assertWithMessage("$id chapter origin").that(timeline.chapters.map { it.origin }.toSet()).containsExactly(ChapterOrigin.EMBEDDED)
        }

        val target = timeline.durationMs * 9 / 10
        val paused = onMain {
            val c = connectController(context)
            c.setMediaItem(MediaItem.Builder().setMediaId(key.mediaId).build(), target)
            c.prepare()
            c.play()
            c.awaitUntil(10_000, "$id playing near its end") { isPlaying }
            delay(PLAY_MS)
            c.pause()
            c.awaitUntil(description = "$id paused") { !isPlaying }
            delay(300)
            c.stop()
            c.clearMediaItems()
            c.release()
            checkNotNull(container.progressStore.read(key)).positionMs
        }
        assertWithMessage("$id paused place").that(paused).isIn(Range.closed(target, target + PLAY_MS + 1_500))
        context.stopService(Intent(context, PlaybackService::class.java))
        delay(1_000)

        val resumed = onMain {
            val c = connectController(context)
            c.setMediaItem(MediaItem.Builder().setMediaId(key.mediaId).build())
            c.prepare()
            c.awaitUntil(10_000, "$id ready") { playbackState == Player.STATE_READY }
            val at = c.startPosition()
            c.pause()
            c.awaitUntil(description = "$id paused again") { !isPlaying }
            c.stop()
            c.clearMediaItems()
            c.release()
            at
        }
        assertWithMessage("$id resumed place").that(resumed).isIn(Range.closed(paused - RESUME_TOLERANCE_MS, paused + RESUME_TOLERANCE_MS))
        context.stopService(Intent(context, PlaybackService::class.java))
        delay(500)
        return "$id: ${probe.chapters.size} markers, paused at $paused ms, resumed at $resumed ms"
    }

    private companion object {
        /** M4B, M4A, MP3, FLAC, Opus and Ogg; every one but the M4A carries chapter markers. */
        val LISTENER_FORMATS = listOf("fmt-m4b-aac", "fmt-m4a-aac", "fmt-mp3-chapters", "fmt-flac-flac", "fmt-opus-chapters", "fmt-ogg-vorbis-chapters")
        const val PLAY_MS = 1_000L

        /** The acceptance target for audible seek error; the matrix measured at most 100 ms. */
        const val RESUME_TOLERANCE_MS = 1_000L
    }
}
