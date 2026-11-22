package ch.lkmc.pageworn.widget

import android.content.Context
import android.graphics.Rect
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
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

    private fun tapTargets(view: View): List<View> {
        if (view.visibility != View.VISIBLE) return emptyList()
        val own = listOf(view).filter { it.hasOnClickListeners() }
        val inside = (view as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { tapTargets(group.getChildAt(it)) } }
        return own + inside.orEmpty()
    }

    private companion object {
        val FONT_SCALES = listOf(0.85f, 1f, 1.3f, 2f)
        const val MIN_TARGET_DP = 48f
    }
}
