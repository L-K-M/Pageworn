package ch.lkmc.pageworn.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.view.ViewConfiguration
import ch.lkmc.pageworn.AppContainer
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.data.db.BookProgressEntity
import ch.lkmc.pageworn.data.db.LocalEditionEntity
import ch.lkmc.pageworn.playback.PlayProblem
import ch.lkmc.pageworn.ui.model.KeyAction
import ch.lkmc.pageworn.ui.state.PlanCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/**
 * Keeps the home screen widgets on the app's own state, and carries out their keys through the
 * services the screens use. It reads nothing while no widget is placed, and never uses the network.
 */
class HomeWidgets(
    private val context: Context,
    private val container: AppContainer,
    /** Runs before each request is drawn. Tests make it throw; the app leaves it empty. */
    private val beforePublishing: suspend (request: Long) -> Unit = {},
) {
    private val plans = PlanCache(container)
    private val views by lazy { WidgetViews(context, container.sessionActivity(context)) }

    /** Each request to look again has a number, and [published] follows once it has been drawn. */
    private val requests = MutableStateFlow(0L)
    private val published = MutableStateFlow(-1L)

    private val settling = MutableStateFlow<SettlingTap?>(null)
    private var taps = 0L

    fun start() {
        container.appScope.launch { requests.collectLatest(::publishSafely) }
    }

    /**
     * The collector runs in the application scope, which has no exception handler: an exception
     * while drawing would end the process, playback included. A failed request is logged by type
     * only, counts as published so [update] does not wait forever, and leaves the widgets as they
     * were until the next request. Cancellation still passes, so [collectLatest] can drop a stale
     * request.
     */
    private suspend fun publishSafely(request: Long) {
        try {
            beforePublishing(request)
            publishFrom(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Widget request $request failed: ${failure.javaClass.name}")
            published.value = request
        }
    }

    /** Widgets were added or removed, or the configuration they are drawn for changed. */
    fun refresh() {
        requests.updateAndGet { it + 1 }
    }

    /** Looks again, and returns once every placed widget shows the current state. */
    suspend fun update() {
        val request = requests.updateAndGet { it + 1 }
        published.first { it >= request }
    }

    /** Carries out [tap], on the main thread and in the order the taps arrive. */
    fun perform(tap: WidgetTap): Job? = when (tap.command) {
        WidgetCommand.PLAY -> {
            settle(tap.key, KeyAction.PLAY)
            container.playerConnection.playFromKey(tap.key)
        }
        WidgetCommand.PAUSE -> {
            settle(tap.key, KeyAction.PAUSE)
            container.playerConnection.pauseFromKey(tap.key)
        }
        WidgetCommand.SKIP_BACK -> container.bookTransport.skipBack(tap.key)
        WidgetCommand.SKIP_FORWARD -> container.bookTransport.skipForward(tap.key)
    }

    /**
     * The second tap of a double tap is the same tap, as on the app's keys. A widget's taps carry
     * no touch times, so its key keeps its action for as long as a double tap can take.
     */
    private fun settle(key: BookKey, action: KeyAction) {
        val tap = SettlingTap(key, action, ++taps)
        settling.value = tap
        container.appScope.launch {
            delay(ViewConfiguration.getDoubleTapTimeout().toLong())
            settling.compareAndSet(tap, null)
        }
    }

    private data class Books(
        val editions: List<LocalEditionEntity>,
        val progress: Map<BookKey, BookProgressEntity>,
        val lastActive: BookKey?,
        val problem: PlayProblem?,
        val settling: SettlingTap?,
    )

    private suspend fun publishFrom(request: Long) {
        // Null on a device without home screen widgets.
        val manager: AppWidgetManager? = context.getSystemService(AppWidgetManager::class.java)
        if (manager == null) {
            published.value = request
            return
        }
        val readingIds = manager.getAppWidgetIds(ComponentName(context, NowReadingWidget::class.java))
        val shelfIds = manager.getAppWidgetIds(ComponentName(context, BookshelfWidget::class.java))
        val showsReading = readingIds.isNotEmpty()
        val showsShelf = shelfIds.isNotEmpty()
        if (!showsReading && !showsShelf) {
            published.value = request
            return
        }

        val books = books()
        val readingStates = if (showsReading) readingStates(books) else flowOf(null)
        val shelfStates = if (showsShelf) shelfStates(books) else flowOf(null)
        var sent: Pair<ReadingWidgetState?, ShelfWidgetState?>? = null
        combine(readingStates, shelfStates, ::Pair).collect { next ->
            val previous = sent
            if (showsReading && (previous == null || previous.first != next.first)) {
                manager.updateAppWidget(readingIds, views.reading(next.first))
            }
            if (showsShelf && (previous == null || previous.second != next.second)) {
                manager.updateAppWidget(shelfIds, views.shelf(checkNotNull(next.second)))
            }
            sent = next
            published.value = request
        }
    }

    private fun books(): Flow<Books> = combine(
        container.editionStore.observeEverything(),
        container.database.progress().observeEverything(),
        container.settings.lastActiveBook,
        container.playerConnection.problem,
        settling,
    ) { editions, progress, lastActive, problem, settling ->
        Books(editions, progress.associateBy { it.key }, lastActive, problem, settling)
    }

    private fun readingStates(books: Flow<Books>): Flow<ReadingWidgetState?> =
        combine(books, container.playbackHub.state) { b, live ->
            val book = WidgetStates.readingBook(b.editions, b.progress, live, b.lastActive, b.problem)
            val plan = book?.let { plans.plan(it.edition) }
            if (book == null || plan == null) null
            else WidgetStates.reading(book, plan, b.progress[plan.key], live, b.problem, b.settling)
        }.distinctUntilChanged()

    private fun shelfStates(books: Flow<Books>): Flow<ShelfWidgetState> =
        combine(books, container.playbackHub.state.map { it.transportOnly() }.distinctUntilChanged()) { b, live ->
            WidgetStates.shelf(b.editions, { container.editionFiles.published(it.generationId) }, b.progress, live, b.problem, b.settling)
        }.distinctUntilChanged()

    private companion object {
        const val TAG = "HomeWidgets"
    }
}
