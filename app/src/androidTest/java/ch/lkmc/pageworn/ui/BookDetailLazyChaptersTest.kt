package ch.lkmc.pageworn.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.ui.detail.BookDetailScreen
import ch.lkmc.pageworn.ui.model.BookDetailUiState
import ch.lkmc.pageworn.ui.model.BookFace
import ch.lkmc.pageworn.ui.model.CoverImage
import ch.lkmc.pageworn.ui.model.DetailAction
import ch.lkmc.pageworn.ui.model.SegmentRow
import ch.lkmc.pageworn.ui.model.StartAction
import ch.lkmc.pageworn.ui.theme.PagewornTheme
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Book details hold hundreds of parts without composing them all. The first screen stays
 * bounded, and scrolling still reaches the last part and the removal action.
 */
@RunWith(AndroidJUnit4::class)
class BookDetailLazyChaptersTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var selectedStartMs: Long? = null
    private var removed = false

    @Test
    fun aLongPartListComposesOnlyWhatFitsAndKeepsItsChapterAndRemovalActions() {
        val segmentCount = SEGMENT_COUNT
        composeRule.setContent {
            PagewornTheme {
                BookDetailScreen(
                    state = detailState(segmentCount),
                    onBack = {},
                    onDownload = {},
                    onPlayPause = {},
                    onRepair = {},
                    onPauseTransfer = {},
                    onResumeTransfer = {},
                    onCancelTransfer = {},
                    onSelectSegment = { selectedStartMs = it },
                    onRemove = { removed = true },
                )
            }
        }
        composeRule.waitForIdle()

        val lastPart = partTitle(segmentCount - 1)
        composeRule.onNodeWithText(TITLE).assertExists()
        val composedParts = composeRule.onAllNodes(hasText("Part ", substring = true)).fetchSemanticsNodes().size
        assertWithMessage("composed part rows out of $segmentCount").that(composedParts).isAtMost(MAX_COMPOSED_PARTS)

        val list = composeRule.onNode(hasScrollToNodeAction())
        list.performScrollToKey(segmentCount - 1)
        composeRule.onNodeWithText(lastPart).performClick()
        assertThat(selectedStartMs).isEqualTo((segmentCount - 1) * PART_MS)

        list.performScrollToNode(hasContentDescription(removeLabel()))
        composeRule.onNode(hasContentDescription(removeLabel())).performClick()
        assertThat(removed).isTrue()
    }

    private fun detailState(count: Int) = BookDetailUiState(
        face = BookFace(BookKey("profile", "long-anthology"), TITLE, "Many Voices", CoverImage.Cloth),
        narrator = "A Reader",
        durationText = "66 h 40 min",
        sizeText = "12 MB",
        action = DetailAction.Play(StartAction.of(hasPlace = false, finished = false), showsPause = false, starting = false),
        description = "A synthetic edition with one part per chapter.",
        segmentsTitle = "Parts",
        segments = List(count) { index ->
            SegmentRow(index, partTitle(index), "10:00", generated = true, current = index == 0, startMs = index * PART_MS)
        },
        segmentsPlayable = true,
        canRemove = true,
    )

    private companion object {
        const val TITLE = "The Long Anthology"
        const val SEGMENT_COUNT = 400
        const val PART_MS = 600_000L
        const val MAX_COMPOSED_PARTS = 30
        fun partTitle(index: Int) = "Part $index"
        fun removeLabel() = "Remove download of $TITLE"
    }
}
