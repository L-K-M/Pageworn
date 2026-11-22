package ch.lkmc.pageworn.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import ch.lkmc.pageworn.MainActivity
import ch.lkmc.pageworn.R
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.StartAction

/**
 * Now reading's arrangements. The launcher picks the closest that fits, so each gives the least
 * room it needs: its fixed parts, and its lines of text at the system's font scale.
 */
enum class ReadingLayout(
    @LayoutRes val layout: Int,
    private val minWidth: Float,
    private val fixedHeight: Float,
    private val textHeight: Float,
) {
    COMPACT(R.layout.pw_widget_reading_compact, 110f, 57f, 0f),
    STRIP(R.layout.pw_widget_reading_strip, 240f, 36f, 41f),
    SQUARE(R.layout.pw_widget_reading_square, 120f, 98f, 41f),
    PLAYER(R.layout.pw_widget_reading_player, 250f, 98f, 59f),
    TALL(R.layout.pw_widget_reading_tall, 310f, 148f, 112f);

    fun minimum(fontScale: Float) = SizeF(minWidth, fixedHeight + textHeight * fontScale)
}

/** Bookshelf's arrangements: as many books as the width holds, with titles where the height allows. */
enum class ShelfLayout(val books: Int, val titles: Boolean) {
    ONE(1, false), TWO(2, false), THREE(3, false), FOUR(4, false),
    ONE_TITLED(1, true), TWO_TITLED(2, true), THREE_TITLED(3, true), FOUR_TITLED(4, true);

    fun minimum(fontScale: Float) = SizeF(
        // Large type takes fewer books, so each title and label keeps room to be read.
        FIRST_BOOK_WIDTH + (books - 1) * (BOOK_WIDTH + LABEL_WIDTH * (fontScale - 1).coerceAtLeast(0f)),
        if (titles) TITLED_HEIGHT + TITLE_HEIGHT * fontScale else HEIGHT,
    )

    private companion object {
        const val FIRST_BOOK_WIDTH = 110f
        const val BOOK_WIDTH = 70f
        const val LABEL_WIDTH = 24f
        const val HEIGHT = 118f
        const val TITLED_HEIGHT = 144f
        const val TITLE_HEIGHT = 20f
    }
}

/**
 * Draws widget states as RemoteViews. Every view that can change is set every time, because the
 * launcher applies an update to the views it already has.
 */
class WidgetViews(private val context: Context, private val openPlayer: PendingIntent) {
    private val covers = WidgetCovers(context)

    fun reading(state: ReadingWidgetState?): RemoteViews {
        if (state == null) return empty()
        val fontScale = context.resources.configuration.fontScale
        return RemoteViews(ReadingLayout.entries.associate { it.minimum(fontScale) to reading(state, it) })
    }

    fun shelf(state: ShelfWidgetState): RemoteViews {
        if (state.books.isEmpty()) return empty()
        val fontScale = context.resources.configuration.fontScale
        return RemoteViews(ShelfLayout.entries.associate { it.minimum(fontScale) to shelf(state, it) })
    }

    fun reading(state: ReadingWidgetState, layout: ReadingLayout): RemoteViews {
        val views = RemoteViews(context.packageName, layout.layout)
        val book = state.face.key
        val open = if (state.destination == WidgetDestination.PLAYER) openPlayer else openBook(book)
        views.setOnClickPendingIntent(android.R.id.background, open)
        views.setContentDescription(android.R.id.background, spoken(state))

        // The smallest arrangement has room for the book or for why it did not start.
        val noticeTakesCover = layout == ReadingLayout.COMPACT && state.notice != null && state.key != null
        views.setViewVisibility(R.id.widget_cover, visibleIf(!noticeTakesCover))
        views.setImageViewBitmap(R.id.widget_cover, covers.bitmap(state.face, state.hasPlace))
        views.setTextViewText(R.id.widget_title, state.face.title)
        views.setTextViewText(R.id.widget_author, state.face.author.orEmpty())

        val waiting = state.key?.starting == true
        views.setTextViewText(R.id.widget_segment, if (waiting) Copy.STARTING else state.segmentLine)
        views.setTextViewText(R.id.widget_segment_left, state.segmentLeft)
        // Beside large type the time left would take the chapter's name.
        val roomForTimeLeft = layout != ReadingLayout.PLAYER || context.resources.configuration.fontScale < LARGE_TYPE
        views.setViewVisibility(R.id.widget_segment_left, visibleIf(roomForTimeLeft))
        views.setTextViewText(R.id.widget_book_line, state.bookLine)
        views.setProgressBar(R.id.widget_segment_bar, WidgetStates.PERMILLE, state.segmentPermille, false)
        views.setProgressBar(R.id.widget_book_bar, WidgetStates.PERMILLE, state.bookPermille, false)

        val hasNotice = state.notice != null
        for (line in PROGRESS_VIEWS) views.setViewVisibility(line, visibleIf(!hasNotice))
        views.setViewVisibility(R.id.widget_notice, visibleIf(hasNotice))
        views.setTextViewText(R.id.widget_notice, state.notice.orEmpty())

        val key = state.key
        for (control in KEY_VIEWS) views.setViewVisibility(control, visibleIf(key != null))
        if (key != null) {
            bindToggle(views, book, state.face.title, key)
            bindSkip(views, R.id.widget_back, WidgetTap(WidgetCommand.SKIP_BACK, book), Copy.BACK_15)
            bindSkip(views, R.id.widget_forward, WidgetTap(WidgetCommand.SKIP_FORWARD, book), Copy.FORWARD_30)
        }
        return views
    }

