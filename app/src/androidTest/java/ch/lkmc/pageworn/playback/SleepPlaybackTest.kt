package ch.lkmc.pageworn.playback

import android.app.KeyguardManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.FakeClock
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.core.sleep.SleepWindow
import ch.lkmc.pageworn.core.timeline.RawChapter
import ch.lkmc.pageworn.data.db.AddressEntity
import ch.lkmc.pageworn.data.db.BookProgressEntity
import ch.lkmc.pageworn.data.db.ProfileEntity
import ch.lkmc.pageworn.data.db.SessionState
import ch.lkmc.pageworn.debug.LocalEditions
import ch.lkmc.pageworn.testing.CueListener
import ch.lkmc.pageworn.testing.Fixtures
import ch.lkmc.pageworn.testing.ListeningRenderersFactory
import ch.lkmc.pageworn.testing.RestartMarker
import ch.lkmc.pageworn.testing.TestApp
import ch.lkmc.pageworn.testing.awaitUntil
import ch.lkmc.pageworn.testing.connectController
import ch.lkmc.pageworn.testing.onMain
import ch.lkmc.pageworn.ui.model.CoverImage
import ch.lkmc.pageworn.ui.state.PlayerStates
import ch.lkmc.pageworn.ui.state.RowBuilders
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** M5 sleep behavior through the media session with the emulator's screen locked. */
@RunWith(AndroidJUnit4::class)
class SleepPlaybackTest {
    private val clock = FakeClock()
    private val context get() = TestApp.context
    private val container get() = TestApp.container
    private val power get() = context.getSystemService(PowerManager::class.java)
    private val keyguard get() = context.getSystemService(KeyguardManager::class.java)
    private val controllers = mutableListOf<MediaController>()
    private var preserveOfflineEdition = false

    @Before
    fun setUp() = TestApp.reset(clock)

    @After
    fun tearDown() {
        onMain { controllers.forEach { it.release() } }
        if (!preserveOfflineEdition) TestApp.reset()
    }

    private suspend fun load(book: BookKey, positionMs: Long): MediaController {
        val controller = connectController(context).also { controllers += it }
        controller.setMediaItem(MediaItem.Builder().setMediaId(book.mediaId).build(), positionMs)
        controller.prepare()
        controller.awaitUntil(description = "ready at $positionMs ms") {
            playbackState == Player.STATE_READY && currentPosition in (positionMs - 30)..(positionMs + 30)
        }
        return controller
    }

