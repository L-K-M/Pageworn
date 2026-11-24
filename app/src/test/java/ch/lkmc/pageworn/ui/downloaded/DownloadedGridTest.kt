package ch.lkmc.pageworn.ui.downloaded

import android.graphics.Bitmap
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.ui.model.BookFace
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.model.CoverImage
import ch.lkmc.pageworn.ui.model.DownloadedUiState
import ch.lkmc.pageworn.ui.model.KeyAction
import ch.lkmc.pageworn.ui.model.ReadyBadge
import ch.lkmc.pageworn.ui.model.ReadyBookRow
import ch.lkmc.pageworn.ui.model.TransferPhase
import ch.lkmc.pageworn.ui.model.TransferRow
import ch.lkmc.pageworn.ui.model.TransferUi
import ch.lkmc.pageworn.ui.components.ROW_COVER
import ch.lkmc.pageworn.ui.theme.PagewornTheme
import androidx.compose.ui.unit.dp
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/** The Downloaded shelf: covers in a grid, with the play key and the repair caption kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class DownloadedGridTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val files = TemporaryFolder()

    private val orchard = book("The Night Orchard", ReadyBadge.InProgress("Chapter 4 · 2 h 12 min left"), hasPlace = true)
    private val rain = book("Field Notes on Rain", ReadyBadge.New)
    private val coast = book("The Glass Coast", ReadyBadge.Finished)
    private val hours = book("Small Hours", ReadyBadge.RepairNeeded(Copy.DAMAGED_COPY), playable = false)

    @Test
    fun readyBooksShareRowsOfCovers() {
        show(books = listOf(orchard, rain, coast, hours))

        val first = compose.onNodeWithContentDescription("Open The Night Orchard").fetchSemanticsNode()
        val second = compose.onNodeWithContentDescription("Open Field Notes on Rain").fetchSemanticsNode()

        // A grid, not a list: the second book sits beside the first one rather than under it.
        assertThat(second.positionInRoot.x).isGreaterThan(first.positionInRoot.x)
        assertThat(second.positionInRoot.y).isWithin(1f).of(first.positionInRoot.y)
        assertThat(compose.onNodeWithContentDescription("Open Small Hours").fetchSemanticsNode().positionInRoot.y)
            .isGreaterThan(first.positionInRoot.y)
    }

    @Test
    fun aCoverShowsNoTitleOrBadgeText() {
        show(books = listOf(orchard, rain))

        // The listener knows these books: the row's title, author and badge text are gone.
        compose.onAllNodesWithText("The Night Orchard").assertCountEquals(0)
        compose.onAllNodesWithText("A. Author").assertCountEquals(0)
        compose.onAllNodesWithText("2 h 12 min left").assertCountEquals(0)
        compose.onAllNodesWithText("New").assertCountEquals(0)
    }

    @Test
    fun aCoverKeepsItsStateForTalkBack() {
        show(books = listOf(orchard, rain, coast))

        assertThat(stateOf("Open The Night Orchard")).isEqualTo("Chapter 4 · 2 h 12 min left")
        assertThat(stateOf("Open Field Notes on Rain")).isEqualTo("New")
        assertThat(stateOf("Open The Glass Coast")).isEqualTo("Finished")
    }

    @Test
    fun thePlayKeySitsOnTheCoverAndKeepsItsTarget() {
        var action: Pair<String, KeyAction>? = null
        show(books = listOf(orchard), onPlayPause = { key, pressed -> action = key.itemId to pressed })

        val cover = compose.onNodeWithContentDescription("Open The Night Orchard").fetchSemanticsNode()
        val key = compose.onNodeWithContentDescription("Resume The Night Orchard").fetchSemanticsNode()

        // Over the art, close to its lower corner so it hides little of it, and never smaller
        // than the 48 dp target.
        assertThat(key.positionInRoot.x).isAtLeast(cover.positionInRoot.x)
        assertThat(key.positionInRoot.y).isAtLeast(cover.positionInRoot.y)
        val art = artOf(cover.boundsInRoot)
        val face = key.touchBoundsInRoot.center
        assertThat(toDp(art.right - face.x)).isIn(Range.closed(0f, NEAR_CORNER))
        assertThat(toDp(art.bottom - face.y)).isIn(Range.closed(0f, NEAR_CORNER))
        compose.onNode(hasContentDescription("Resume The Night Orchard") and hasClickAction())
            .assertWidthIsAtLeast(MIN_TARGET).assertHeightIsAtLeast(MIN_TARGET)

        compose.onNodeWithContentDescription("Resume The Night Orchard").performClick()
        assertThat(action).isEqualTo(orchard.face.key.itemId to KeyAction.PLAY)
    }

    @Test
    fun theKeysTargetPastTheCornerStillPlays() {
        var action: KeyAction? = null
        var opened = false
        show(books = listOf(rain), onOpenBook = { opened = true }, onPlayPause = { _, pressed -> action = pressed })

        // The key rests on the corner, so part of its target lies outside the cover. A touch
        // there is still the key's, and the cover beneath does not open.
        val cover = artOf(boundsOf("Open Field Notes on Rain"))
        val target = keyTarget("Play Field Notes on Rain")
        assertThat(target.right).isGreaterThan(cover.right)
        compose.onNodeWithContentDescription("Play Field Notes on Rain").performTouchInput {
            click(Offset(width - 2f, height - 2f))
        }
        assertThat(action).isEqualTo(KeyAction.PLAY)
        assertThat(opened).isFalse()
    }

    @Test
    fun aCoverOpensTheBook() {
        var opened: String? = null
        show(books = listOf(rain), onOpenBook = { opened = it.itemId })

        compose.onNodeWithContentDescription("Open Field Notes on Rain").performClick()

        assertThat(opened).isEqualTo(rain.face.key.itemId)
    }

    @Test
    fun aDamagedEditionSaysSoUnderItsCoverAndHasNoKey() {
        show(books = listOf(hours))

        // One line under the cover, starting at its edge; the reason is what TalkBack reads from
        // the cover, and details show it with the repair.
        val cover = boundsOf("Open Small Hours")
        val caption = compose.onNodeWithText(Copy.REPAIR_NEEDED).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertThat(caption.top).isAtLeast(cover.bottom)
        assertThat(caption.left).isWithin(1f).of(cover.left)
        val reason = (hours.badge as ReadyBadge.RepairNeeded).message
        compose.onAllNodesWithText(reason).assertCountEquals(0)
        assertThat(stateOf("Open Small Hours")).isEqualTo(reason)
        compose.onAllNodesWithContentDescription("Play Small Hours").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Resume Small Hours").assertCountEquals(0)
    }

    @Test
    fun theShelfKeepsThePageMargins() {
        show(books = listOf(orchard, rain, coast))

        // The covers line up with the heading and the rules, 20 dp in from either edge.
        val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        val first = boundsOf("Open The Night Orchard")
        val last = boundsOf("Open The Glass Coast")
        assertThat(first.top).isEqualTo(last.top)
        assertThat(toDp(first.left)).isWithin(0.5f).of(PAGE_MARGIN)
        assertThat(toDp(width - last.right)).isWithin(0.5f).of(PAGE_MARGIN)
    }

    @Test
    fun theHeadingStillRunsToTheScreensEdges() {
        var settings = 0
        show(books = listOf(orchard), onOpenSettings = { settings++ })

        // The gear keeps its place near the edge, outside the shelf's margin, and a touch there
        // still reaches it.
        val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        val gear = compose.onNodeWithContentDescription("Settings")
        assertThat(toDp(width - gear.fetchSemanticsNode().boundsInRoot.right)).isLessThan(PAGE_MARGIN)
        gear.performTouchInput { click(Offset(this.width - 2f, centerY)) }
        assertThat(settings).isEqualTo(1)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theKeyFollowsPortraitArtAndLeavesItsCentreClear() {
        val art = coverFile("portrait.png", width = 200, height = 300)
        show(books = listOf(orchard.copy(face = orchard.face.copy(cover = CoverImage.Local(art)))))

        // A 2:3 book is narrower than its square frame and centred in it. Once the art is in, the
        // key sits on the book's lower corner rather than on the paper beside it, and still leaves
        // the book's centre to the cover's tap.
        val frame = artOf(boundsOf("Open The Night Orchard"))
        val bookRight = frame.center.x + frame.height / 3
        compose.waitUntil(5_000) { keyTarget("Resume The Night Orchard").center.x < bookRight }
        val key = keyTarget("Resume The Night Orchard")
        assertThat(toDp(bookRight - key.center.x)).isIn(Range.closed(0f, NEAR_CORNER))
        assertThat(toDp(frame.bottom - key.center.y)).isIn(Range.closed(0f, NEAR_CORNER))
        assertThat(key.contains(frame.center)).isFalse()
    }

    @Test
    fun transfersKeepTheirFullWidthAboveTheCovers() {
        val transfer = TransferRow(
            coast.face,
            TransferUi("job", TransferPhase.TRANSFERRING, 62, 100, "Downloading", canPause = true, canResume = false, canRetry = false, canCancel = true),
        )
        show(books = listOf(rain), transfers = listOf(transfer))

        compose.onNodeWithContentDescription("Pause download of The Glass Coast").assertIsDisplayed()
        val transferCover = compose.onNodeWithContentDescription("Open The Glass Coast").fetchSemanticsNode()
        val cover = compose.onNodeWithContentDescription("Open Field Notes on Rain").fetchSemanticsNode()

        // The group stays above the shelf, and its row still runs the width of the screen.
        assertThat(transferCover.positionInRoot.y).isLessThan(cover.positionInRoot.y)
        // A full-width row, not a shelf column: its cover keeps a row's size.
        assertThat(widthOf(transferCover)).isAtMost(ROW_COVER + 1.dp)
    }

    @Test
    fun theShelfStillScrollsByIndex() {
        show(books = listOf(orchard, rain, coast, hours))

        // The visual checks scroll the shelf by node, as they scrolled the list.
        compose.onNode(hasScrollToIndexAction() and hasScrollAction()).assertExists()
    }

    @Test
    fun theKeyStaysPutWhenTheBookGainsItsPlace() {
        val books = mutableStateOf(listOf(orchard.copy(hasPlace = false)))
        compose.setContent {
            PagewornTheme {
                DownloadedScreen(
                    state = DownloadedUiState(books = books.value),
                    listState = rememberLazyGridState(),
                    onOpenBook = {}, onPlayPause = { _, _ -> },
                    onPauseTransfer = {}, onResumeTransfer = {}, onRetryTransfer = {}, onCancelTransfer = {},
                    onAction = {}, onOpenSettings = {}, contentPadding = PaddingValues(),
                )
            }
        }
        val before = compose.onNodeWithContentDescription("Play The Night Orchard").fetchSemanticsNode().positionInRoot

        compose.runOnIdle { books.value = listOf(orchard) }

        // The ribbon takes its space before it appears, so a book starting moves nothing on the shelf.
        assertThat(compose.onNodeWithContentDescription("Resume The Night Orchard").fetchSemanticsNode().positionInRoot).isEqualTo(before)
    }

    @Test
    fun theKeysTargetNeverReachesTheCoversCentre() {
        // A tap on a cover opens the book, so the key over its corner must leave the centre clear
        // at every width the shelf runs at.
        listOf("w320dp-h640dp", "w360dp-h800dp", "w411dp-h891dp", "w600dp-h960dp", "w840dp-h480dp").forEach { screen ->
            RuntimeEnvironment.setQualifiers(screen)
            show(books = listOf(orchard))

            val cover = compose.onNodeWithContentDescription("Open The Night Orchard").fetchSemanticsNode().boundsInRoot
            val key = compose.onNodeWithContentDescription("Resume The Night Orchard").fetchSemanticsNode().touchBoundsInRoot

            assertWithMessage("play key target on a $screen screen").that(key.contains(cover.center)).isFalse()
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theKeyOnShortArtKeepsToItsCell() {
        val wide = orchard.copy(face = orchard.face.copy(cover = CoverImage.Local(coverFile("landscape.png", width = 300, height = 150))))
        val tall = rain.copy(face = rain.face.copy(cover = CoverImage.Local(coverFile("portrait.png", width = 200, height = 300))))
        show(books = listOf(wide, tall, coast, book("Small Hours", ReadyBadge.New)))

        // A landscape key has nowhere to move once its art is in, so the portrait book beside it
        // shows when the art has loaded: its key moves in to its narrower book.
        val beside = artOf(boundsOf("Open Field Notes on Rain"))
        compose.waitUntil(5_000) { keyTarget("Play Field Notes on Rain").center.x < beside.center.x + beside.height / 3 }
        Thread.sleep(ART_SETTLE_MS)
        compose.waitForIdle()

        // A 2:1 book sits at the foot of its frame. Its key stays on the corner, leaves the centre
        // to the cover's tap and keeps its whole target above the next row's cover.
        val frame = artOf(boundsOf("Open The Night Orchard"))
        val bookTop = frame.bottom - frame.width / 2
        val key = keyTarget("Resume The Night Orchard")
        val bookCentre = Offset(frame.center.x, (bookTop + frame.bottom) / 2)
        assertThat(key.contains(bookCentre)).isFalse()
        assertThat(toDp(frame.right - key.center.x)).isIn(Range.closed(0f, NEAR_CORNER))
        assertThat(key.bottom).isAtMost(boundsOf("Open Small Hours").top)
    }

    private fun coverFile(name: String, width: Int, height: Int) = files.newFile(name).also { file ->
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun book(title: String, badge: ReadyBadge, playable: Boolean = true, hasPlace: Boolean = false) = ReadyBookRow(
        BookFace(BookKey("profile", title), title, "A. Author", CoverImage.Cloth),
        badge, hasPlace = hasPlace, showsPause = false, starting = false, playable = playable,
    )

    private fun show(
        books: List<ReadyBookRow>,
        transfers: List<TransferRow> = emptyList(),
        onOpenBook: (BookKey) -> Unit = {},
        onPlayPause: (BookKey, KeyAction) -> Unit = { _, _ -> },
        onOpenSettings: () -> Unit = {},
    ) {
        compose.setContent {
            PagewornTheme {
                DownloadedScreen(
                    state = DownloadedUiState(transfers = transfers, books = books),
                    listState = rememberLazyGridState(),
                    onOpenBook = onOpenBook,
                    onPlayPause = onPlayPause,
                    onPauseTransfer = {}, onResumeTransfer = {}, onRetryTransfer = {}, onCancelTransfer = {},
                    onAction = {}, onOpenSettings = onOpenSettings, contentPadding = PaddingValues(),
                )
            }
        }
    }

    private fun boundsOf(description: String) = compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot

    private fun keyTarget(description: String) = compose.onNodeWithContentDescription(description).fetchSemanticsNode().touchBoundsInRoot

    /** The square frame of a cover's art, without the ribbon that may hang below it. */
    private fun artOf(cover: Rect) = Rect(cover.left, cover.top, cover.right, cover.top + cover.width)

    private fun toDp(px: Float) = with(compose.density) { px.toDp().value }

    private fun stateOf(cover: String) =
        compose.onNodeWithContentDescription(cover).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.StateDescription) { null }

    private fun widthOf(node: androidx.compose.ui.semantics.SemanticsNode) = with(compose.density) { node.size.width.toDp() }

    private companion object {
        val MIN_TARGET = 48.dp
        const val PAGE_MARGIN = 20f

        /** How far in from the art's lower corner the key's centre may sit. */
        const val NEAR_CORNER = 12f

        /** Time for a second cover's art, decoded beside one already in, to reach the screen. */
        const val ART_SETTLE_MS = 300L
    }
}
