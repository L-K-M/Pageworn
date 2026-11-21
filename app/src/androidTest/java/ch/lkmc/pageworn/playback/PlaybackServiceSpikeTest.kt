package ch.lkmc.pageworn.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.testing.Fixtures
import ch.lkmc.pageworn.testing.TestApp
import ch.lkmc.pageworn.testing.awaitUntil
import ch.lkmc.pageworn.testing.connectController
import ch.lkmc.pageworn.testing.onMain
import ch.lkmc.pageworn.testing.positionInCurrentPeriodMs
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M0 exit check: play a bundled chaptered M4B and a multi-file reference book through the
 * playback service. A seek to book time 115 s yields track B at 25 s.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackServiceSpikeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var boundaryKey: BookKey
    private lateinit var m4bKey: BookKey
    private lateinit var controller: MediaController

    @Before
    fun setUp() {
        TestApp.reset()
        boundaryKey = TestApp.publishBoundaryBook()
        m4bKey = TestApp.publishSingleM4b()
        controller = onMain { connectController(context) }
        onMain { controller.volume = 0f }
    }

    @After
    fun tearDown() = onMain {
        controller.stop()
        controller.clearMediaItems()
        controller.release()
    }

    @Test
    fun probedFixtureDurationsMatchTheBoundaryBook() {
        val files = Fixtures.install("boundary-book")
        assertThat(TestApp.probeDuration(files[0])).isIn(com.google.common.collect.Range.closed(89_950L, 90_050L))
        assertThat(TestApp.probeDuration(files[1])).isIn(com.google.common.collect.Range.closed(119_950L, 120_050L))
    }
    @Test
    fun multiFileBookSeeksAcrossTheFileBoundaryAsBookTime() = onMain {
        controller.setMediaItem(MediaItem.Builder().setMediaId(boundaryKey.mediaId).build())
        controller.prepare()
        controller.awaitUntil(description = "ready") { playbackState == Player.STATE_READY }
        assertThat(controller.duration).isIn(com.google.common.collect.Range.closed(209_500L, 210_500L))
        assertThat(controller.currentTimeline.periodCount).isEqualTo(2)

        controller.seekTo(115_000)
        controller.awaitUntil(description = "seek to 115 s") { playbackState == Player.STATE_READY && currentPosition in 114_900..115_100 }
        assertThat(controller.currentPeriodIndex).isEqualTo(1)
        assertThat(controller.positionInCurrentPeriodMs()).isIn(com.google.common.collect.Range.closed(24_900L, 25_100L))

        controller.play()
        controller.awaitUntil(description = "playing past 116 s") { isPlaying && currentPosition >= 116_000 }
        assertThat(controller.currentPeriodIndex).isEqualTo(1)
        controller.pause()

        // A seek to exactly 90 s selects track B at zero.
        controller.seekTo(90_000)
        controller.awaitUntil(description = "seek to 90 s") { playbackState == Player.STATE_READY && currentPosition in 89_950..90_100 }
        assertThat(controller.currentPeriodIndex).isEqualTo(1)
        assertThat(controller.positionInCurrentPeriodMs()).isAtMost(100L)
    }

    @Test
    fun chapteredM4bPlaysThroughTheService() = onMain {
        controller.setMediaItem(MediaItem.Builder().setMediaId(m4bKey.mediaId).build())
        controller.prepare()
        controller.awaitUntil(description = "ready") { playbackState == Player.STATE_READY }
        assertThat(controller.duration).isIn(com.google.common.collect.Range.closed(299_500L, 300_500L))
        assertThat(controller.mediaMetadata.title.toString()).isEqualTo("The Night Orchard")

        controller.seekTo(250_000)
        controller.play()
        controller.awaitUntil(description = "playing past 251 s") { isPlaying && currentPosition >= 251_000 }
        controller.pause()
    }

    @Test
    fun headsetNextAndPreviousMoveThirtyForwardAndFifteenBack() = onMain {
        controller.setMediaItem(MediaItem.Builder().setMediaId(boundaryKey.mediaId).build())
        controller.prepare()
        controller.awaitUntil(description = "ready") { playbackState == Player.STATE_READY }
        controller.seekTo(115_000)
        controller.awaitUntil(description = "at 115 s") { playbackState == Player.STATE_READY && currentPosition in 114_900..115_100 }
        assertThat(controller.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)).isTrue()
        controller.seekToNext()
        controller.awaitUntil(description = "next = +30 s") { currentPosition in 144_900..145_100 }
        controller.seekToPrevious()
        controller.awaitUntil(description = "previous = -15 s") { currentPosition in 129_900..130_100 }
        assertThat(controller.mediaItemCount).isEqualTo(1)
    }
}
