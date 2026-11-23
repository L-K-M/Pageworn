package ch.lkmc.pageworn.playback

import android.os.Looper
import android.provider.Settings
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.FakeClock
import ch.lkmc.pageworn.core.progress.StopKind
import ch.lkmc.pageworn.core.sleep.SleepLength
import ch.lkmc.pageworn.core.sleep.SleepWindow
import ch.lkmc.pageworn.core.timeline.Division
import ch.lkmc.pageworn.data.EditionFiles
import ch.lkmc.pageworn.data.EditionManifest
import ch.lkmc.pageworn.data.EditionStore
import ch.lkmc.pageworn.data.ManifestTrack
import ch.lkmc.pageworn.data.ProgressStore
import ch.lkmc.pageworn.data.SettingsStore
import ch.lkmc.pageworn.data.SleepSettingsStore
import ch.lkmc.pageworn.data.db.PagewornDatabase
import ch.lkmc.pageworn.data.db.ProfileEntity
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sleep mode's timed timers on a scripted player (sleep behavior in docs/05-playback-and-formats.md):
 * every start runs a fresh timer while the mode is on, a pause ends it, the volume fades over the
 * last seconds, Play or Pause during the fade keeps the book going, and a Play soon after a stop
 * runs the same length again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepTimerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val key = BookKey("profile", "item")
    private val other = BookKey("profile", "other")
    private val clock = FakeClock()
    private val zone = ZoneId.of("Europe/Zurich")
    private val ticks = TestScope()
    private val player = ScriptedPlayer()
    private val hub = PlaybackHub()
    private val writes = mutableListOf<Pair<Long, StopKind>>()
    private lateinit var db: PagewornDatabase
    private lateinit var files: EditionFiles
    private lateinit var editions: EditionStore
    private lateinit var progress: ProgressStore
    private lateinit var sleep: SleepSettingsStore
    private lateinit var controller: PlaybackController

    @Before
    fun setUp() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 1)
        db = Room.inMemoryDatabaseBuilder(context, PagewornDatabase::class.java).build()
        db.profiles().upsert(ProfileEntity(key.profileId, "account", "Listener", null, null, "2.38.0", clock.wall))
        files = EditionFiles(folder.newFolder("media")).also { it.ensureRoots() }
        editions = EditionStore(db, files, clock)
        progress = ProgressStore(db.progress(), clock)
        publish(key.itemId, "generation")
        sleep = SleepSettingsStore(context, clock) { zone }
        controller = PlaybackController(
            exo = player.exo,
            editions = editions,
            progress = progress,
            settings = SettingsStore(context),
            sleepSettings = sleep,
            clock = clock,
            hub = hub,
            writer = ProgressWriter { _, _, position, _, _, kind, _, _ -> writes += position to kind },
            items = BookItems(context),
            bookSources = BookMediaSourceFactory(DefaultDataSource.Factory(context)),
            scope = ticks,
        )
        player.item = checkNotNull(controller.resolve(key, null)).first
        ticks.testScheduler.runCurrent()
    }

    @After
    fun tearDown() {
        controller.release()
        db.close()
    }

    @Test
    fun withTheModeOffAStartRunsNoTimer() {
        start()

        assertThat(hub.state.value.sleep).isEqualTo(SleepState.Off)
    }

    @Test
    fun releasingTheControllerStopsItsWorkWithoutCancellingTheScope() {
        val parent = checkNotNull(ticks.coroutineContext[Job])

        controller.release()
        ticks.testScheduler.runCurrent()

        assertThat(parent.isActive).isTrue()
        assertThat(parent.children.toList()).isEmpty()
    }

    @Test
    fun withTheModeOnEveryStartRunsTheFullLength() {
        sleep.choose(SleepLength.Minutes(30))

        start()

        assertThat(hub.state.value.sleep).isEqualTo(SleepState.Timed(30, clock.elapsed + 30 * MINUTE))
        assertThat(hub.state.value.sleepRemainingMs).isEqualTo(30 * MINUTE)
    }

    @Test
    fun aPauseEndsTheTimerAndTheNextPlayRestartsTheFullLength() {
        sleep.choose(SleepLength.Minutes(30))
        start()
        elapse(10 * MINUTE)

        player.pause()
        assertThat(hub.state.value.sleep).isEqualTo(SleepState.Off)

        elapse(MINUTE)
        start()
        assertThat(hub.state.value.sleep).isEqualTo(SleepState.Timed(30, clock.elapsed + 30 * MINUTE))
    }

    @Test
    fun theVolumeFadesOverTheLastThreeSecondsAndTheStopIsTimed() {
        sleep.choose(SleepLength.Minutes(1))
        start()

        elapse(MINUTE - 4_000)
        assertThat(player.volume).isEqualTo(1f)

        elapse(2_500)
        // 1.5 s of 3 s left: the volume follows the square of the share left.
        assertThat(player.volume).isWithin(0.08f).of(0.25f)
        assertThat(player.wantsToPlay).isTrue()

        elapse(1_600)
        assertThat(player.wantsToPlay).isFalse()
        assertThat(player.volume).isLessThan(0.05f)
        assertThat(hub.state.value.sleep).isEqualTo(SleepState.Off)
        assertThat(writes.last().second).isEqualTo(StopKind.TIMED)

        // The next start is heard at full volume.
        controller.beforePlay()
        assertThat(player.volume).isEqualTo(1f)
    }

    @Test
    fun playOrPauseDuringTheFadeKeepsPlayingWithAFreshTimer() {
        sleep.choose(SleepLength.Minutes(1))
        start()
        assertThat(controller.keepPlayingDuringFade()).isFalse()

        elapse(MINUTE - 1_000)
        assertThat(player.volume).isLessThan(1f)

        assertThat(controller.keepPlayingDuringFade()).isTrue()
        assertThat(player.volume).isEqualTo(1f)
        assertThat(player.wantsToPlay).isTrue()
        assertThat(hub.state.value.sleep).isEqualTo(SleepState.Timed(1, clock.elapsed + MINUTE))

        elapse(2_000)
        assertThat(player.wantsToPlay).isTrue()
        assertThat(player.volume).isEqualTo(1f)
    }

    @Test
    fun aPlaySoonAfterAStopRunsTheSameLengthEvenWithTheModeOff() {
        sleep.choose(SleepLength.Minutes(1))
        start()
        elapse(MINUTE + 100)
        assertThat(player.wantsToPlay).isFalse()
        // The mode turns off meanwhile, as when the automatic window ends.
        sleep.choose(null)

        elapse(4 * MINUTE)
        start()

        assertThat(hub.state.value.sleep).isEqualTo(SleepState.Timed(1, clock.elapsed + MINUTE))
    }

    @Test
    fun aPlayLongAfterAStopRunsNoTimerWithTheModeOff() {
        sleep.choose(SleepLength.Minutes(1))
        start()
        elapse(MINUTE + 100)
        sleep.choose(null)

        elapse(6 * MINUTE)
        start()

        assertThat(hub.state.value.sleep).isEqualTo(SleepState.Off)
    }

    @Test
    fun choosingOffForgetsTheStop() {
        sleep.choose(SleepLength.Minutes(1))
        start()
        elapse(MINUTE + 100)

        controller.setSleep(null)
        start()

        assertThat(hub.state.value.sleep).isEqualTo(SleepState.Off)
    }

    @Test
    fun choosingALengthWhilePlayingStartsItAtOnce() {
        start()

        controller.setSleep(SleepLength.Minutes(15))

        assertThat(hub.state.value.sleep).isEqualTo(SleepState.Timed(15, clock.elapsed + 15 * MINUTE))
        assertThat(sleep.settings.value.enabled).isTrue()
    }

    @Test
    fun choosingOffDuringTheFadeRestoresTheVolume() {
        sleep.choose(SleepLength.Minutes(1))
        start()
        elapse(MINUTE - 1_000)

        controller.setSleep(null)

        assertThat(player.volume).isEqualTo(1f)
        assertThat(hub.state.value.sleep).isEqualTo(SleepState.Off)
        elapse(2_000)
        assertThat(player.wantsToPlay).isTrue()
    }

    @Test
    fun theWindowBeginningWhileABookPlaysStartsItsTimer() {
        val local = Instant.ofEpochMilli(clock.wall).atZone(zone)
        val startMinute = (local.hour * 60 + local.minute + 2) % SleepWindow.MINUTES_PER_DAY
        sleep.choose(SleepLength.Minutes(20))
        sleep.choose(null)
        sleep.setAutomatic(true, SleepWindow(startMinute, (startMinute + 60) % SleepWindow.MINUTES_PER_DAY))
        start()
        assertThat(hub.state.value.sleep).isEqualTo(SleepState.Off)

        elapse(3 * MINUTE)

        assertThat(hub.state.value.sleep).isInstanceOf(SleepState.Timed::class.java)
        val timer = hub.state.value.sleep as SleepState.Timed
        assertThat(timer.minutes).isEqualTo(20)
        assertThat(sleep.settings.value.enabled).isTrue()
    }

    @Test
    fun aSwitchToABookThatCannotOpenKeepsThePlayingBooksTimer() = runBlocking {
        sleep.choose(SleepLength.Minutes(30))
        start()
        elapse(5 * MINUTE)
        val timer = hub.state.value.sleep

        // A car or Bluetooth controller can ask the session for a book that is not downloaded.
        val savedBefore = writes.size
        assertThat(controller.resolve(BookKey("profile", "missing"), null)).isNull()
        ticks.testScheduler.runCurrent()

        // The playing book is not checkpointed as if it had been left.
        assertThat(writes).hasSize(savedBefore)
        assertThat(controller.sleep).isEqualTo(timer)
        assertThat(hub.state.value.sleep).isEqualTo(timer)
        assertThat(hub.state.value.sleepRemainingMs).isEqualTo(25 * MINUTE)
        assertThat(hub.state.value.isPlaying).isTrue()
        assertThat(player.wantsToPlay).isTrue()
    }

    @Test
    fun aSwitchToADamagedBookKeepsAnEndOfTrackTimer() = runBlocking {
        player.position = LAST_TRACK_MS
        start()
        controller.setSleep(SleepLength.EndOfSegment)
        val timer = controller.sleep
        assertThat(timer).isEqualTo(SleepState.EndOf(Division.TRACKS, 2 * TRACK_MS, true))

        // The damaged edition turns Repair needed when the open checks its files, and the open fails.
        publish(other.itemId, "other-generation")
        File(files.published("other-generation"), "000.mp3").delete()
        val savedBefore = writes.size
        assertThat(controller.resolve(other, null)).isNull()
        ticks.testScheduler.runCurrent()

        assertThat(writes).hasSize(savedBefore)
        assertThat(controller.sleep).isEqualTo(timer)
        assertThat(hub.state.value.sleep).isEqualTo(timer)
        assertThat(hub.state.value.sleepRemainingMs).isEqualTo(2 * TRACK_MS - LAST_TRACK_MS)
        assertThat(player.wantsToPlay).isTrue()
    }

    @Test
    fun aSwitchWaitingForAContinuityChoiceKeepsThePlayingBooksTimer() = runBlocking {
        sleep.choose(SleepLength.Minutes(30))
        start()
        publish(other.itemId, "other-generation")
        progress.saveOutsideSession(other, 0, 2 * TRACK_MS, "old-print", StopKind.EXACT)
        progress.requireContinuityChoice(other, "print-other")

        // The book opens only after the listener chooses where to start, so the session refuses it.
        val savedBefore = writes.size
        assertThat(controller.resolve(other, null)).isNull()
        ticks.testScheduler.runCurrent()

        assertThat(writes).hasSize(savedBefore)

        assertThat(controller.sleep).isEqualTo(SleepState.Timed(30, clock.elapsed + 30 * MINUTE))
        assertThat(hub.state.value.sleep).isEqualTo(controller.sleep)
        assertThat(hub.state.value.sleepRemainingMs).isEqualTo(30 * MINUTE)
        assertThat(player.wantsToPlay).isTrue()
    }

    @Test
    fun anAcceptedSwitchEndsTheOutgoingBooksTimer() = runBlocking {
        sleep.choose(SleepLength.Minutes(30))
        start()
        publish(other.itemId, "other-generation")
        assertThat(controller.sleep).isInstanceOf(SleepState.Timed::class.java)

        val resolved = controller.resolve(other, null)

        assertThat(resolved).isNotNull()
        assertThat(controller.loadedKey).isEqualTo(other)
        assertThat(controller.sleep).isEqualTo(SleepState.Off)
        assertThat(hub.state.value.sleep).isEqualTo(SleepState.Off)
        assertThat(hub.state.value.sleepRemainingMs).isNull()
    }

    /** Publishes a two-track edition of [itemId] like the book the controller starts on. */
    private suspend fun publish(itemId: String, generationId: String) {
        val staged = files.staging(generationId).also { it.mkdirs() }
        val tracks = (0..1).map { ordinal ->
            val name = "%03d.mp3".format(ordinal)
            File(staged, name).writeBytes(byteArrayOf(1))
            ManifestTrack(ordinal, "file-$ordinal", 1, 1, name, ordinal * TRACK_MS, TRACK_MS, extension = "mp3")
        }
        editions.publish(
            EditionManifest(generationId = generationId, jobId = null, profileId = key.profileId, itemId = itemId, fingerprint = "print-$itemId", title = "Book", tracks = tracks, chapters = emptyList()),
        )
    }

    private fun start() {
        controller.beforePlay()
        player.play()
        ticks.testScheduler.runCurrent()
    }

    /** Moves both clocks on in small steps, so ticks and the fade see time pass as on a phone. */
    private fun elapse(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(STEP_MS, left)
            clock.advance(step)
            ticks.testScheduler.advanceTimeBy(step)
            ticks.testScheduler.runCurrent()
            left -= step
        }
    }

    /** The few answers of an ExoPlayer that the controller reads, scripted by the test. */
    private class ScriptedPlayer {
        var item: MediaItem? = null
        var wantsToPlay = false
        var volume = 1f
        var position = POSITION_MS
        private var audible = false
        private var listener: Player.Listener? = null

        val exo = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, args ->
            when (method.name) {
                "addListener" -> { listener = args[0] as Player.Listener; null }
                // A resume rewind seeks; this player stays where it is.
                "removeListener", "setPlaybackSpeed", "seekTo" -> null
                "pause" -> { pause(); null }
                "setPlayWhenReady" -> { if (args[0] as Boolean) play() else pause(); null }
                "getPlayWhenReady" -> wantsToPlay
                "isPlaying" -> audible
                "getVolume" -> volume
                "setVolume" -> { volume = args[0] as Float; null }
                "getPlaybackState" -> Player.STATE_READY
                "getPlayerError" -> null
                "getCurrentPosition" -> position
                "getCurrentMediaItem" -> item
                "getPlaybackParameters" -> PlaybackParameters.DEFAULT
                "getPlaybackSuppressionReason" -> Player.PLAYBACK_SUPPRESSION_REASON_NONE
                "isCommandAvailable" -> true
                "getApplicationLooper" -> Looper.getMainLooper()
                else -> error("The controller asked the player for ${method.name}")
            }
        } as ExoPlayer

        fun play() {
            if (wantsToPlay) return
            wantsToPlay = true
            audible = true
            listener?.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            listener?.onIsPlayingChanged(true)
        }

        fun pause() {
            if (!wantsToPlay) return
            wantsToPlay = false
            audible = false
            listener?.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            listener?.onIsPlayingChanged(false)
        }
    }

    private companion object {
        const val TRACK_MS = 90_000L
        const val POSITION_MS = 30_000L
        const val LAST_TRACK_MS = 150_000L
        const val MINUTE = 60_000L
        const val STEP_MS = 50L
    }
}
