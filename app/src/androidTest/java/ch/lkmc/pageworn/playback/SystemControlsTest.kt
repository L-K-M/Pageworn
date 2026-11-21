package ch.lkmc.pageworn.playback

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController as PlatformController
import android.media.session.MediaSession as PlatformSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.FakeClock
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.testing.TestApp
import ch.lkmc.pageworn.testing.awaitUntil
import ch.lkmc.pageworn.testing.connectController
import ch.lkmc.pageworn.testing.onMain
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The system's controls of the one session (A16, A16d, A16e): headset keys as the platform delivers
 * them, the media notification's own actions, the platform session that the lock screen reads, and
 * the playback resumption path. Bluetooth hardware, pulled headphones, the lock screen's look and the
 * resumption control after a phone restart are owner checks.
 */
@RunWith(AndroidJUnit4::class)
class SystemControlsTest {
    private val context get() = TestApp.context
    private val container get() = TestApp.container
    private lateinit var clock: FakeClock
    private lateinit var book: BookKey
    private val controllers = mutableListOf<MediaController>()

    @Before
    fun setUp() {
        clock = FakeClock()
        TestApp.reset(clock)
        book = publishWithCover()
    }

    @After
    fun tearDown() {
        onMain { controllers.forEach { it.release() } }
        TestApp.reset()
    }

    /** The boundary book with a cover of its own, as the resumption control shows it. */
    private fun publishWithCover(): BookKey {
        val cover = File(context.cacheDir, "test-cover.png")
        val bitmap = Bitmap.createBitmap(COVER_PX, COVER_PX, Bitmap.Config.ARGB_8888).apply { eraseColor(COVER_COLOR) }
        cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return TestApp.publishBoundaryBook(cover = cover)
    }

    private suspend fun controller(): MediaController = connectController(context).also { controllers += it }

    private fun item() = MediaItem.Builder().setMediaId(book.mediaId).build()

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    /** The platform session's block in `dumpsys media_session`, as the lock screen and notification read it. */
    private fun platformSession(): String {
        val dump = shell("dumpsys media_session")
        val start = dump.indexOf("package=${context.packageName}")
        assertWithMessage("platform session of the app").that(start).isAtLeast(0)
        return dump.substring(start, minOf(dump.length, start + 4_000))
    }

    /** The platform session's playback state code, printed as `PLAYING(3)` or as `3` by version. */
    private fun platformState(): Int {
        val match = PLATFORM_STATE.find(platformSession()) ?: throw AssertionError("no playback state in the platform session")
        return match.groupValues[1].toInt()
    }

    private suspend fun awaitPlatformState(expected: Int) {
        withTimeoutOrNull(NOTIFICATION_WAIT_MS) {
            while (platformState() != expected) delay(100)
        }
        assertWithMessage("platform playback state").that(platformState()).isEqualTo(expected)
    }

    private suspend fun MediaController.startPaused(positionMs: Long) {
        setMediaItem(item(), positionMs)
        prepare()
        play()
        awaitUntil(10_000, "playing") { isPlaying }
        pause()
        awaitUntil(description = "paused") { !isPlaying }
        seekTo(positionMs)
        awaitUntil(description = "at $positionMs") { kotlin.math.abs(currentPosition - positionMs) < 100 }
    }

    private suspend fun MediaController.awaitAt(expectedMs: Long, what: String) {
        awaitUntil(5_000, "$what at $expectedMs ms") { kotlin.math.abs(currentPosition - expectedMs) <= 500 }
    }

