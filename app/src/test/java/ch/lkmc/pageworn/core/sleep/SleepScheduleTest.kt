package ch.lkmc.pageworn.core.sleep

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The pure schedule rules: a window's half-open bounds, the latest edge at or before now, and how
 * daylight-saving gaps and repeats resolve.
 */
class SleepScheduleTest {
    private val zone = ZoneId.of("Europe/Zurich")

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun utc(text: String): Long = Instant.parse(text).toEpochMilli()

    @Test
    fun anOvernightWindowStartsAtItsStartAndEndsBeforeItsEnd() {
        val window = SleepWindow(22 * 60, 7 * 60)

        assertThat(window.contains(21 * 60 + 59)).isFalse()
        assertThat(window.contains(22 * 60)).isTrue()
        assertThat(window.contains(3 * 60)).isTrue()
        assertThat(window.contains(6 * 60 + 59)).isTrue()
        assertThat(window.contains(7 * 60)).isFalse()
    }

    @Test
    fun aDaytimeWindowStartsAtItsStartAndEndsBeforeItsEnd() {
        val window = SleepWindow(13 * 60, 15 * 60)

        assertThat(window.contains(12 * 60 + 59)).isFalse()
        assertThat(window.contains(13 * 60)).isTrue()
        assertThat(window.contains(14 * 60 + 59)).isTrue()
        assertThat(window.contains(15 * 60)).isFalse()
    }

    @Test
    fun aWindowNeedsDistinctMinutesInsideTheDay() {
        assertThrows(IllegalArgumentException::class.java) { SleepWindow(7 * 60, 7 * 60) }
        assertThrows(IllegalArgumentException::class.java) { SleepWindow(-1, 7 * 60) }
        assertThrows(IllegalArgumentException::class.java) { SleepWindow(22 * 60, 1440) }
    }

    @Test
    fun theLatestEdgeAtNightIsThatEveningsStart() {
        val window = SleepWindow(22 * 60, 7 * 60)

        val edge = SleepSchedule.latestEdge(window, millis(2026, 11, 23, 23, 0), zone)

        assertThat(edge.atMillis).isEqualTo(millis(2026, 11, 23, 22, 0))
        assertThat(edge.turnsOn).isTrue()
    }

    @Test
    fun theLatestEdgeBeforeDawnIsThePreviousEveningsStart() {
        val window = SleepWindow(22 * 60, 7 * 60)

        val edge = SleepSchedule.latestEdge(window, millis(2026, 11, 24, 6, 0), zone)

        assertThat(edge.atMillis).isEqualTo(millis(2026, 11, 23, 22, 0))
        assertThat(edge.turnsOn).isTrue()
    }

    @Test
    fun anEdgeAtExactlyNowIsThatEdge() {
        val window = SleepWindow(22 * 60, 7 * 60)

        val start = SleepSchedule.latestEdge(window, millis(2026, 11, 23, 22, 0), zone)
        assertThat(start.atMillis).isEqualTo(millis(2026, 11, 23, 22, 0))
        assertThat(start.turnsOn).isTrue()

        val end = SleepSchedule.latestEdge(window, millis(2026, 11, 24, 7, 0), zone)
        assertThat(end.atMillis).isEqualTo(millis(2026, 11, 24, 7, 0))
        assertThat(end.turnsOn).isFalse()
    }

    @Test
    fun theLatestEdgeAtNoonIsThatMorningsEnd() {
        val window = SleepWindow(22 * 60, 7 * 60)

        val edge = SleepSchedule.latestEdge(window, millis(2026, 11, 24, 12, 0), zone)

        assertThat(edge.atMillis).isEqualTo(millis(2026, 11, 24, 7, 0))
        assertThat(edge.turnsOn).isFalse()
    }

    @Test
    fun isInsideFollowsTheWindowBounds() {
        val window = SleepWindow(22 * 60, 7 * 60)

        assertThat(SleepSchedule.isInside(window, millis(2026, 11, 23, 23, 0), zone)).isTrue()
        assertThat(SleepSchedule.isInside(window, millis(2026, 11, 24, 12, 0), zone)).isFalse()
    }

    @Test
    fun aSkippedStartTimeMovesLaterByTheGap() {
        val window = SleepWindow(2 * 60 + 30, 6 * 60)

        val edge = SleepSchedule.latestEdge(window, millis(2026, 3, 29, 5, 0), zone)

        assertThat(edge.atMillis).isEqualTo(utc("2026-03-29T01:30:00Z"))
        assertThat(edge.turnsOn).isTrue()
    }

    @Test
    fun aRepeatedStartTimeCountsOnceAtItsFirstOccurrence() {
        val window = SleepWindow(2 * 60 + 30, 6 * 60)

        val edge = SleepSchedule.latestEdge(window, utc("2026-10-25T04:00:00Z"), zone)

        assertThat(edge.atMillis).isEqualTo(utc("2026-10-25T00:30:00Z"))
        assertThat(edge.turnsOn).isTrue()
    }
}
