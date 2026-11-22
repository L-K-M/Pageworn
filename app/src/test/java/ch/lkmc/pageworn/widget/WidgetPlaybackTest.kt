package ch.lkmc.pageworn.widget

import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import androidx.media3.session.MediaLibraryService
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.pageworn.PagewornApp
import ch.lkmc.pageworn.PhoneBooks
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.playback.PlaybackService
import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * A32: the widgets' keys through the app's real connection, session, service and player.
 * Android's background-start rules are A32b's, on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetPlaybackTest {
    private val app = ApplicationProvider.getApplicationContext<PagewornApp>()
    private val container = app.container
    private val books = PhoneBooks(container)
    private lateinit var service: ServiceController<PlaybackService>
    private lateinit var first: BookKey
    private lateinit var second: BookKey

    @Before
    fun setUp() = runBlocking<Unit> {
        val audio = silence()
        first = books.download("first", durationMs = LENGTH_MS, audio = audio, extension = "wav")
        second = books.download("second", durationMs = LENGTH_MS, audio = audio, extension = "wav")
        service = Robolectric.buildService(PlaybackService::class.java).create()
        val name = ComponentName(app, PlaybackService::class.java)
        val binder = service.get().onBind(Intent(MediaLibraryService.SERVICE_INTERFACE).setComponent(name))
        shadowOf(app).setComponentNameAndServiceForBindService(name, binder)
    }

    @After
    fun tearDown() {
        container.playerConnection.release()
        shadowOf(Looper.getMainLooper()).idle()
        service.destroy()
    }

    @Test
    fun playStartsTheServiceInTheForegroundAndPauseStopsTheBook() = runBlocking<Unit> {
        books.place(first, 60_000, LENGTH_MS)

        tap(WidgetCommand.PLAY, first)
        await("playing") { live().key == first && live().playbackRequested }
        await("foreground") { shadowOf(service.get()).lastForegroundNotification != null }

        tap(WidgetCommand.PAUSE, first)
        await("paused") { live().key == first && !live().playbackRequested }
    }

    @Test
    fun aSkipMovesItsOwnBookWhileAnotherStarts() = runBlocking<Unit> {
        books.place(first, 60_000, LENGTH_MS)
        books.place(second, 90_000, LENGTH_MS)
        perform(WidgetCommand.PLAY, first)
        await("first playing") { live().key == first && live().playbackRequested }

        perform(WidgetCommand.PLAY, second)
        perform(WidgetCommand.SKIP_BACK, first)

        await("second playing") { live().key == second && live().playbackRequested && live().starting == null }
        await("first moved") { (container.progressStore.read(first)?.positionMs ?: Long.MAX_VALUE) <= 60_000 - SKIP_BACK_MS + SLACK_MS }
        assertThat(live().positionMs).isAtLeast(90_000 - SLACK_MS)
    }

    @Test
    fun aSkipOfTheStartingBookMovesWhereItPlays() = runBlocking<Unit> {
        books.place(first, 60_000, LENGTH_MS)

        perform(WidgetCommand.PLAY, first)
        perform(WidgetCommand.SKIP_BACK, first)

        await("playing from the moved place") {
            live().key == first && live().playbackRequested && live().positionMs in (45_000 - SLACK_MS)..(45_000 + SLACK_MS)
        }
    }

    @Test
    fun aSkipOfTheBookTakingOverMovesWhereItPlays() = runBlocking<Unit> {
        books.place(first, 60_000, LENGTH_MS)
        books.place(second, 90_000, LENGTH_MS)
        perform(WidgetCommand.PLAY, first)
        await("first playing") { live().key == first && live().playbackRequested }

        perform(WidgetCommand.PLAY, second)
        perform(WidgetCommand.SKIP_BACK, second)

        await("second playing from the moved place") {
            live().key == second && live().playbackRequested && live().positionMs in (75_000 - SLACK_MS)..(75_000 + SLACK_MS)
        }
    }

    @Test
    fun playAfterASkipStartsFromTheMovedPlace() = runBlocking<Unit> {
        books.place(first, 60_000, LENGTH_MS)

        perform(WidgetCommand.SKIP_BACK, first)
        perform(WidgetCommand.PLAY, first)

        await("playing from the moved place") {
            live().key == first && live().playbackRequested && live().positionMs in (45_000 - SLACK_MS)..(45_000 + SLACK_MS)
        }
    }

    private fun live() = container.playbackHub.state.value

    private fun tap(command: WidgetCommand, key: BookKey) = WidgetTap(command, key).pendingIntent(app).send()

    private fun perform(command: WidgetCommand, key: BookKey) = container.homeWidgets.perform(WidgetTap(command, key))

    private suspend fun await(what: String, condition: suspend () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_NS
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what: ${live()}" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
        }
    }

    /** A WAV of silence, which the player reads without a decoder. */
    private fun silence(): ByteArray {
        val data = (LENGTH_MS / 1000 * RATE * BYTES_PER_SAMPLE).toInt()
        return ByteBuffer.allocate(WAV_HEADER + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(WAV_HEADER - 8 + data); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(RATE); putInt(RATE * BYTES_PER_SAMPLE)
            putShort(BYTES_PER_SAMPLE.toShort()); putShort(16); put("data".toByteArray()); putInt(data)
        }.array()
    }

    private companion object {
        const val LENGTH_MS = 180_000L
        const val RATE = 8_000
        const val BYTES_PER_SAMPLE = 2
        const val WAV_HEADER = 44
        const val SKIP_BACK_MS = 15_000L
        const val SLACK_MS = 4_000L
        const val WAIT_NS = 10_000_000_000L
        const val POLL_MS = 10L
    }
}
