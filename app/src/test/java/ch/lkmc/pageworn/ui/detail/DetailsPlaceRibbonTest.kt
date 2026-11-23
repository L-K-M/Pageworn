package ch.lkmc.pageworn.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.ui.model.BookDetailUiState
import ch.lkmc.pageworn.ui.model.BookFace
import ch.lkmc.pageworn.ui.model.CoverImage
import ch.lkmc.pageworn.ui.model.DetailAction
import ch.lkmc.pageworn.ui.model.StartAction
import ch.lkmc.pageworn.ui.theme.PagewornTheme
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The details cover carries the place ribbon only for a saved place, as Library does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class DetailsPlaceRibbonTest {
    @get:Rule
    val compose = createComposeRule()

    private var start by mutableStateOf(StartAction.PLAY)
    private lateinit var density: Density

    @Test
    fun resumeAddsTheRibbonAndNewAndReplayDoNot() {
        compose.setContent {
            density = LocalDensity.current
            PagewornTheme { screen(state = detail(start)) }
        }

        val newTop = titleTop()
        compose.runOnIdle { start = StartAction.RESUME }
        compose.waitForIdle()
        val resumeTop = titleTop()
        compose.runOnIdle { start = StartAction.REPLAY }
        compose.waitForIdle()
        val replayTop = titleTop()

        // The ribbon hangs below the cover and pushes everything under it down. It is decoration,
        // so its presence is read from the cover's height, never from announced semantics.
        val minimumPeek = with(density) { MIN_RIBBON_PEEK.toPx() }
        assertWithMessage("Resume must draw the place ribbon under the cover").that(resumeTop - newTop).isAtLeast(minimumPeek)
        assertWithMessage("A finished book replays from its beginning without a ribbon").that(replayTop).isEqualTo(newTop)
    }

    private fun titleTop(): Float = compose.onNodeWithText(TITLE).fetchSemanticsNode().boundsInRoot.top

    @Composable
    private fun screen(state: BookDetailUiState) = BookDetailScreen(
        state = state,
        onBack = {},
        onDownload = {},
        onPlayPause = {},
        onRepair = {},
        onPauseTransfer = {},
        onResumeTransfer = {},
        onCancelTransfer = {},
        onSelectSegment = {},
        onRemove = {},
    )

    private fun detail(start: StartAction) = BookDetailUiState(
        face = BookFace(BookKey(PROFILE, ITEM), TITLE, AUTHOR, CoverImage.Cloth),
        narrator = null,
        durationText = "1 h",
        sizeText = "10 MB",
        action = DetailAction.Play(start, showsPause = false, starting = false),
        description = null,
        segmentsTitle = null,
        segments = emptyList(),
        segmentsPlayable = false,
        canRemove = false,
    )

    private companion object {
        const val PROFILE = "profile"
        const val ITEM = "item"
        const val TITLE = "The Night Orchard"
        const val AUTHOR = "A. Author"

        /** BookCover keeps at least this much of the ribbon below the cover edge. */
        val MIN_RIBBON_PEEK = 8.dp
    }
}
