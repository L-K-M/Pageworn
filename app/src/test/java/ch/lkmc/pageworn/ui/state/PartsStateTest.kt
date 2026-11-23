package ch.lkmc.pageworn.ui.state

import ch.lkmc.pageworn.core.BookKey
import ch.lkmc.pageworn.core.timeline.BookTimeline
import ch.lkmc.pageworn.playback.EditionPlan
import ch.lkmc.pageworn.playback.LivePlayback
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/** A24: a single file without chapters reads as ten-minute parts everywhere chapters would show. */
class PartsStateTest {
    private val key = BookKey("profile", "item")
    private val durationMs = 260 * MINUTE
    private val timeline = BookTimeline(BookTimeline.contiguousTracks(listOf(durationMs)), emptyList())
    private val plan = EditionPlan(key, "gen", "fingerprint", "Title", "Author", null, listOf(File("000.mp3")), timeline)
    private val live = LivePlayback(key = key, plan = plan, positionMs = 128 * MINUTE)

    @Test
    fun a24ThePlayerHasAPartAndABookScale() {
        val player = PlayerStates.player(plan, null, null, live)
        assertThat(player.segmentLine).isEqualTo("Part 13")
        assertThat(player.segmentStartMs).isEqualTo(120 * MINUTE)
        assertThat(player.segmentEndMs).isEqualTo(130 * MINUTE)
        assertThat(player.elapsedText).isEqualTo("8:00")
        assertThat(player.remainingText).isEqualTo("−2:00")
        assertThat(player.bookLine).isEqualTo("Book · 2 h 08 min of 4 h 20 min")
        assertThat(player.segmentsLabel).isEqualTo("Parts")
        assertThat(player.segments).hasSize(26)
        assertThat(player.segments.single { it.current }.title).isEqualTo("Part 13")
        assertThat(player.sleep.endLabel).isEqualTo("End of part")
    }

    @Test
    fun a24RowsAndTheMiniPlayerNameThePart() {
        assertThat(PlayerStates.mini(plan, null, null, live).subtitle).isEqualTo("Part 13")
        assertThat(RowBuilders.progressText(timeline, live.positionMs, durationMs)).isEqualTo("Part 13 · 2 h 12 min left")
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