    private suspend fun MediaController.setSleep(mode: String, minutes: Int = 0) {
        val args = Bundle().apply {
            putString(PagewornCommands.ARG_SLEEP_MODE, mode)
            putInt(PagewornCommands.ARG_SLEEP_MINUTES, minutes)
        }
        val command = SessionCommand(PagewornCommands.SET_SLEEP, Bundle.EMPTY)
        assertThat(sendCustomCommand(command, args).await().resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
    }

    private fun shell(command: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
    }

    private suspend fun withLockedScreen(block: suspend () -> Unit) {
        shell("input keyevent KEYCODE_SLEEP")
        var lockedCheckPassed = false
        try {
            withTimeout(8_000) {
                while (power.isInteractive || !keyguard.isKeyguardLocked) delay(30)
            }
            block()
            assertThat(power.isInteractive).isFalse()
            assertThat(keyguard.isKeyguardLocked).isTrue()
            lockedCheckPassed = true
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            // The dismiss is asynchronous. Let it finish before the next test locks the screen.
            val dismissed = withTimeoutOrNull(8_000) {
                while (!power.isInteractive || keyguard.isKeyguardLocked) delay(30)
                true
            }
            if (lockedCheckPassed) assertWithMessage("Keyguard dismissed after test").that(dismissed).isTrue()
        }
    }

    private suspend fun saved(
        book: BookKey,
        predicate: (BookProgressEntity) -> Boolean,
    ): BookProgressEntity = withTimeout(5_000) {
        checkNotNull(container.database.progress().observe(book.profileId, book.itemId)
            .first { it != null && predicate(it) })
    }

    private suspend fun awaitSleepStop(description: String): LivePlayback =
        try {
            withTimeout(8_000) { container.playbackHub.state.first { !it.playWhenReady && it.sleep == SleepState.Off } }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            val live = container.playbackHub.state.value
            throw AssertionError("$description at ${live.positionMs} ms, requesting play: ${live.playWhenReady}, sleep: ${live.sleep}", timeout)
        }

    private suspend fun liveWhen(condition: (LivePlayback) -> Boolean): LivePlayback =
        withTimeout(8_000) { container.playbackHub.state.first(condition) }

    @Test
    fun a08ExpiredLoginAndAirplaneModeKeepCoverChaptersAndSavedPlaceAvailable() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("offline") == "true")
        assertThat(context.getSystemService(ConnectivityManager::class.java).activeNetwork).isNull()
        runBlocking {
            container.database.profiles().upsert(
                ProfileEntity(TestApp.PROFILE, "offline-fixture", "listener", null, null, "2.38.0", clock.wall),
            )
            container.database.addresses().upsert(AddressEntity(
                "offline-address", TestApp.PROFILE, "https://offline.invalid/", "Home", 0,
                sessionState = SessionState.EXPIRED,
            ))
        }
        val cover = File.createTempFile("offline-cover", ".png", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val book = TestApp.publishBoundaryBook(cover = cover)
            val original = cover.readBytes()
            val requests = container.networkDiagnostics.entries()
            onMain {
                val c = load(book, 115_000)
                val plan = checkNotNull(container.editionStore.plan(book))
                val edition = checkNotNull(container.database.editions().edition(book.profileId, book.itemId))
                val row = RowBuilders.editionFace(edition, container.editionFiles.published(edition.generationId))
                val player = PlayerStates.player(plan, edition, container.progressStore.read(book), container.playbackHub.state.value)
                assertThat((row.cover as CoverImage.Local).file.readBytes()).isEqualTo(original)
                assertThat((player.face.cover as CoverImage.Local).file.readBytes()).isEqualTo(original)
                val artwork = checkNotNull(c.currentMediaItem?.mediaMetadata?.artworkUri)
                assertThat(context.contentResolver.openInputStream(artwork)?.use { it.readBytes() }).isEqualTo(original)

                c.setPlaybackSpeed(1.5f)
                c.seekTo(plan.timeline.chapters.last().startMs)
                c.awaitUntil(description = "at the last chapter") {
                    playbackState == Player.STATE_READY && currentPosition in 139_950..140_050
                }
                withLockedScreen {
                    for (minutes in listOf(15, 30, 45, 60)) {
                        c.play()
                        liveWhen { it.isPlaying && it.speed == 1.5f }
                        c.setSleep(PagewornCommands.SLEEP_TIMED, minutes)
                        clock.advance(minutes * 60_000L)
                        awaitSleepStop("offline $minutes minute timer")
                    }
                    c.play()
                    liveWhen { it.isPlaying }
                    c.setSleep(PagewornCommands.SLEEP_TIMED, 15)
                    c.setSleep(PagewornCommands.SLEEP_OFF)
                    clock.advance(16 * 60_000L)
                    delay(PlaybackController.TICK_MS * 2)
                    assertThat(container.playbackHub.state.value.isPlaying).isTrue()
                    c.pause()
                }
                val paused = liveWhen { !it.playWhenReady }.positionMs
                val stop = saved(book) { it.stopKind == StopKind.ORDINARY && it.positionMs in (paused - 100)..(paused + 100) }
                c.stop()
                c.clearMediaItems()
                c.awaitUntil(description = "session item cleared") { mediaItemCount == 0 }
                val resumed = connectController(context).also { controllers += it }
                resumed.setMediaItem(MediaItem.Builder().setMediaId(book.mediaId).build())
                resumed.prepare()
                resumed.awaitUntil(description = "reloaded saved place") {
                    playbackState == Player.STATE_READY && currentPosition in (stop.positionMs - 30)..(stop.positionMs + 30)
                }
                assertThat(resumed.playbackParameters.speed).isEqualTo(1.5f)
                assertThat(container.progressStore.read(book)?.positionMs).isEqualTo(stop.positionMs)
                if (InstrumentationRegistry.getArguments().getString("offlineRestart") == "true") {
                    RestartMarker.write("offline", mapOf("position" to stop.positionMs, "wall" to clock.wall))
                    preserveOfflineEdition = true
                }
            }
            assertThat(container.networkDiagnostics.entries()).isEqualTo(requests)
        } finally {
            cover.delete()
        }
    }

    @Test
    fun a12AndA22EachTimedChoiceUsesElapsedTimeAtDoubleSpeedAndOffKeepsPlaying() {
        val book = TestApp.publishSingleM4b()
        onMain {
            val c = load(book, 80_000)
            c.setPlaybackSpeed(2f)
            withLockedScreen {
                for (minutes in listOf(15, 30, 45, 60)) {
                    c.play()
                    liveWhen { it.isPlaying && it.speed == 2f }
                    val armedAt = clock.elapsed
                    c.setSleep(PagewornCommands.SLEEP_TIMED, minutes)
                    val deadline = armedAt + minutes * 60_000L
                    assertThat(container.playbackHub.state.value.sleep).isEqualTo(SleepState.Timed(minutes, deadline))

                    // Only elapsed time drives the deadline. A wall-clock correction leaves it armed.
                    val oldWall = clock.wall
                    clock.wall += 8 * 60 * 60_000L
                    clock.elapsed = deadline - 1
                    delay(PlaybackController.TICK_MS * 2)
                    assertThat(c.isPlaying).isTrue()
                    assertThat(container.playbackHub.state.value.sleep).isInstanceOf(SleepState.Timed::class.java)
                    clock.wall = oldWall
                    clock.elapsed = deadline
                    val stopped = awaitSleepStop("$minutes minute timer")
                    assertThat(stopped.isPlaying).isFalse()
                    saved(book) { it.stopKind == StopKind.TIMED && it.positionMs in (stopped.positionMs - 100)..(stopped.positionMs + 100) }
                }
                c.play()
                c.awaitUntil(description = "playing with Off") { isPlaying }
                c.setSleep(PagewornCommands.SLEEP_TIMED, 15)
                c.setSleep(PagewornCommands.SLEEP_OFF)
                clock.advance(16 * 60_000L)
                delay(PlaybackController.TICK_MS * 2)
                assertThat(c.isPlaying).isTrue()
                assertThat(container.playbackHub.state.value.sleep).isEqualTo(SleepState.Off)
                c.pause()
            }
        }
    }

    @Test
    fun a12ExpiryDuringATransientAudioInterruptionPreventsAutoResume() {
        val book = TestApp.publishBoundaryBook()
        onMain {
            val c = load(book, 80_000)
            val audio = context.getSystemService(AudioManager::class.java)
            val interruption = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener {}
                .build()
            c.setPlaybackSpeed(2f)
            c.play()
            liveWhen { it.isPlaying && it.speed == 2f }
            delay(350)
            c.setSleep(PagewornCommands.SLEEP_TIMED, 15)
            assertThat(audio.requestAudioFocus(interruption)).isEqualTo(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            try {
                c.awaitUntil(description = "transient focus loss") {
                    playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
                }
                val paused = saved(book) { it.stopKind == StopKind.ORDINARY }
                withLockedScreen {
                    clock.advance(15 * 60_000L)
                    awaitSleepStop("expiry during audio focus loss")
                    assertThat(container.progressStore.read(book)).isEqualTo(paused)
                    audio.abandonAudioFocusRequest(interruption)
                    delay(1_000)
                    assertThat(c.isPlaying).isFalse()
                    assertThat(c.playWhenReady).isFalse()
                }
            } finally {
                audio.abandonAudioFocusRequest(interruption)
            }
        }
    }

    @Test
    fun a13ChapterStopResumesExactlyAndRetargetsAfterASeek() {
        val book = TestApp.publishBoundaryBook()
        onMain {
            val c = load(book, 115_000)
            // At rest, choosing sets the mode only; the timer starts with playback.
            c.setSleep(PagewornCommands.SLEEP_END_OF_SEGMENT)
            assertThat(container.playbackHub.state.value.sleep).isEqualTo(SleepState.Off)
            // Shorten the real wait by seeking within the same chapter before locking.
            c.seekTo(138_500)
            c.awaitUntil(description = "ready near the chapter end") {
                playbackState == Player.STATE_READY && currentPosition in 138_470..138_530
            }
            withLockedScreen {
                assertBoundary(c, book, 140_000, finished = false)
                clock.advance(8 * 60 * 60_000L)
                c.play()
                c.awaitUntil(description = "exactly at the next chapter") {
                    isPlaying && currentPosition in 139_980..140_250
                }
                // The mode stays on: this start targets the end of the chapter it is in.
                liveWhen { it.isPlaying && it.sleep != SleepState.Off }
                assertEndTarget(210_000)
                c.seekTo(73_500)
                liveWhen { it.positionMs in 73_470..74_000 && (it.sleep as? SleepState.EndOf)?.boundaryMs == 75_000L }
                assertBoundary(c, book, 75_000, finished = false)
                c.pause()
                c.awaitUntil(description = "paused") { !isPlaying }
                assertThat(container.playbackHub.state.value.sleep).isEqualTo(SleepState.Off)
            }
        }
    }

    @Test
    fun a13AnAudioClipProtectsTheNextChapterEvenIfThePauseArrivesLate() {
        val book = TestApp.publishBoundaryBook()
        val plan = checkNotNull(runBlocking { container.editionStore.plan(book) })
        val followingCue = Fixtures.fixture("boundary-book").cues.single { it.atMs == 140_250L }
        onMain {
            val listener = CueListener()
            val player = ExoPlayer.Builder(context, ListeningRenderersFactory(context, listener))
                .setMediaSourceFactory(BookMediaSourceFactory(container.playbackDataSourceFactory))
                .build()
            try {
                suspend fun renderPastBoundary(clipEndMs: Long?): Long? {
                    player.setMediaItem(BookItems(context).playable(plan, clipEndMs), 139_000)
                    player.prepare()
                    player.play()
                    player.awaitUntil(description = "past the following chapter cue") {
                        isPlaying && currentPosition >= 141_000
                    }
                    player.pause()
                    assertThat(player.duration in 209_950..210_050).isTrue()
                    return listener.onsetMs(followingCue.freqHz)
                }

                assertThat(renderPastBoundary(null)).isNotNull()
                assertThat(renderPastBoundary(140_000)).isNull()
            } finally {
                player.release()
            }
        }
    }

    @Test
    fun a13bEndOfTheLastChapterFinishesTheBook() {
        val book = TestApp.publishBoundaryBook()
        onMain {
            val c = load(book, 208_500)
            c.setSleep(PagewornCommands.SLEEP_END_OF_SEGMENT)
            withLockedScreen { assertBoundary(c, book, 210_000, finished = true) }
        }
    }

    @Test
    fun a13cChapterlessMultiFileBookStopsExactlyAtTheTrackBoundary() {
        val book = TestApp.publishBoundaryBook(fixtureId = "no-chapters-multi", chapters = emptyList())
        onMain {
            val c = load(book, 89_000)
            c.setSleep(PagewornCommands.SLEEP_END_OF_SEGMENT)
            withLockedScreen {
                assertBoundary(c, book, 90_000, finished = false)
                c.setSleep(PagewornCommands.SLEEP_OFF)
                c.play()
                c.awaitUntil(description = "playing track B from its start") {
                    isPlaying && currentPeriodIndex == 1 && currentPosition in 89_980..90_250
                }
                c.pause()
            }
        }
    }

    @Test
    fun a28bGeneratedInterludeEndsTenSecondsIntoTrackB() {
        val book = TestApp.publishBoundaryBook(chapters = listOf(
            RawChapter(30_000, 75_000, null), RawChapter(100_000, 150_000, null),
        ))
        onMain {
            val c = load(book, 80_000)
            c.setSleep(PagewornCommands.SLEEP_END_OF_SEGMENT)
            c.seekTo(98_500)
            c.awaitUntil(description = "ready near the end of the interlude") {
                playbackState == Player.STATE_READY && currentPosition in 98_470..98_530
            }
            withLockedScreen { assertBoundary(c, book, 100_000, finished = false) }
        }
    }

    @Test
    fun a12bTimedStopsRewindOnlyAfterFiveMinutesAndLaterSeeksTakePriority() {
        val file = Fixtures.install("fmt-mp3-vbr-long").single()
        val book = TestApp.key("long-vbr")
        runBlocking {
            LocalEditions.publish(container, book, "Long VBR", null,
                listOf(LocalEditions.Track(file, TestApp.probeDuration(file))))
        }
        onMain {
            val c = load(book, 600_000)
            suspend fun seekTo(positionMs: Long) {
                c.seekTo(positionMs)
                c.awaitUntil(description = "ready at $positionMs ms") {
                    playbackState == Player.STATE_READY && currentPosition in (positionMs - 100)..(positionMs + 100)
                }
                saved(book) { it.stopKind == StopKind.EXACT && it.positionMs in (positionMs - 100)..(positionMs + 100) }
            }

            suspend fun stopAt(positionMs: Long): Long {
                seekTo(positionMs)
                c.play()
                liveWhen { it.isPlaying }
                c.setSleep(PagewornCommands.SLEEP_TIMED, 15)
                clock.advance(15 * 60_000L)
                val stop = awaitSleepStop("timed stop near $positionMs ms").positionMs
                assertThat(stop in positionMs..(positionMs + 1_000)).isTrue()
                return saved(book) { it.stopKind == StopKind.TIMED && it.positionMs in (stop - 100)..(stop + 100) }.positionMs
            }

            suspend fun resumeAt(expected: Long) {
                c.play()
                liveWhen {
                    it.isPlaying && it.positionMs in (expected - 100)..(expected + 350)
                }
                c.pause()
                liveWhen { !it.playWhenReady && !it.isPlaying }
            }

            val fourMinutes = stopAt(600_000)
            clock.advance(4 * 60_000L)
            resumeAt(fourMinutes)

            val fiveMinutes = stopAt(600_000)
            clock.advance(5 * 60_000L)
            resumeAt(fiveMinutes - 300_000)

            stopAt(200_000)
            clock.advance(10 * 60_000L)
            resumeAt(0)

            stopAt(600_000)
            seekTo(700_000)
            clock.advance(10 * 60_000L)
            resumeAt(700_000)

            seekTo(600_000)
            c.play()
            liveWhen { it.isPlaying }
            c.setSleep(PagewornCommands.SLEEP_TIMED, 15)
            c.pause()
            liveWhen { !it.isPlaying && !it.playWhenReady }
            saved(book) { it.stopKind == StopKind.ORDINARY && it.positionMs in 599_900..601_000 }
            // Let the pause settle where the player stopped before taking its place.
            delay(500)
            val ordinary = checkNotNull(container.progressStore.read(book))
            // The pause ended the timer, so its deadline passing changes nothing.
            assertThat(container.playbackHub.state.value.sleep).isEqualTo(SleepState.Off)
            clock.advance(15 * 60_000L)
            delay(PlaybackController.TICK_MS * 2)
            assertThat(container.progressStore.read(book)).isEqualTo(ordinary)
            clock.advance(10 * 60_000L)
            resumeAt(ordinary.positionMs - 10_000)
        }
    }

    @Test
    fun a13dEveryStartRunsTheFullLengthAndABookSwitchStartsAFreshTimer() {
        val book = TestApp.publishBoundaryBook()
        val other = TestApp.publishBoundaryBook(itemId = "other-book", title = "Other Book")
        onMain {
            val c = load(book, 10_000)
            c.setSleep(PagewornCommands.SLEEP_TIMED, 30)
            assertThat(container.playbackHub.state.value.sleep).isEqualTo(SleepState.Off)

            c.play()
            liveWhen { it.isPlaying }
            assertThat(container.playbackHub.state.value.sleep).isEqualTo(SleepState.Timed(30, clock.elapsed + 30 * 60_000L))
            clock.advance(10 * 60_000L)
            c.pause()
            liveWhen { !it.playWhenReady && it.sleep == SleepState.Off }

            clock.advance(60_000L)
            c.play()
            liveWhen { it.isPlaying }
            assertThat(container.playbackHub.state.value.sleep).isEqualTo(SleepState.Timed(30, clock.elapsed + 30 * 60_000L))

            clock.advance(5 * 60_000L)
            c.setMediaItem(MediaItem.Builder().setMediaId(other.mediaId).build())
            c.prepare()
            c.play()
            val switched = liveWhen { it.key == other && it.isPlaying && it.sleep is SleepState.Timed }
            assertThat(switched.sleep).isEqualTo(SleepState.Timed(30, clock.elapsed + 30 * 60_000L))
            c.pause()
        }
    }

    @Test
    fun a13eTheVolumeFadesBeforeATimedStopAndPlayDuringTheFadeKeepsPlaying() {
        val book = TestApp.publishBoundaryBook()
        onMain {
            val c = load(book, 10_000)
            c.setSleep(PagewornCommands.SLEEP_TIMED, 15)
            c.play()
            val deadline = (liveWhen { it.isPlaying && it.sleep is SleepState.Timed }.sleep as SleepState.Timed).deadlineElapsedMs
            assertThat(c.volume).isEqualTo(1f)

            clock.elapsed = deadline - 1_500
            c.awaitUntil(description = "fading") { volume in 0.01f..0.5f }
            // Pause during the fade keeps the book playing with a fresh timer at full volume.
            c.pause()
            val kept = liveWhen { it.sleep is SleepState.Timed && (it.sleep as SleepState.Timed).deadlineElapsedMs > deadline }
            assertThat((kept.sleep as SleepState.Timed).deadlineElapsedMs).isEqualTo(clock.elapsed + 15 * 60_000L)
            c.awaitUntil(description = "full volume again") { volume == 1f && isPlaying }

            withLockedScreen {
                clock.elapsed = (kept.sleep as SleepState.Timed).deadlineElapsedMs - 1_000
                c.awaitUntil(description = "fading again") { volume < 0.2f }
                clock.elapsed += 1_000
                val stopped = awaitSleepStop("timed stop after the fade")
                saved(book) { it.stopKind == StopKind.TIMED && it.positionMs in (stopped.positionMs - 100)..(stopped.positionMs + 100) }
            }
            c.play()
            c.awaitUntil(description = "full volume after the stop") { isPlaying && volume == 1f }
            c.pause()
        }
    }

    @Test
    fun a13eEndOfChapterFadesAtDoubleSpeedAndStillStopsOnTheBoundary() {
        val book = TestApp.publishBoundaryBook()
        onMain {
            val c = load(book, 133_000)
            c.setPlaybackSpeed(2f)
            c.setSleep(PagewornCommands.SLEEP_END_OF_SEGMENT)
            withLockedScreen {
                c.play()
                c.awaitUntil(description = "full volume well before the boundary") { isPlaying && currentPosition in 133_000..133_900 && volume == 1f }
                // 3 s of wall time at 2x is the last 6 s of the chapter.
                c.awaitUntil(10_000, "fading in the chapter's last seconds") { currentPosition >= 137_500 && volume < 0.5f }
                // No key is pressed here: a Play or Pause during the fade would keep the book playing.
                val stopped = awaitSleepStop("end of chapter after the fade at 2x")
                assertWithMessage("stopped at ${stopped.positionMs} ms").that(stopped.positionMs in 139_950..140_100).isTrue()
                saved(book) { it.positionMs == 140_000L && it.stopKind == StopKind.EXACT }
            }
        }
    }

    @Test
    fun a13fAPlaySoonAfterAStopRunsTheSameLengthUntilOffIsChosen() {
        val book = TestApp.publishBoundaryBook()
        onMain {
            val c = load(book, 10_000)
            c.setSleep(PagewornCommands.SLEEP_TIMED, 15)
            c.play()
            liveWhen { it.isPlaying }
            clock.advance(15 * 60_000L)
            awaitSleepStop("first timed stop")
            // The automatic window ends: the mode turns off, the stop is still recent.
            container.sleepSettings.choose(null)

            clock.advance(4 * 60_000L)
            c.play()
            val again = liveWhen { it.isPlaying && it.sleep is SleepState.Timed }
            assertThat((again.sleep as SleepState.Timed).minutes).isEqualTo(15)
            clock.advance(15 * 60_000L)
            awaitSleepStop("second timed stop")

            c.setSleep(PagewornCommands.SLEEP_OFF)
            c.play()
            liveWhen { it.isPlaying }
            delay(PlaybackController.TICK_MS * 2)
            assertThat(container.playbackHub.state.value.sleep).isEqualTo(SleepState.Off)
            c.pause()
        }
    }

    @Test
    fun a13hTheWindowBeginningStartsTheTimerOfABookThatIsPlaying() {
        val book = TestApp.publishBoundaryBook()
        val local = java.time.Instant.ofEpochMilli(clock.wall).atZone(java.time.ZoneId.systemDefault())
        val startMinute = (local.hour * 60 + local.minute + 2) % SleepWindow.MINUTES_PER_DAY
        onMain {
            val c = load(book, 10_000)
            c.setSleep(PagewornCommands.SLEEP_TIMED, 20)
            c.setSleep(PagewornCommands.SLEEP_OFF)
            container.sleepSettings.setAutomatic(true, SleepWindow(startMinute, (startMinute + 2) % SleepWindow.MINUTES_PER_DAY))
            c.play()
            liveWhen { it.isPlaying }
            assertThat(container.playbackHub.state.value.sleep).isEqualTo(SleepState.Off)

            clock.advance(3 * 60_000L)
            val armed = liveWhen { it.sleep is SleepState.Timed }
            assertThat((armed.sleep as SleepState.Timed).minutes).isEqualTo(20)
            assertThat(container.sleepSettings.settings.value.enabled).isTrue()

            // The window ending lets the running timer finish.
            clock.advance(2 * 60_000L)
            delay(1_500)
            assertThat(container.sleepSettings.settings.value.enabled).isFalse()
            assertThat(container.playbackHub.state.value.sleep).isEqualTo(armed.sleep)
            c.pause()
        }
    }

    private fun assertEndTarget(expectedMs: Long) {
        val sleep = container.playbackHub.state.value.sleep as SleepState.EndOf
        assertThat(sleep.boundaryMs).isEqualTo(expectedMs)
    }

    private suspend fun assertBoundary(c: MediaController, book: BookKey, boundary: Long, finished: Boolean) {
        c.play()
        // The controller shows a Play at once; the service's own state says when its timer runs.
        liveWhen { it.isPlaying && it.sleep != SleepState.Off }
        assertEndTarget(boundary)
        val stopped = awaitSleepStop("end of segment at $boundary ms")
        c.awaitUntil(description = "paused at the boundary") { !isPlaying && !playWhenReady }
        val observation = "boundary $boundary ms, service stopped at ${stopped.positionMs} ms, controller at ${c.currentPosition} ms"
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("observation", observation) })
        assertWithMessage(observation).that(stopped.positionMs in (boundary - 50)..(boundary + 100)).isTrue()
        val saved = saved(book) { it.positionMs == boundary && it.stopKind == StopKind.EXACT }
        assertThat(saved.finished).isEqualTo(finished)
        assertThat(container.playbackHub.state.value.ended).isEqualTo(finished)
    }
}
