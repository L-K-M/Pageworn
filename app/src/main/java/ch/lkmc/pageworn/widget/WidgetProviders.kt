package ch.lkmc.pageworn.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ch.lkmc.pageworn.AppContainer
import ch.lkmc.pageworn.PagewornApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A widget's provider only says when to look again: [HomeWidgets] draws every widget. */
abstract class PaperWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        finishAfterPublishing(context.container, goAsync())
    }

    override fun onDisabled(context: Context) {
        context.container.homeWidgets.refresh()
    }
}

class NowReadingWidget : PaperWidgetProvider()

class BookshelfWidget : PaperWidgetProvider()

/**
 * A key on a widget. The launcher delivers it with ten seconds in which the app may start its
 * playback service in the foreground with while-in-use access, which Android 17 requires for audio.
 * So the command begins before this returns, and the process is held until it has ended.
 */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tap = WidgetTap.from(intent) ?: return
        val container = context.container
        val work = container.homeWidgets.perform(tap)
        finishAfterPublishing(container, goAsync()) { work?.join() }
    }
}

private val Context.container: AppContainer get() = (applicationContext as PagewornApp).container

/** Keeps the process until [work] has ended and the widgets show its result. */
private fun finishAfterPublishing(
    container: AppContainer,
    // Null when a receiver is called directly rather than by a broadcast.
    pending: BroadcastReceiver.PendingResult?,
    work: suspend () -> Unit = {},
) {
    container.appScope.launch {
        try {
            withTimeoutOrNull(BROADCAST_BUDGET_MS) {
                work()
                container.homeWidgets.update()
            }
        } finally {
            pending?.finish()
        }
    }
}

/** Inside the ten seconds a foreground broadcast may take. */
private const val BROADCAST_BUDGET_MS = 8_000L
