package ch.lkmc.pageworn.ui.downloaded

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

/** The Downloaded shelf: covers in a grid, with the play key and the repair caption kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class DownloadedGridTest {
    @get:Rule
    val compose = createComposeRule()

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

        // Over the art, in the lower corner, and never smaller than the 48 dp target.
        assertThat(key.positionInRoot.x).isAtLeast(cover.positionInRoot.x)
        assertThat(key.positionInRoot.y).isAtLeast(cover.positionInRoot.y)
        compose.onNode(hasContentDescription("Resume The Night Orchard") and hasClickAction())
            .assertWidthIsAtLeast(MIN_TARGET).assertHeightIsAtLeast(MIN_TARGET)

        compose.onNodeWithContentDescription("Resume The Night Orchard").performClick()
        assertThat(action).isEqualTo(orchard.face.key.itemId to KeyAction.PLAY)
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

        compose.onNodeWithText(Copy.REPAIR_NEEDED).assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Play Small Hours").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Resume Small Hours").assertCountEquals(0)
        assertThat(stateOf("Open Small Hours")).isNull()
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

    private fun book(title: String, badge: ReadyBadge, playable: Boolean = true, hasPlace: Boolean = false) = ReadyBookRow(
        BookFace(BookKey("profile", title), title, "A. Author", CoverImage.Cloth),
        badge, hasPlace = hasPlace, showsPause = false, starting = false, playable = playable,
    )

    private fun show(
        books: List<ReadyBookRow>,
        transfers: List<TransferRow> = emptyList(),
        onOpenBook: (BookKey) -> Unit = {},
        onPlayPause: (BookKey, KeyAction) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            PagewornTheme {
                DownloadedScreen(
                    state = DownloadedUiState(transfers = transfers, books = books),
                    listState = rememberLazyGridState(),
                    onOpenBook = onOpenBook,
                    onPlayPause = onPlayPause,
                    onPauseTransfer = {}, onResumeTransfer = {}, onRetryTransfer = {}, onCancelTransfer = {},
                    onAction = {}, onOpenSettings = {}, contentPadding = PaddingValues(),
                )
            }
        }
    }

    private fun stateOf(cover: String) =
        compose.onNodeWithContentDescription(cover).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.StateDescription) { null }

    private fun widthOf(node: androidx.compose.ui.semantics.SemanticsNode) = with(compose.density) { node.size.width.toDp() }

    private companion object {
        val MIN_TARGET = 48.dp
    }
}
