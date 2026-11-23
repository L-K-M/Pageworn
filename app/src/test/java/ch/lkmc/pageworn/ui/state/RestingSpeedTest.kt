package ch.lkmc.pageworn.ui.state

import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.pageworn.AppContainer
import ch.lkmc.pageworn.PagewornApp
import ch.lkmc.pageworn.PhoneBooks
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.playback.LivePlayback
import ch.lkmc.pageworn.ui.model.PlayerUiState
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A22: speed persists, and the player shows the speed the next Play will use. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestingSpeedTest {
    private lateinit var container: AppContainer
    private lateinit var key: BookKey
    private lateinit var player: PlayerViewModel

    @Before
    fun setUp() {
        container = ApplicationProvider.getApplicationContext<PagewornApp>().container
        key = runBlocking { PhoneBooks(container).download("orchard") }
        container.settings.setLastActiveBook(key)
    }

    @After
    fun tearDown() {
        player.viewModelScope.cancel()
    }

    @Test
    fun a22AtRestThePlayerShowsTheStoredSpeed() {
        container.settings.setSpeed(1.5f)
        // No playback service runs, so the hub holds its empty state at 1×.
        player = PlayerViewModel(container)

        assertThat(await("the resting book") { it.face.key == key }.speed).isEqualTo(1.5f)

        container.settings.setSpeed(1.25f)
        await("the newly chosen speed") { it.speed == 1.25f }
    }

    @Test
    fun theLivePlayersSpeedWinsForTheBookItHolds() {
        container.settings.setSpeed(1.5f)
        player = PlayerViewModel(container)
        val resting = await("the resting book") { it.face.key == key }

        // The service publishes the player's own multiplier while it holds the book.
        container.playbackHub.publish(LivePlayback(key = key, positionMs = resting.positionMs, speed = 2f, held = true))

        await("the live speed") { it.speed == 2f }
    }

    private fun await(what: String, condition: (PlayerUiState) -> Boolean): PlayerUiState {
        repeat(WAIT_STEPS) {
            shadowOf(Looper.getMainLooper()).idle()
            player.player.value?.let { if (condition(it)) return it }
            Thread.sleep(WAIT_STEP_MS)
        }
        assertWithMessage("$what never showed: ${player.player.value}").fail()
        error("unreachable")
    }

    private companion object {
        const val WAIT_STEPS = 500
        const val WAIT_STEP_MS = 10L
    }
}
