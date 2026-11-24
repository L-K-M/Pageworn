package ch.lkmc.pageworn.ui.player

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.ui.detail.BookDetailScreen
import ch.lkmc.pageworn.ui.model.BookDetailUiState
import ch.lkmc.pageworn.ui.model.DetailAction
import ch.lkmc.pageworn.ui.model.StartAction
import com.google.common.truth.Truth.assertThat
import ch.lkmc.pageworn.core.timeline.BookTimeline
import ch.lkmc.pageworn.playback.EditionPlan
import ch.lkmc.pageworn.playback.LivePlayback
import ch.lkmc.pageworn.ui.model.Copy
import ch.lkmc.pageworn.ui.state.PlayerStates
import ch.lkmc.pageworn.ui.theme.PagewornTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** docs/01: "Couldn't start playback" offers Play again, or Dismiss, wherever the book was tapped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayNoticeDismissTest {
    @get:Rule
    val compose = createComposeRule()

    private val key = BookKey("profile", "item")
    private val timeline = BookTimeline(BookTimeline.contiguousTracks(listOf(60 * MINUTE)), emptyList())
    private val plan = EditionPlan(key, "gen", "fingerprint", "The Night Orchard", "Elin Ward", null, listOf(File("000.mp3")), timeline)

    @Test
    fun thePlayerOffersDismissBesideAFailedStart() {
        var dismissed = 0
        val state = PlayerStates.player(plan, null, null, LivePlayback()).copy(playNotice = Copy.PLAY_DID_NOT_START)
        compose.setContent {
            PagewornTheme {
                PlayerScreen(state = state, onCollapse = {}, onPlayPause = {}, onSkipBack = {}, onSkipForward = {}, onSeekTo = {}, onSelectSpeed = {}, onSelectSegment = {}, onSelectSleep = {}, onRepair = {}, onUsePreviousPosition = {}, onStartOver = {}, onDismissPlayNotice = { dismissed++ })
            }
        }

        compose.onNodeWithText(Copy.PLAY_DID_NOT_START).assertExists()
        compose.onNodeWithText(Copy.DISMISS).performScrollTo().performClick()
        assertThat(dismissed).isEqualTo(1)
    }

    @Test
    fun detailsOfferDismissInsteadOfTheirMessage() {
        val state = mutableStateOf(BookDetailUiState(
            face = PlayerStates.face(plan), narrator = null, durationText = "1 h 00 min", sizeText = null,
            action = DetailAction.Play(StartAction.RESUME, showsPause = false, starting = false),
            message = Copy.AUDIO_REMOVED, playNotice = Copy.PLAY_DID_NOT_START,
            description = null, segmentsTitle = null, segments = emptyList(), segmentsPlayable = true, canRemove = true,
        ))
        compose.setContent {
            PagewornTheme {
                // Dismissing clears the problem, so the next state carries no notice.
                BookDetailScreen(state.value, onBack = {}, onDownload = {}, onRetry = {}, onPlayPause = {}, onRepair = {}, onPauseTransfer = {}, onResumeTransfer = {}, onCancelTransfer = {}, onSelectSegment = {}, onRemove = {}, onDismissPlayNotice = { state.value = state.value.copy(playNotice = null) })
            }
        }

        compose.onNodeWithText(Copy.PLAY_DID_NOT_START).assertExists()
        compose.onNodeWithText(Copy.AUDIO_REMOVED).assertDoesNotExist()
        compose.onNodeWithText(Copy.DISMISS).performScrollTo().performClick()

        compose.onNodeWithText(Copy.PLAY_DID_NOT_START).assertDoesNotExist()
        compose.onNodeWithText(Copy.DISMISS).assertDoesNotExist()
        compose.onNodeWithText(Copy.AUDIO_REMOVED).assertExists()
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
