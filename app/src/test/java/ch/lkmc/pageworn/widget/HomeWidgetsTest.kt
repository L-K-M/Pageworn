package ch.lkmc.pageworn.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.pageworn.MainActivity
import ch.lkmc.pageworn.PagewornApp
import ch.lkmc.pageworn.PhoneBooks
import ch.lkmc.pageworn.R
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.playback.LivePlayback
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A32: the placed widgets follow the app, and their taps reach the named book. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeWidgetsTest {
    private val app = ApplicationProvider.getApplicationContext<PagewornApp>()
    private val container = app.container
    private val books = PhoneBooks(container)
    private val manager = AppWidgetManager.getInstance(app)

    @Test
    fun aPlacedWidgetShowsTheBookAndFollowsItsKey() = runBlocking<Unit> {
        val key = books.download("orchard", title = "The Night Orchard")
        container.settings.setLastActiveBook(key)
        val id = shadowOf(manager).createWidget(NowReadingWidget::class.java, R.layout.pw_widget_empty)

        await { shown(id)?.findViewById<View>(R.id.widget_toggle)?.contentDescription?.toString() == "Play The Night Orchard" }
        container.playbackHub.publish(LivePlayback(key = key, held = true, playWhenReady = true, playbackRequested = true, isPlaying = true))

        await { shown(id)?.findViewById<View>(R.id.widget_toggle)?.contentDescription?.toString() == "Pause The Night Orchard" }
    }

    @Test
    fun theEmptyShelfSaysWhereBooksWillAppear() = runBlocking<Unit> {
        val id = shadowOf(manager).createWidget(BookshelfWidget::class.java, R.layout.pw_widget_empty)

        await { shown(id)?.findViewById<TextView>(R.id.widget_notice)?.text?.toString() == "Your downloaded books will appear here." }
    }

    @Test
    fun aFailedRequestLeavesLaterRequestsWorking() = runBlocking<Unit> {
        val key = books.download("recovering", title = "The Recovering")
        container.settings.setLastActiveBook(key)
        shadowOf(manager).createWidget(NowReadingWidget::class.java, R.layout.pw_widget_empty)

        val failing = AtomicBoolean(true)
        val widgets = HomeWidgets(app, container) { if (failing.get()) throw IllegalStateException("a failing request") }
        widgets.start()

        // The failed request is answered instead of leaving its caller waiting.
        withTimeout(10_000) { widgets.update() }

        // The collector survived it: with a widget placed, update() returns only once this
        // instance has drawn. (The app's own instance draws the widget too, so the views alone
        // would not show it.)
        failing.set(false)
        withTimeout(10_000) { widgets.update() }
    }

    @Test
    fun aSkipKeyMovesTheBookItWasDrawnFor() = runBlocking<Unit> {
        val drawnFor = books.download("drawn-for")
        val playing = books.download("playing")
        books.place(drawnFor, 600_000)
        container.playbackHub.publish(LivePlayback(key = playing, held = true, playbackRequested = true))

        WidgetTap(WidgetCommand.SKIP_BACK, drawnFor).pendingIntent(app).send()

        await { container.progressStore.read(drawnFor)?.positionMs == 585_000L }
        assertThat(container.progressStore.read(playing)).isNull()
    }

    @Test
    fun playShowsPauseAtOnceAndAStalePauseLeavesTheOtherBookAlone() = runBlocking<Unit> {
        val tapped = books.download("tapped")
        val other = books.download("other")

        container.homeWidgets.perform(WidgetTap(WidgetCommand.PLAY, tapped))
        assertThat(container.playbackHub.state.value.showsPause(tapped)).isTrue()

        assertThat(container.homeWidgets.perform(WidgetTap(WidgetCommand.PAUSE, other))).isNull()
        assertThat(container.playbackHub.state.value.showsPause(tapped)).isTrue()
    }

    @Test
    fun eachKeyOfEachBookIsItsOwnTap() {
        val taps = listOf(BookKey("profile", "one"), BookKey("profile", "two#odd/id?")).flatMap { key ->
            WidgetCommand.entries.map { WidgetTap(it, key) }
        }

        assertThat(taps.map { it.pendingIntent(app) }.toSet()).hasSize(taps.size)
        for (tap in taps) {
            val sent = shadowOf(tap.pendingIntent(app))
            assertThat(WidgetTap.from(sent.savedIntent)).isEqualTo(tap)
            assertThat(sent.isBroadcastIntent).isTrue()
            assertThat(sent.isImmutable).isTrue()
            assertThat(sent.savedIntent.flags and Intent.FLAG_RECEIVER_FOREGROUND).isNotEqualTo(0)
        }
    }

    @Test
    fun aTapWithoutABookIsIgnored() {
        val valid = WidgetTap(WidgetCommand.PLAY, BookKey("profile", "one")).intent(app)

        assertThat(WidgetTap.from(Intent(valid).setData(null))).isNull()
        assertThat(WidgetTap.from(Intent(valid).setAction("ch.lkmc.pageworn.widget.DELETE"))).isNull()
    }

    @Test
    fun bookLinksAreDistinctForEachBook() {
        val one = MainActivity.openBookIntent(app, BookKey("profile", "one"))
        val two = MainActivity.openBookIntent(app, BookKey("profile", "two"))

        assertThat(one.filterEquals(two)).isFalse()
    }

    @Test
    fun otherAppsCannotPressTheKeys() {
        for (receiver in listOf(WidgetActionReceiver::class.java, NowReadingWidget::class.java, BookshelfWidget::class.java)) {
            val info = app.packageManager.getReceiverInfo(ComponentName(app, receiver), 0)
            assertWithMessage(receiver.simpleName).that(info.exported).isFalse()
        }
    }

    private fun shown(id: Int): View? = shadowOf(manager).getViewFor(id)

    private suspend fun await(condition: suspend () -> Boolean) {
        val deadline = System.nanoTime() + WAIT_NS
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    private companion object {
        const val WAIT_NS = 10_000_000_000L
    }
}