    private fun mediaNotification(): Notification? =
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .map { it.notification }
            .firstOrNull { it.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) }

    /** The media notification's action titled [title], once the notification shows it. */
    private suspend fun awaitAction(title: String): Notification.Action {
        fun find() = mediaNotification()?.actions.orEmpty().firstOrNull { it.title?.toString() == title }
        val found = withTimeoutOrNull(NOTIFICATION_WAIT_MS) {
            var action = find()
            while (action == null) {
                delay(100)
                action = find()
            }
            action
        }
        return found ?: throw AssertionError("no \"$title\" action among ${mediaNotification()?.actions.orEmpty().map { it.title }}")
    }

    @Test
    fun a16dHeadsetKeysAndTheDoublePressMoveWithinTheBook() {
        onMain {
            val c = controller()
            c.startPaused(100_000)
            shell("input keyevent KEYCODE_MEDIA_NEXT")
            c.awaitAt(130_000, "headset Next")
            shell("input keyevent KEYCODE_MEDIA_PREVIOUS")
            c.awaitAt(115_000, "headset Previous")
            // Two presses in one command arrive within the double-press window.
            shell("input keyevent KEYCODE_HEADSETHOOK KEYCODE_HEADSETHOOK")
            c.awaitAt(145_000, "double press")
            delay(1_000)
            // No control restarted the book, left it or started playback.
            assertThat(c.mediaItemCount).isEqualTo(1)
            assertThat(c.currentMediaItem?.mediaId).isEqualTo(book.mediaId)
            assertThat(c.isPlaying).isFalse()
            assertThat(c.currentPosition).isIn(Range.closed(144_500L, 145_500L))
        }
    }

    /**
     * The controls SystemUI draws for the media notification. From API 33 it builds them from the
     * platform session named in the notification, not from the notification's own actions, and a
     * tap reaches the session as a transport control or custom action. Android also sheds
     * notification updates beyond five a second per package, so the notification's actions can lag
     * the player while the session-derived controls stay current.
     */
    private suspend fun systemControls(): PlatformController {
        val notification = withTimeoutOrNull(NOTIFICATION_WAIT_MS) {
            var n = mediaNotification()
            while (n == null) {
                delay(100)
                n = mediaNotification()
            }
            n
        } ?: throw AssertionError("no media notification")
        val token = checkNotNull(notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, PlatformSession.Token::class.java))
        return PlatformController(context, token)
    }

    private fun PlatformController.press(name: String) {
        val action = playbackState?.customActions.orEmpty().firstOrNull { it.name.toString() == name }
            ?: throw AssertionError("no \"$name\" control among ${playbackState?.customActions.orEmpty().map { it.name }}")
        transportControls.sendCustomAction(action, null)
    }

    @Test
    fun a16dAnd16TheNotificationSkipsWithinTheBookAndEverySurfaceAgrees() {
        onMain {
            val c = controller()
            c.startPaused(100_000)
            // The notification carries the skip buttons, and its session presents the whole book:
            // one item spanning both files, as the lock screen's progress shows it.
            awaitAction("Back 15 seconds")
            awaitAction("Forward 30 seconds")
            val system = systemControls()
            assertThat(c.duration).isIn(Range.closed(209_000L, 211_000L))
            assertThat(system.metadata!!.getLong(MediaMetadata.METADATA_KEY_DURATION)).isIn(Range.closed(209_000L, 211_000L))
            system.press("Forward 30 seconds")
            c.awaitAt(130_000, "notification forward")
            system.press("Back 15 seconds")
            c.awaitAt(115_000, "notification back")
            assertThat(c.mediaItemCount).isEqualTo(1)
            assertThat(c.currentMediaItem?.mediaId).isEqualTo(book.mediaId)

            // Play and Pause from the notification: the app, the session and the platform agree.
            assertThat(c.isPlaying).isFalse()
            system.transportControls.play()
            c.awaitUntil(10_000, "playing from the notification") { isPlaying }
            assertThat(container.playbackHub.state.value.isPlaying).isTrue()
            awaitPlatformState(PlaybackState.STATE_PLAYING)
            system.transportControls.pause()
            c.awaitUntil(10_000, "paused from the notification") { !isPlaying }
            delay(500)
            assertThat(container.playbackHub.state.value.isPlaying).isFalse()
            awaitPlatformState(PlaybackState.STATE_PAUSED)
            assertWithMessage("platform position; controller at ${c.currentPosition}, state ${system.playbackState}\n${platformSession().take(1_500)}")
                .that(system.playbackState!!.position).isIn(Range.closed(115_000L, 117_000L))
        }
    }

    /**
     * A16, headphone removal. Android protects `AUDIO_BECOMING_NOISY` from apps and from the shell
     * user, so server-fixture/m4-check.sh sends it from a root shell on the emulator, twice, once
     * this test logs that it is playing. Pulling real headphones is an owner check.
     */
    @Test
    fun a16HeadphoneRemovalPausesOnceAndEverySurfaceAgrees() {
        assumeTrue("Run from server-fixture/m4-check.sh, which sends the broadcast", InstrumentationRegistry.getArguments().getString(NOISY_FROM_HOST) == "true")
        onMain {
            val c = controller()
            val playing = CopyOnWriteArrayList<Boolean>()
            c.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing += isPlaying
                }
            })
            c.setMediaItem(item(), 100_000)
            c.prepare()
            c.play()
            c.awaitUntil(10_000, "playing") { isPlaying }
            delay(1_000)
            playing.clear()
            Log.i(CHECK_TAG, NOISY_READY)
            c.awaitUntil(60_000, "paused by the headphone removal") { !isPlaying }
            val pausedAt = c.currentPosition
            // The host sends the broadcast a second time meanwhile; nothing may resume or pause again.
            delay(5_000)
            assertWithMessage("playing transitions after the removal").that(playing).containsExactly(false)
            assertThat(c.playWhenReady).isFalse()
            assertThat(c.currentPosition).isEqualTo(pausedAt)
            val saved = checkNotNull(container.progressStore.read(book))
            assertThat(saved.stopKind).isEqualTo(StopKind.ORDINARY)
            assertThat(saved.positionMs).isIn(Range.closed(pausedAt - 300, pausedAt + 300))
            assertThat(container.playbackHub.state.value.isPlaying).isFalse()
            awaitPlatformState(PlaybackState.STATE_PAUSED)
        }
    }

    @Test
    fun a16eResumptionPlaysTheLastBookAtItsPlaceAndSpeedWithoutASleepTimer() {
        onMain {
            val c = controller()
            c.setPlaybackSpeed(1.5f)
            c.startPaused(115_000)
            delay(500)
            // The service stops with an empty player, as after the process dies: only Room and the
            // settings keep the book. The process-death variant runs from server-fixture/m4-check.sh.
            c.stop()
            c.clearMediaItems()
            c.release()
            controllers.remove(c)
            context.stopService(Intent(context, PlaybackService::class.java))
            delay(1_000)
            assertThat(container.settings.lastActiveBook.value).isEqualTo(book)

            // A play request with nothing loaded asks the session for playback resumption.
            val press = controller()
            assertThat(press.mediaItemCount).isEqualTo(0)
            press.play()
            press.awaitUntil(10_000, "resumed") { isPlaying }
            assertThat(press.currentMediaItem?.mediaId).isEqualTo(book.mediaId)
            assertThat(press.currentPosition).isIn(Range.closed(115_000L, 116_500L))
            assertThat(press.playbackParameters.speed).isEqualTo(1.5f)
            assertThat(container.playbackHub.state.value.sleep).isEqualTo(SleepState.Off)
            press.pause()
        }
    }

    @Test
    fun a16eTheResumptionControlGetsTheBookAndItsCoverWithoutPlaying() {
        onMain {
            container.progressStore.saveOutsideSession(book, 115_000, 210_000, null, StopKind.ORDINARY)
            container.settings.setLastActiveBook(book)
            val player = ExoPlayer.Builder(context).build()
            val callback = BookSessionCallback(context.packageName, container, { error("metadata only") }, BookItems(context))
            val session = MediaLibrarySession.Builder(context, player, callback).setId("resumption-test").build()
            try {
                val systemUi = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
                    "com.android.systemui", 1, 1_000_000, MediaLibraryInfo.VERSION_INT, 1, true, Bundle.EMPTY, false,
                )
                val result = callback.onPlaybackResumption(session, systemUi, false).await()
                val metadata = result.mediaItems.single().mediaMetadata
                assertThat(result.mediaItems.single().mediaId).isEqualTo(book.mediaId)
                assertThat(metadata.title.toString()).isEqualTo("Boundary Book")
                // No network at boot: the cover travels as bytes, not as a URI to resolve.
                assertThat(metadata.artworkData).isNotNull()
                assertThat(metadata.artworkUri).isNull()
                assertThat(result.startPositionMs).isEqualTo(115_000)
                assertThat(player.isPlaying).isFalse()
            } finally {
                session.release()
                player.release()
                callback.release()
            }
        }
    }

    private companion object {
        const val COVER_PX = 64

        /** The log tag and line on which server-fixture/m4-check.sh sends the broadcast. */
        const val CHECK_TAG = "PagewornCheck"
        const val NOISY_READY = "ready: noisy"
        const val NOISY_FROM_HOST = "noisyFromHost"
        const val NOTIFICATION_WAIT_MS = 10_000L
        const val COVER_COLOR = 0xFF7A5C3A.toInt()
        val PLATFORM_STATE = Regex("""PlaybackState \{state=(?:[A-Z_]+\()?(\d+)""")
    }
}
