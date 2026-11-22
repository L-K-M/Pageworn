package ch.lkmc.pageworn.widget

import android.content.Context
import android.graphics.Rect
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import ch.lkmc.pageworn.PagewornApp
import ch.lkmc.pageworn.R
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.ui.model.BookFace
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.CoverImage
import ch.lkmc.pageworn.ui.model.KeyAction
import ch.lkmc.pageworn.ui.model.StartAction
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** A32 and V07: every arrangement, as a launcher inflates and measures it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetViewsTest {
    private val app = ApplicationProvider.getApplicationContext<PagewornApp>()
    private val views get() = WidgetViews(app, app.container.sessionActivity(app))
    private val density get() = app.resources.displayMetrics.density

    private val key = WidgetKey(showsPause = false, action = KeyAction.PLAY, starting = false, start = StartAction.RESUME)

    @Test
    fun everyTapTargetFitsItsSmallestWidget() {
        for (fontScale in FONT_SCALES) {
            RuntimeEnvironment.setFontScale(fontScale)
            for (notice in listOf(null, Copy.PLAY_DID_NOT_START)) {
                for (layout in ReadingLayout.entries) {
                    val size = layout.minimum(fontScale)
                    assertTargetsFit(measured(views.reading(reading(notice), layout), size), "$layout at $fontScale, notice $notice")
                }
                for (layout in ShelfLayout.entries) {
                    val size = layout.minimum(fontScale)
                    assertTargetsFit(measured(views.shelf(shelf(notice), layout), size), "$layout at $fontScale, notice $notice")
                }
            }
        }
    }

    @Test
    fun theLargestArrangementsHaveBothBarsAndAllThreeKeys() {
        for (layout in listOf(ReadingLayout.PLAYER, ReadingLayout.TALL)) {
            val root = measured(views.reading(reading(), layout), layout.minimum(1f))
            for (id in listOf(R.id.widget_segment_bar, R.id.widget_book_bar, R.id.widget_back, R.id.widget_toggle, R.id.widget_forward)) {
                assertWithMessage("$layout ${app.resources.getResourceEntryName(id)}").that(root.findViewById<View>(id).isDrawn()).isTrue()
            }
        }
    }

    @Test
    fun keysAndTheWidgetNameWhatTheyDo() {
        val root = measured(views.reading(reading(), ReadingLayout.PLAYER), ReadingLayout.PLAYER.minimum(1f))

        fun spoken(id: Int) = root.findViewById<View>(id).contentDescription.toString()
        assertThat(spoken(R.id.widget_toggle)).isEqualTo("Resume The Night Orchard")
        assertThat(spoken(R.id.widget_back)).isEqualTo(Copy.BACK_15)
        assertThat(spoken(R.id.widget_forward)).isEqualTo(Copy.FORWARD_30)
        assertThat(spoken(android.R.id.background)).contains("Chapter: The Gate, 13 min left. Book · 2 h 08 min of 4 h 20 min")
    }

    @Test
    fun aStartingBookShowsPauseAndSaysItIsStarting() {
        val starting = reading().copy(key = key.copy(showsPause = true, starting = true))

        val toggle = measured(views.reading(starting, ReadingLayout.PLAYER), ReadingLayout.PLAYER.minimum(1f)).findViewById<View>(R.id.widget_toggle)

        assertThat(toggle.contentDescription.toString()).isEqualTo("Pause The Night Orchard")
        assertThat(toggle.stateDescription.toString()).isEqualTo(Copy.STARTING)
    }

    @Test
    fun aBookThatCannotPlayHasNoKeysOnlyTheReason() {
        val state = reading(Copy.CHOOSE_START).copy(key = null)
        for (layout in ReadingLayout.entries) {
            val root = measured(views.reading(state, layout), layout.minimum(1f))
            for (id in listOf(R.id.widget_toggle, R.id.widget_back, R.id.widget_forward)) {
                root.findViewById<View>(id)?.let { assertThat(it.isDrawn()).isFalse() }
            }
            assertWithMessage("$layout").that(root.findViewById<View>(R.id.widget_notice).isDrawn()).isTrue()
        }
    }

    @Test
    fun anUpdateOntoTheViewsAlreadyShownLeavesNothingBehind() {
        for (layout in ReadingLayout.entries) {
            val shown = measured(views.reading(reading(Copy.PLAY_DID_NOT_START).copy(key = null), layout), layout.minimum(1f))

            views.reading(reading(), layout).reapply(app, (shown as ViewGroup).getChildAt(0))

            assertWithMessage("$layout key").that(shown.findViewById<View>(R.id.widget_toggle).isDrawn()).isTrue()
            shown.findViewById<View>(R.id.widget_title)?.let { assertWithMessage("$layout title").that(it.isDrawn()).isTrue() }
            assertWithMessage("$layout cover").that(shown.findViewById<View>(R.id.widget_cover).isDrawn()).isTrue()
            assertWithMessage("$layout notice").that(shown.findViewById<View>(R.id.widget_notice).isDrawn()).isFalse()
        }

        val shelved = measured(views.shelf(shelf(Copy.PLAY_DID_NOT_START), ShelfLayout.FOUR), ShelfLayout.FOUR.minimum(1f))
        views.shelf(shelf(null).copy(books = shelf(null).books.take(2)), ShelfLayout.FOUR).reapply(app, (shelved as ViewGroup).getChildAt(0))
        assertThat(shelved.findViewById<ViewGroup>(R.id.widget_books).childCount).isEqualTo(2)
        assertThat(shelved.findViewById<View>(R.id.widget_books).isDrawn()).isTrue()
    }

    @Test
    fun aShelfReadsOutWhyABookDidNotStart() {
        val root = measured(views.shelf(shelf(Copy.PLAY_DID_NOT_START), ShelfLayout.FOUR), ShelfLayout.FOUR.minimum(1f))

        assertThat(root.findViewById<View>(android.R.id.background).contentDescription.toString()).contains(Copy.PLAY_DID_NOT_START)
    }

    @Test
    fun aFailedStartIsReadOutWithThePlaceItLeaves() {
        val root = measured(views.reading(reading(Copy.PLAY_DID_NOT_START), ReadingLayout.PLAYER), ReadingLayout.PLAYER.minimum(1f))

        val spoken = root.findViewById<View>(android.R.id.background).contentDescription.toString()
        assertThat(spoken).contains(Copy.PLAY_DID_NOT_START)
        assertThat(spoken).doesNotContain("..")
        assertThat(spoken).contains("Book · 2 h 08 min of 4 h 20 min")
    }

    /** API 31 scales large type linearly, and API 34 less for the larger sizes. */
    @Test
    @Config(sdk = [31, 34])
    fun everyNoticeShowsWholeWordsAndLines() {
        for (fontScale in FONT_SCALES) {
            RuntimeEnvironment.setFontScale(fontScale)
            for (notice in listOf(Copy.PLAY_DID_NOT_START, Copy.DAMAGED_COPY, Copy.CHOOSE_START)) {
                for (layout in ReadingLayout.entries) {
                    val state = reading(notice).copy(key = key.takeIf { notice == Copy.PLAY_DID_NOT_START })
                    val root = measured(views.reading(state, layout), layout.minimum(fontScale))
                    assertReadsWhole(root, notice, "$layout at $fontScale")
                }
            }
            // A book that waits for a choice says so on its own key on the shelf.
            for (notice in listOf(Copy.PLAY_DID_NOT_START, Copy.DAMAGED_COPY)) {
                for (layout in ShelfLayout.entries) {
                    val root = measured(views.shelf(shelf(notice), layout), layout.minimum(fontScale))
                    assertReadsWhole(root, notice, "$layout at $fontScale")
                }
            }
            for (size in listOf(SizeF(110f, 57f), SizeF(110f, 118f), SizeF(250f, 57f))) {
                assertReadsWhole(measured(views.reading(null), size), Copy.NO_DOWNLOADS, "empty $size at $fontScale")
            }
        }
    }

    @Test
    fun nowReadingIsOneSpokenTargetBesideItsKeys() {
        for (layout in ReadingLayout.entries) {
            for (notice in listOf(null, Copy.PLAY_DID_NOT_START)) {
                val root = measured(views.reading(reading(notice), layout), layout.minimum(1f))
                for (view in descendants(root).filter { (it is TextView && it.text.isNotEmpty()) || it is ProgressBar }) {
                    assertWithMessage("$layout ${app.resources.getResourceEntryName(view.id)}")
                        .that(view.importantForAccessibility).isEqualTo(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
                }
            }
        }
    }

    @Test
    fun theSizesShareOnePictureOfEachBook() {
        fun pictures(remote: RemoteViews): List<*> =
            ReflectionHelpers.getField(ReflectionHelpers.getField<Any>(remote, "mBitmapCache"), "mBitmaps")

        assertThat(pictures(views.shelf(shelf(null)))).hasSize(WidgetStates.SHELF_BOOKS)
        assertThat(pictures(views.reading(reading()))).hasSize(1)
    }

    @Test
    fun theLauncherPicksAnArrangementByTheWidgetsSize() {
        val sized = views.reading(reading())

        fun arranged(width: Float, height: Float) = measured(sized, SizeF(width, height))
        assertThat(arranged(360f, 300f).findViewById<View>(R.id.widget_author)).isNotNull()
        val player = arranged(360f, 200f)
        assertThat(player.findViewById<View>(R.id.widget_author)).isNull()
        assertThat(player.findViewById<View>(R.id.widget_back)).isNotNull()
        val strip = arranged(360f, 102f)
        assertThat(strip.findViewById<View>(R.id.widget_back)).isNull()
        assertThat(strip.findViewById<View>(R.id.widget_book_bar)).isNotNull()
        assertThat(arranged(150f, 60f).findViewById<View>(R.id.widget_title)).isNull()
    }

    @Test
    fun aFullShelfStaysFarInsideTheLaunchersPictureLimit() {
        val bytes = ReflectionHelpers.callInstanceMethod<Int>(views.shelf(shelf(null)), "estimateMemoryUsage")
        val metrics = app.resources.displayMetrics

        // AppWidgetService allows six bytes for each pixel of the screen.
        assertThat(bytes).isLessThan(6 * metrics.widthPixels * metrics.heightPixels / 2)
    }

    private fun reading(notice: String? = null) = ReadingWidgetState(
        face = face(0), hasPlace = true, segmentName = "Chapter", segmentLine = "The Gate", segmentLeft = "13 min left",
        segmentPermille = 400, bookLine = "Book · 2 h 08 min of 4 h 20 min", bookPermille = 493,
        key = key, notice = notice, destination = WidgetDestination.PLAYER,
    )

    private fun shelf(notice: String?) = ShelfWidgetState(
        List(WidgetStates.SHELF_BOOKS) { index ->
            val hold = ShelfHold.REPAIR.takeIf { index == 1 }
            ShelfBook(face(index), hasPlace = index == 0, key = key.takeIf { hold == null }, hold = hold)
        },
        notice,
    )

    private fun face(index: Int) =
        BookFace(BookKey("profile", "item-$index"), if (index == 0) "The Night Orchard" else "Book $index", "Elin Ward", CoverImage.Cloth)

    /** Inflates with the size the launcher passes, which the public apply() does not take. */
    private fun measured(remote: RemoteViews, size: SizeF): View {
        val parent = FrameLayout(app)
        val handler = Class.forName("android.widget.RemoteViews\$InteractionHandler")
        val root = ReflectionHelpers.callInstanceMethod<View>(
            remote, "apply",
            ClassParameter.from(Context::class.java, app),
            ClassParameter.from(ViewGroup::class.java, parent),
            ClassParameter(handler, null),
            ClassParameter.from(SizeF::class.java, size),
        )
        parent.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val width = Math.round(size.width * density)
        val height = Math.round(size.height * density)
        parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, width, height)
        return parent
    }

    /** Like isShown, which is always false outside a window. */
    private fun View.isDrawn(): Boolean =
        generateSequence(this) { it.parent as? View }.all { it.visibility == View.VISIBLE }

    private fun assertTargetsFit(root: View, where: String) {
        val targets = tapTargets(root)
        assertWithMessage(where).that(targets).isNotEmpty()
        for (target in targets) {
            val shown = Rect()
            assertWithMessage("$where: ${target.contentDescription} is on screen").that(target.getGlobalVisibleRect(shown)).isTrue()
            val minimum = (MIN_TARGET_DP * density).toInt()
            assertWithMessage("$where: ${target.contentDescription} width").that(shown.width()).isAtLeast(minimum)
            assertWithMessage("$where: ${target.contentDescription} height").that(shown.height()).isAtLeast(minimum)
        }
    }

    /** The whole notice, or its whole first sentence where there is no room for both. */
    private fun assertReadsWhole(root: View, notice: String, where: String) {
        val view = root.findViewById<TextView>(R.id.widget_notice)
        val text = view.text.toString()
        val at = "$where: $text"
        assertWithMessage(at).that(text).isAnyOf(notice, notice.substringBefore(". ").removeSuffix(".") + ".")

        val lines = view.layout
        val last = minOf(lines.lineCount, view.maxLines) - 1
        assertWithMessage("$at ends early").that(lines.getLineEnd(last)).isEqualTo(text.length)
        assertWithMessage("$at is shortened").that(lines.getEllipsisCount(last)).isEqualTo(0)
        val room = view.height - view.compoundPaddingTop - view.compoundPaddingBottom
        assertWithMessage("$at is cut through a line").that(lines.getLineBottom(last)).isAtMost(room + ROUNDING_PX)
        val shown = Rect()
        assertWithMessage("$at is on screen").that(view.getGlobalVisibleRect(shown)).isTrue()
        assertWithMessage("$at is inside the widget").that(shown.height()).isAtLeast(view.height - ROUNDING_PX)
        for (line in 0 until last) {
            val end = lines.getLineEnd(line)
            val splits = !text[end - 1].isWhitespace() && !text[end].isWhitespace()
            assertWithMessage("$at breaks inside a word after ${text.take(end)}, ${view.textSize} px in ${view.width}").that(splits).isFalse()
        }
    }

    private fun descendants(view: View): List<View> =
        listOf(view) + ((view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { descendants(group.getChildAt(it)) } }.orEmpty())

    private fun tapTargets(view: View): List<View> {
        if (view.visibility != View.VISIBLE) return emptyList()
        val own = listOf(view).filter { it.hasOnClickListeners() }
        val inside = (view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { tapTargets(group.getChildAt(it)) } }
        return own + inside.orEmpty()
    }

    private companion object {
        val FONT_SCALES = listOf(0.85f, 1f, 1.3f, 2f)
        const val MIN_TARGET_DP = 48f
        const val ROUNDING_PX = 2
    }
}
