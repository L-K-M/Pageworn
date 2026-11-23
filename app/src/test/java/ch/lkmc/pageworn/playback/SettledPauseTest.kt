package ch.lkmc.pageworn.playback

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.FakeClock
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
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * A10: a pause's place follows the player only until it settles. A seek after it is another matter,
 * and one that fails keeps the place saved before it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettledPauseTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val key = BookKey("profile", "item")
    private val clock = FakeClock()
    private val ticks = TestScope()
    private val player = ScriptedPlayer()
    private val hub = PlaybackHub()
    private val writes = mutableListOf<Long>()
    private lateinit var db: PagewornDatabase
    private lateinit var controller: PlaybackController

    @Before
    fun setUp() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(context, PagewornDatabase::class.java).build()
        db.profiles().upsert(ProfileEntity(key.profileId, "account", "Listener", null, null, "2.38.0", clock.wall))
        val files = EditionFiles(folder.newFolder("media")).also { it.ensureRoots() }
        val staged = files.staging("generation").also { it.mkdirs() }
        val tracks = (0..1).map { ordinal ->
            val name = "%03d.mp3".format(ordinal)
            File(staged, name).writeBytes(byteArrayOf(1))
            ManifestTrack(ordinal, "file-$ordinal", 1, 1, name, ordinal * TRACK_MS, TRACK_MS, extension = "mp3")
        }
        val editions = EditionStore(db, files, clock, CoroutineScope(SupervisorJob()))
        editions.publish(
            EditionManifest(generationId = "generation", jobId = null, profileId = key.profileId, itemId = key.itemId, fingerprint = "print", title = "Book", tracks = tracks, chapters = emptyList()),
        )
        controller = PlaybackController(
            exo = player.exo,
            editions = editions,
            progress = ProgressStore(db.progress(), clock),
            settings = SettingsStore(context),
            sleepSettings = SleepSettingsStore(context, clock),
            clock = clock,
            hub = hub,
            writer = ProgressWriter { _, _, position, _, _, _, _, _ -> writes += position },
            items = BookItems(context),
            bookSources = BookMediaSourceFactory(DefaultDataSource.Factory(context)),
            scope = ticks,
        )
        player.item = checkNotNull(controller.resolve(key, null)).first
        player.playing(controller, atMs = 0)
        player.pausing(controller, atMs = PAUSED_MS)
    }

    @After
    fun tearDown() {
        controller.release()
        db.close()
    }

    @Test
    fun a10ThePauseMovesWhereThePlayerSettles() {
        player.position = SETTLED_MS

        tick()

        assertThat(writes.last()).isEqualTo(SETTLED_MS)
    }

    @Test
    fun a10dASeekThatFailsWhilePausedKeepsThePlaceBeforeIt() {
        tick()

        player.seeking(controller, toMs = IN_FAILING_TRACK_MS)
        player.failing(controller)
        tick()

        assertThat(writes.last()).isEqualTo(PAUSED_MS)
    }

    @Test
    fun a10dASeekThatFailsWhilePausedShowsThePlaceItKept() {
        tick()

        player.seeking(controller, toMs = IN_FAILING_TRACK_MS)
        player.failing(controller)

        assertThat(hub.state.value.positionMs).isEqualTo(PAUSED_MS)
    }

    @Test
    fun a10dASeekThatFailsAfterPlayKeepsThePlaceBeforeIt() {
        tick()

        player.seeking(controller, toMs = IN_FAILING_TRACK_MS)
        player.wantsToPlay = true
        player.failing(controller)
        tick()

        assertThat(writes.last()).isEqualTo(PAUSED_MS)
    }

    private fun tick() {
        ticks.testScheduler.advanceTimeBy(PlaybackController.TICK_MS + 1)
    }

    /** The few answers of an ExoPlayer that the controller reads, scripted by the test. */
    private class ScriptedPlayer {
        var item: MediaItem? = null
        var position = 0L
        var wantsToPlay = false
        private var audible = false
        private var state = Player.STATE_READY
        private var error: ExoPlaybackException? = null

        val exo = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, args ->
            when (method.name) {
                "addListener", "removeListener", "setPlaybackSpeed", "setVolume" -> null
                "getVolume" -> 1f
                "setPlayWhenReady" -> { wantsToPlay = args[0] as Boolean; null }
                "getPlayWhenReady" -> wantsToPlay
                "isPlaying" -> audible
                "getPlaybackState" -> state
                "getPlayerError" -> error
                "getCurrentPosition" -> position
                "getCurrentMediaItem" -> item
                "getPlaybackParameters" -> PlaybackParameters.DEFAULT
                "getPlaybackSuppressionReason" -> Player.PLAYBACK_SUPPRESSION_REASON_NONE
                "isCommandAvailable" -> true
                "getApplicationLooper" -> Looper.getMainLooper()
                else -> error("The controller asked the player for ${method.name}")
            }
        } as ExoPlayer

        fun playing(listener: Player.Listener, atMs: Long) {
            position = atMs
            wantsToPlay = true
            audible = true
            listener.onIsPlayingChanged(true)
        }

        fun pausing(listener: Player.Listener, atMs: Long) {
            position = atMs
            wantsToPlay = false
            audible = false
            listener.onIsPlayingChanged(false)
        }

        fun seeking(listener: Player.Listener, toMs: Long) {
            val from = positionInfo(position)
            position = toMs
            state = Player.STATE_BUFFERING
            listener.onPositionDiscontinuity(from, positionInfo(toMs), Player.DISCONTINUITY_REASON_SEEK)
        }

        fun failing(listener: Player.Listener) {
            val failure = ExoPlaybackException.createForSource(IOException("unreadable track"), PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
            error = failure
            state = Player.STATE_IDLE
            listener.onPlayerError(failure)
        }

        private fun positionInfo(ms: Long) = Player.PositionInfo(null, 0, item, null, 0, ms, ms, -1, -1)
    }

    private companion object {
        const val TRACK_MS = 90_000L
        const val PAUSED_MS = 30_000L
        const val SETTLED_MS = 30_080L
        const val IN_FAILING_TRACK_MS = 120_000L
    }
}
