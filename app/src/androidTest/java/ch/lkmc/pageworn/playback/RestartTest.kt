package ch.lkmc.pageworn.playback

import android.net.ConnectivityManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.FakeClock
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.debug.LocalEditions
import ch.lkmc.pageworn.testing.Fixtures
import ch.lkmc.pageworn.testing.HoldingWriter
import ch.lkmc.pageworn.testing.RestartMarker
import ch.lkmc.pageworn.testing.TestApp
import ch.lkmc.pageworn.testing.awaitUntil
import ch.lkmc.pageworn.testing.connectController
import ch.lkmc.pageworn.testing.onMain
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Cases that need a process restart. Each case has a first half that arranges and records the
 * state, and later halves that must run in later instrumentation runs, so that Android starts the
 * app in a new process (see [RestartMarker]). Run them one by one, in name order:
 *
 *     am instrument -w -e class ch.lkmc.pageworn.playback.RestartTest#a10Part1... <runner>
 *     am instrument -w -e class ch.lkmc.pageworn.playback.RestartTest#a10Part2... <runner>
 *
 * In a single run of the whole suite the later halves skip themselves.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RestartTest {
    private val context get() = TestApp.context
    private val container get() = TestApp.container

    private fun item(key: BookKey) = MediaItem.Builder().setMediaId(key.mediaId).build()

    private suspend fun MediaController.playUntil(position: Long, description: String) {
        awaitUntil(30_000, description) { isPlaying && currentPosition >= position }
    }

    private suspend fun MediaController.pauseAndSettle() {
        pause()
        awaitUntil(description = "paused") { !isPlaying }
        // Let the pause checkpoint reach Room.
        delay(300)
    }

    // --- M1 exit check and A10 ----------------------------------------------------------------

    /**
     * Pause A late, play B, then deliver A's delayed position callback. Both places must hold, in
     * this process and (part 2) after a restart.
     */
    @Test
    fun a10Part1PauseALateThenListenToB() {
        TestApp.reset()
        RestartMarker.clear()
        val a = TestApp.publishBoundaryBook()
        val b = TestApp.publishSingleM4b()
        val writer = HoldingWriter(ProgressWriter.of(container.progressStore, container.appScope))
        container.progressWriter = writer
        // A's first periodic sample past 190 s stands for a position callback that arrives late.
        writer.holdIf = { it.key == a && it.positionMs >= 190_000 && it.stopKind == StopKind.ORDINARY }

        val (aPaused, bPaused) = onMain {
            val c = connectController(context)
            c.setMediaItem(item(a), 186_000)
            c.prepare()
            c.play()
            c.awaitUntil(15_000, "A's periodic sample is held") { writer.held.isNotEmpty() }
            writer.holdIf = null
            c.playUntil(196_000, "A past 196 s")
            c.pauseAndSettle()

            // The switch to B checkpoints A once more, at the position the player settled on after
            // the pause; that write is A's place.
            c.setMediaItem(item(b))
            c.prepare()
            c.play()
            c.playUntil(3_000, "B past 3 s")
            c.pauseAndSettle()
            val aPaused = writer.passed.last { it.key == a }.positionMs
            val bPaused = writer.passed.last { it.key == b }.positionMs

            assertThat(writer.held).hasSize(1)
            assertThat(writer.held.single().positionMs).isLessThan(aPaused)
            writer.deliverHeld()
            delay(300)
            c.release()
            aPaused to bPaused
        }

        runBlocking {
            assertWithMessage("A after the delayed callback").that(container.progressStore.read(a)!!.positionMs).isEqualTo(aPaused)
            assertWithMessage("B").that(container.progressStore.read(b)!!.positionMs).isEqualTo(bPaused)
        }
        assertThat(aPaused).isAtLeast(196_000)
        RestartMarker.write("a10", mapOf("a" to aPaused, "b" to bPaused))
    }

    /** After the restart both places are stored, and explicit resume starts each book there. */
    @Test
    fun a10Part2BothPlacesSurviveTheRestart() {
        val marker = RestartMarker.readAfterRestart("a10")
        val a = TestApp.key("boundary-book")
        val b = TestApp.key("single-m4b")
        runBlocking {
            assertThat(container.progressStore.read(a)!!.positionMs).isEqualTo(marker.getValue("a"))
            assertThat(container.progressStore.read(b)!!.positionMs).isEqualTo(marker.getValue("b"))
        }
        onMain {
            val c = connectController(context)
            for ((key, expected) in listOf(a to marker.getValue("a"), b to marker.getValue("b"), a to marker.getValue("a"))) {
                c.setMediaItem(item(key))
                c.prepare()
                c.awaitUntil(description = "${key.itemId} ready") { playbackState == Player.STATE_READY && currentMediaItem?.mediaId == key.mediaId }
                // Explicit resume within 1 s of the place (no rewind: the rest is under five minutes).
                assertWithMessage("start of ${key.itemId}").that(c.currentPosition).isIn(com.google.common.collect.Range.closed(expected - 1_000, expected + 1_000))
                c.play()
                c.awaitUntil(description = "${key.itemId} playing") { isPlaying }
                c.pauseAndSettle()
            }
            c.release()
        }
        RestartMarker.clear()
        TestApp.reset()
    }

    // --- A10b -----------------------------------------------------------------------------------

    /** At 2x, hold the periodic sample from 115 s, seek back to 75 s, pause, deliver the sample. */
    @Test
    fun a10bPart1HeldSampleAfterSeekBack() {
        TestApp.reset()
        RestartMarker.clear()
        val a = TestApp.publishBoundaryBook()
        val writer = HoldingWriter(ProgressWriter.of(container.progressStore, container.appScope))
        container.progressWriter = writer
        writer.holdIf = { it.key == a && it.positionMs >= 115_000 && it.stopKind == StopKind.ORDINARY }

        val stored = onMain {
            val c = connectController(context)
            c.setPlaybackSpeed(2f)
            c.setMediaItem(item(a), 108_000)
            c.prepare()
            c.play()
            c.awaitUntil(15_000, "sample from 115 s is held") { writer.held.isNotEmpty() }
            writer.holdIf = null
            c.seekTo(75_000)
            c.awaitUntil(description = "back at 75 s") { playbackState == Player.STATE_READY && currentPosition in 75_000..76_000 }
            c.pauseAndSettle()
            writer.deliverHeld()
            delay(300)
            c.release()
            runBlocking { container.progressStore.read(a)!!.positionMs }
        }
        // Within the fixture's seek tolerance of 75 s; the held 115 s sample did not land.
        assertThat(stored).isIn(com.google.common.collect.Range.closed(75_000L, 76_000L))
        RestartMarker.write("a10b", mapOf("stored" to stored))
    }

    /**
     * After the restart 75 s is restored. Then resume at 2x and stop the process just before the
     * next scheduled checkpoint: part 3 measures the loss.
     */
    @Test
    fun a10bPart2RestoresThenDiesBeforeACheckpoint() {
        val marker = RestartMarker.readAfterRestart("a10b")
        val a = TestApp.key("boundary-book")
        assertThat(runBlocking { container.progressStore.read(a)!!.positionMs }).isEqualTo(marker.getValue("stored"))
        val writer = HoldingWriter(ProgressWriter.of(container.progressStore, container.appScope))
        container.progressWriter = writer

        val lastPosition = onMain {
            val c = connectController(context)
            c.setPlaybackSpeed(2f)
            c.setMediaItem(item(a))
            c.prepare()
            c.awaitUntil(description = "ready") { playbackState == Player.STATE_READY }
            assertThat(c.currentPosition).isIn(com.google.common.collect.Range.closed(marker.getValue("stored") - 1_000, marker.getValue("stored") + 1_000))
            c.play()
            c.awaitUntil(description = "playing") { isPlaying }
            // Wait for one periodic checkpoint, then stop just before the next one (4 s of media).
            c.awaitUntil(15_000, "a periodic checkpoint") { writer.passed.any { it.listened && it.stopKind == StopKind.ORDINARY } }
            val committed = writer.passed.last().positionMs
            c.awaitUntil(15_000, "just before the next checkpoint") { currentPosition >= committed + 3_500 }
            c.currentPosition
        }
        RestartMarker.write("a10b-death", mapOf("lastPosition" to lastPosition))
        // The instrumentation run ends here; Android kills the process without lifecycle callbacks.
    }

    /** At most 5 s of book time plus the seek tolerance was lost. */
    @Test
    fun a10bPart3LossIsAtMostFiveSeconds() {
        val marker = RestartMarker.readAfterRestart("a10b-death")
        val a = TestApp.key("boundary-book")
        val stored = runBlocking { container.progressStore.read(a)!!.positionMs }
        val lost = marker.getValue("lastPosition") - stored
        assertWithMessage("book time lost").that(lost).isAtMost(5_000L + SEEK_TOLERANCE_MS)
        RestartMarker.clear()
        TestApp.reset()
    }

    // --- A29, process death ---------------------------------------------------------------------

    /** Pause the boundary book at 115 s, then the process dies. */
    @Test
    fun a29Part1PauseAt115() {
        TestApp.reset()
        RestartMarker.clear()
        val clock = FakeClock()
        container.clock = clock
        val a = TestApp.publishBoundaryBook()
        val stopped = onMain {
            val c = connectController(context)
            c.setMediaItem(item(a), 115_000)
            c.prepare()
            c.play()
            c.awaitUntil(description = "playing") { isPlaying }
            c.pauseAndSettle()
            c.release()
            runBlocking { container.progressStore.read(a)!! }
        }
        assertThat(stopped.stopKind).isEqualTo(StopKind.ORDINARY)
        RestartMarker.write("a29", mapOf("position" to stopped.positionMs, "wall" to clock.wall))
    }

    /** After the restart and a 10 minute rest, a headset Play resumes 10 s earlier. */
    @Test
    fun a29Part2HeadsetPlayAfterTenMinutes() {
        val marker = RestartMarker.readAfterRestart("a29")
        val a = TestApp.key("boundary-book")
        container.clock = FakeClock(wall = marker.getValue("wall") + 10 * 60_000L)
        // Before the start the saved place is the stop position (what Downloaded and the player show).
        assertThat(runBlocking { container.progressStore.read(a)!!.positionMs }).isEqualTo(marker.getValue("position"))
        // A media key with no session running: Android asks the last media app to resume playback,
        // as it does for a headset button. A real Bluetooth headset is an owner check.
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cmd media_session dispatch play").close()
        val start = onMain {
            // Suspends rather than blocks: the media button broadcast needs the main thread.
            awaitService()
            val c = connectController(context)
            c.awaitUntil(15_000, "resumed by the media key") { isPlaying && currentMediaItem?.mediaId == a.mediaId }
            val position = c.currentPosition
            c.pause()
            c.release()
            position
        }
        val expected = marker.getValue("position") - 10_000
        assertThat(start).isIn(com.google.common.collect.Range.closed(expected - 300, expected + 1_000))
        RestartMarker.clear()
        TestApp.reset()
    }

    @Test
    fun a12bPart1RestAfterATimedStop() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sleepRestart") == "true")
        val clock = FakeClock()
        TestApp.reset(clock)
        RestartMarker.clear()
        val key = TestApp.key("long-vbr")
        val file = Fixtures.install("fmt-mp3-vbr-long").single()
        runBlocking {
            LocalEditions.publish(container, key, "Long VBR", null,
                listOf(LocalEditions.Track(file, TestApp.probeDuration(file))))
        }
        val stopped = onMain {
            val c = connectController(context)
            try {
                c.setMediaItem(item(key), 600_000)
                c.prepare()
                c.play()
                withTimeout(10_000) { container.playbackHub.state.first { it.isPlaying } }
                val command = SessionCommand(PagewornCommands.SET_SLEEP, Bundle.EMPTY)
                val args = Bundle().apply {
                    putString(PagewornCommands.ARG_SLEEP_MODE, PagewornCommands.SLEEP_TIMED)
                    putInt(PagewornCommands.ARG_SLEEP_MINUTES, 15)
                }
                assertThat(c.sendCustomCommand(command, args).await().resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
                clock.advance(15 * 60_000L)
                val progress = withTimeout(10_000) {
                    container.database.progress().observe(key.profileId, key.itemId)
                        .first { it?.stopKind == StopKind.TIMED }
                }
                checkNotNull(progress)
            } finally {
                c.release()
            }
        }
        assertThat(stopped.positionMs in 600_000..601_000).isTrue()
        RestartMarker.write("a12b", mapOf("position" to stopped.positionMs, "wall" to clock.wall))
    }

    @Test
    fun a12bPart2TimedStopRewindsAfterEightHoursAndProcessDeath() {
        val marker = RestartMarker.readAfterRestart("a12b")
        val key = TestApp.key("long-vbr")
        container.clock = FakeClock(wall = marker.getValue("wall") + 8 * 60 * 60_000L)
        val stop = checkNotNull(runBlocking { container.progressStore.read(key) })
        assertThat(stop.positionMs).isEqualTo(marker.getValue("position"))
        assertThat(stop.stopKind).isEqualTo(StopKind.TIMED)
        onMain {
            val c = connectController(context)
            try {
                c.setMediaItem(item(key))
                c.prepare()
                c.play()
                val start = withTimeout(10_000) { container.playbackHub.state.first { it.isPlaying && it.sleep is SleepState.Timed } }
                val expected = stop.positionMs - 300_000
                assertThat(start.positionMs in (expected - 100)..(expected + 350)).isTrue()
                // Sleep mode outlives the process; the start runs a fresh timer of its length.
                assertThat((start.sleep as SleepState.Timed).minutes).isEqualTo(15)
                c.pauseAndSettle()
            } finally {
                c.release()
            }
        }
        RestartMarker.clear()
        TestApp.reset()
    }

    @Test
    fun a22OfflinePlaybackKeepsItsPlaceAndSpeedAfterProcessDeath() {
        val marker = RestartMarker.readAfterRestart("offline")
        val book = TestApp.key("boundary-book")
        val position = marker.getValue("position")
        container.clock = FakeClock(wall = marker.getValue("wall"))
        assertThat(context.getSystemService(ConnectivityManager::class.java).activeNetwork).isNull()
        assertThat(runBlocking { container.progressStore.read(book)?.positionMs }).isEqualTo(position)
        onMain {
            val c = connectController(context)
            try {
                c.setMediaItem(item(book))
                c.prepare()
                c.awaitUntil(description = "saved offline place after process death") {
                    playbackState == Player.STATE_READY && currentPosition in (position - 30)..(position + 30)
                }
                assertThat(c.playbackParameters.speed).isEqualTo(1.5f)
                assertThat(container.playbackHub.state.value.sleep).isEqualTo(SleepState.Off)
            } finally {
                c.release()
            }
        }
        assertThat(container.networkDiagnostics.entries()).isEmpty()
        RestartMarker.clear()
        TestApp.reset()
    }

    // --- A16e, resumption after process death ------------------------------------------------

    /** Pause the boundary book at 115 s at 1.5x with 15-minute sleep mode on; then the process dies. */
    @Test
    fun a16ePart1PauseAt115At1_5xWithATimer() {
        TestApp.reset()
        RestartMarker.clear()
        val clock = FakeClock()
        container.clock = clock
        val a = TestApp.publishBoundaryBook()
        val stopped = onMain {
            val c = connectController(context)
            c.setPlaybackSpeed(A16E_SPEED)
            c.setMediaItem(item(a), 115_000)
            c.prepare()
            c.awaitUntil(description = "ready") { playbackState == Player.STATE_READY }
            val sleep = Bundle().apply {
                putString(PagewornCommands.ARG_SLEEP_MODE, PagewornCommands.SLEEP_TIMED)
                putInt(PagewornCommands.ARG_SLEEP_MINUTES, 15)
            }
            val armed = c.sendCustomCommand(SessionCommand(PagewornCommands.SET_SLEEP, Bundle.EMPTY), sleep).await()
            assertThat(armed.resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
            c.play()
            c.awaitUntil(description = "playing") { isPlaying }
            // The controller masks Play at once; the service's state shows when its timer runs.
            withTimeout(5_000) { container.playbackHub.state.first { it.isPlaying && it.sleep is SleepState.Timed } }
            c.pauseAndSettle()
            // The pause ends the timer; the mode stays on.
            assertThat(container.playbackHub.state.value.sleep).isEqualTo(SleepState.Off)
            assertThat(container.sleepSettings.settings.value.enabled).isTrue()
            c.release()
            runBlocking { container.progressStore.read(a)!! }
        }
        RestartMarker.write("a16e", mapOf("position" to stopped.positionMs, "wall" to clock.wall))
    }

    /**
     * In a new process, a media-key Play resumes the book at its place and speed with a fresh timer
     * of the sleep mode's length and without opening the activity, through the service started in the foreground. A press on
     * Bluetooth headphones and the resumption control after a phone restart are owner checks.
     */
    @Test
    fun a16ePart2MediaPlayResumesAtThePlaceAndSpeedWithAFreshTimer() {
        val marker = RestartMarker.readAfterRestart("a16e")
        val a = TestApp.key("boundary-book")
        container.clock = FakeClock(wall = marker.getValue("wall") + 60_000L)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("cmd media_session dispatch play").close()
        val resumed = onMain {
            awaitService()
            val c = connectController(context)
            c.awaitUntil(15_000, "resumed by the media key") { isPlaying && currentMediaItem?.mediaId == a.mediaId }
            val state = container.playbackHub.state.value
            val resumedActivities = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            val service = shell("dumpsys activity services ${context.packageName}/.playback.PlaybackService")
            val resumed = Resumed(state.positionMs, c.playbackParameters.speed, state.sleep, resumedActivities.size, service.contains("isForeground=true"))
            c.pause()
            c.release()
            resumed
        }
        val place = marker.getValue("position")
        assertThat(resumed.positionMs).isIn(com.google.common.collect.Range.closed(place - 300, place + 1_500))
        assertThat(resumed.speed).isEqualTo(A16E_SPEED)
        assertThat((resumed.sleep as SleepState.Timed).minutes).isEqualTo(15)
        assertWithMessage("resumed activities").that(resumed.resumedActivities).isEqualTo(0)
        assertWithMessage("the service runs in the foreground").that(resumed.foreground).isTrue()
        RestartMarker.clear()
        TestApp.reset()
    }

    private data class Resumed(val positionMs: Long, val speed: Float, val sleep: SleepState, val resumedActivities: Int, val foreground: Boolean)

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private suspend fun awaitService() {
        repeat(100) {
            if (container.playbackHub.state.value.key != null) return
            delay(100)
        }
    }

    companion object {
        /** Largest audible seek error the format matrix measured on the targets (Opus on API 37). */
        const val SEEK_TOLERANCE_MS = 100L

        const val A16E_SPEED = 1.5f
    }
}
