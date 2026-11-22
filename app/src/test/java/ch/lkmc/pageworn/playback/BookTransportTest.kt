package ch.lkmc.pageworn.playback

import androidx.test.core.app.ApplicationProvider
import ch.lkmc.pageworn.PagewornApp
import ch.lkmc.pageworn.PhoneBooks
import ch.lkmc.pageworn.core.progress.StopKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookTransportTest {
    private val container = ApplicationProvider.getApplicationContext<PagewornApp>().container
    private val books = PhoneBooks(container)

    @Test
    fun aSkipMovesTheNamedBookWhileAnotherIsLoaded() = runBlocking<Unit> {
        val named = books.download("named")
        val loaded = books.download("loaded")
        books.place(named, 600_000)
        books.place(loaded, 900_000)
        container.playbackHub.publish(LivePlayback(key = loaded, held = true, positionMs = 900_000))

        container.bookTransport.skipBack(named).join()

        val moved = checkNotNull(container.progressStore.read(named))
        assertThat(moved.positionMs).isEqualTo(585_000)
        assertThat(moved.stopKind).isEqualTo(StopKind.EXACT)
        assertThat(container.progressStore.read(loaded)?.positionMs).isEqualTo(900_000)
    }

    @Test
    fun skipsAtRestStayInsideTheBookAndFinishItAtItsEnd() = runBlocking<Unit> {
        val key = books.download("book")
        books.place(key, 5_000)

        container.bookTransport.skipBack(key).join()
        assertThat(container.progressStore.read(key)?.positionMs).isEqualTo(0)

        books.place(key, PhoneBooks.DURATION_MS - 10_000)
        container.bookTransport.skipForward(key).join()
        val end = checkNotNull(container.progressStore.read(key))
        assertThat(end.positionMs).isEqualTo(PhoneBooks.DURATION_MS)
        assertThat(end.finished).isTrue()
    }

    @Test
    fun quickSkipsAtRestEachMoveThePlace() = runBlocking<Unit> {
        val key = books.download("book")
        books.place(key, 600_000)

        List(5) { container.bookTransport.skipForward(key) }.forEach { it.join() }

        assertThat(container.progressStore.read(key)?.positionMs).isEqualTo(750_000)
    }
}