    fun shelf(state: ShelfWidgetState, layout: ShelfLayout): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.pw_widget_shelf)
        views.setOnClickPendingIntent(android.R.id.background, openApp())
        views.setContentDescription(android.R.id.background, context.getString(R.string.widget_bookshelf))
        views.setViewVisibility(R.id.widget_notice, visibleIf(state.notice != null))
        views.setTextViewText(R.id.widget_notice, state.notice.orEmpty())
        // A short shelf has room for its books or for why one did not start.
        views.setViewVisibility(R.id.widget_books, visibleIf(state.notice == null || layout.titles))

        views.removeAllViews(R.id.widget_books)
        views.removeAllViews(R.id.widget_keys)
        for (book in state.books.take(layout.books)) {
            views.addView(R.id.widget_books, shelfCover(book, layout.titles))
            views.addView(R.id.widget_keys, shelfKey(book))
        }
        return views
    }

    private fun shelfCover(book: ShelfBook, titled: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.pw_widget_shelf_book)
        val title = book.face.title
        views.setImageViewBitmap(R.id.widget_cover, covers.bitmap(book.face, book.hasPlace))
        views.setViewVisibility(R.id.widget_title, visibleIf(titled))
        views.setTextViewText(R.id.widget_title, title)
        views.setContentDescription(R.id.widget_book, "Open $title")
        views.setOnClickPendingIntent(R.id.widget_book, openBook(book.face.key))
        return views
    }

    private fun shelfKey(book: ShelfBook): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.pw_widget_shelf_key)
        views.setViewVisibility(R.id.widget_toggle, visibleIf(book.key != null))
        views.setViewVisibility(R.id.widget_hold, visibleIf(book.hold != null))
        book.key?.let { bindToggle(views, book.face.key, book.face.title, it) }
        book.hold?.let {
            val spoken = when (it) {
                ShelfHold.REPAIR -> Copy.DAMAGED_COPY
                ShelfHold.CHOOSE_START -> Copy.CHOOSE_START
            }
            views.setTextViewText(R.id.widget_hold, it.label)
            views.setContentDescription(R.id.widget_hold, "${book.face.title}: $spoken")
            views.setOnClickPendingIntent(R.id.widget_hold, openBook(book.face.key))
        }
        return views
    }

    private fun empty(): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.pw_widget_empty)
        views.setOnClickPendingIntent(android.R.id.background, openApp())
        views.setTextViewText(R.id.widget_notice, Copy.NO_DOWNLOADS)
        return views
    }

    private fun bindToggle(views: RemoteViews, book: BookKey, title: String, key: WidgetKey) {
        val verb = when {
            key.showsPause -> Copy.PAUSE
            key.start == StartAction.REPLAY -> Copy.REPLAY
            key.start == StartAction.RESUME -> "Resume"
            else -> "Play"
        }
        views.setImageViewResource(R.id.widget_toggle, if (key.showsPause) R.drawable.pw_inlay_pause else R.drawable.pw_inlay_play)
        views.setContentDescription(R.id.widget_toggle, "$verb $title")
        views.setCharSequence(R.id.widget_toggle, "setStateDescription", if (key.starting) Copy.STARTING else null)
        views.setOnClickPendingIntent(R.id.widget_toggle, WidgetTap(WidgetCommand.of(key.action), book).pendingIntent(context))
    }

    private fun bindSkip(views: RemoteViews, id: Int, tap: WidgetTap, description: String) {
        views.setContentDescription(id, description)
        views.setOnClickPendingIntent(id, tap.pendingIntent(context))
    }

    /** The widget is one target beside its keys, so it reads out everything it shows. */
    private fun spoken(state: ReadingWidgetState): String {
        val place = state.notice ?: "${state.segmentName}: ${state.segmentLine}, ${state.segmentLeft}. ${state.bookLine}"
        return listOfNotNull(state.face.title, state.face.author, place).joinToString(". ")
    }

    private fun openBook(book: BookKey): PendingIntent =
        activity(MainActivity.openBookIntent(context, book))

    /** The launcher's own intent, which brings the app forward on the screen it was left on. */
    private fun openApp(): PendingIntent = activity(
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    private fun activity(intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun visibleIf(shown: Boolean) = if (shown) View.VISIBLE else View.GONE

    private companion object {
        const val LARGE_TYPE = 1.5f
        val PROGRESS_VIEWS = intArrayOf(R.id.widget_progress, R.id.widget_segment_row)
        val KEY_VIEWS = intArrayOf(R.id.widget_toggle, R.id.widget_back, R.id.widget_forward, R.id.widget_key_row)
    }
}
