package ch.lkmc.pageworn.ui.state

import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.timeline.BookTimeline
import ch.lkmc.pageworn.data.db.EditionState
import ch.lkmc.pageworn.data.db.LocalEditionEntity
import ch.lkmc.pageworn.playback.EditionPlan
import ch.lkmc.pageworn.playback.LivePlayback
import ch.lkmc.pageworn.ui.model.Copy
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * A playback failure disables the player's and mini-player's keys for the generation that failed
 * (playback failure handling in docs/05-playback-and-formats.md), and only for that one.
 */
class PlayerFailureStateTest {
    private val key = BookKey("profile", "item")
    private val timeline = BookTimeline(BookTimeline.contiguousTracks(listOf(90_000L, 120_000L)), emptyList())

    private fun plan(generationId: String) =
        EditionPlan(key, generationId, "fingerprint", "Title", "Author", null, listOf(File("000.m4a"), File("001.m4a")), timeline)

    private fun edition(generationId: String, state: EditionState = EditionState.READY) = LocalEditionEntity(
        key.profileId, key.itemId, generationId, "fingerprint", state, 210_000, "dir", null, "Title", "Author",
        narrators = null, description = null, chapterSource = null, publishedAt = 0, sizeBytes = 0,
    )

    private fun failedOn(generationId: String) = LivePlayback(key = key, plan = plan(generationId), positionMs = 100_000, failed = true)

    @Test
    fun aFailureOnThePublishedEditionDisablesTheKeysBeforeRepairNeededIsStored() {
        val live = failedOn("gen-1")
        val player = PlayerStates.player(plan("gen-1"), edition("gen-1"), null, live)
        assertThat(player.failureMessage).isEqualTo(Copy.PLAYBACK_STOPPED)
        assertThat(player.transportEnabled).isFalse()
        val mini = PlayerStates.mini(plan("gen-1"), edition("gen-1"), null, live)
        assertThat(mini.enabled).isFalse()
        assertThat(mini.disabledReason).isEqualTo(Copy.PLAYBACK_STOPPED)
    }

    @Test
    fun aRepairedEditionIsNotHeldByTheFailureOfTheOneItReplaced() {
        val live = failedOn("gen-1")
        val player = PlayerStates.player(plan("gen-2"), edition("gen-2"), null, live)
        assertThat(player.failureMessage).isNull()
        assertThat(player.transportEnabled).isTrue()
        val mini = PlayerStates.mini(plan("gen-2"), edition("gen-2"), null, live)
        assertThat(mini.enabled).isTrue()
        assertThat(mini.disabledReason).isNull()
    }

    @Test
    fun anEditionMarkedForRepairStaysDisabledWithoutALiveFailure() {
        val player = PlayerStates.player(plan("gen-1"), edition("gen-1", EditionState.REPAIR_NEEDED), null, LivePlayback())
        assertThat(player.failureMessage).isEqualTo(Copy.PLAYBACK_STOPPED)
        assertThat(player.transportEnabled).isFalse()
    }
}
