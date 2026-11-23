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
    fun aShiftedStartAtOrAfterItsEndSkipsThatWindow() {
        for (endMinute in listOf(3 * 60 + 15, 3 * 60 + 30)) {
            val window = SleepWindow(2 * 60 + 30, endMinute)
            val afterTheMovedStart = millis(2026, 3, 29, 3, 31)

            val edge = SleepSchedule.latestEdge(window, afterTheMovedStart, zone)
            assertThat(edge.turnsOn).isFalse()
            assertThat(edge.atMillis).isEqualTo(millis(2026, 3, 29, 3, endMinute % 60))
            assertThat(SleepSchedule.isInside(window, millis(2026, 3, 30, 2, 31), zone)).isTrue()
        }
    }

    @Test
    fun anOvernightWindowCanBeginWhenTheShiftedPreviousWindowEnds() {
        val window = SleepWindow(3 * 60 + 15, 2 * 60 + 15)
        val sharedEdge = millis(2026, 3, 29, 3, 15)

        assertThat(SleepSchedule.latestEdge(window, sharedEdge, zone)).isEqualTo(SleepEdge(sharedEdge, true))
    }

    @Test
    fun aRepeatedStartTimeCountsOnceAtItsFirstOccurrence() {
        val window = SleepWindow(2 * 60 + 30, 6 * 60)

        val edge = SleepSchedule.latestEdge(window, utc("2026-10-25T04:00:00Z"), zone)

        assertThat(edge.atMillis).isEqualTo(utc("2026-10-25T00:30:00Z"))
        assertThat(edge.turnsOn).isTrue()
    }

    @Test
    fun isInsideAgreesWithTheEdgesWhenAStartIsSkipped() {
        // 29 March 2026 skips 02:00 to 03:00 in Zurich, so a 02:30 start falls at 03:30.
        val window = SleepWindow(2 * 60 + 30, 7 * 60)
        val beforeTheMovedStart = utc("2026-03-29T01:15:00Z") // 03:15 local

        assertThat(SleepSchedule.latestEdge(window, beforeTheMovedStart, zone).turnsOn).isFalse()
        assertThat(SleepSchedule.isInside(window, beforeTheMovedStart, zone)).isFalse()
        assertThat(SleepSchedule.isInside(window, utc("2026-03-29T01:30:00Z"), zone)).isTrue()
    }

    @Test
    fun theNextEdgeOfAnOvernightWindowBeforeItsStartIsThatEveningsStart() {
        val window = SleepWindow(22 * 60, 7 * 60)

        val edge = SleepSchedule.nextEdge(window, millis(2026, 11, 24, 12, 0), zone)

        assertThat(edge.atMillis).isEqualTo(millis(2026, 11, 24, 22, 0))
        assertThat(edge.turnsOn).isTrue()
    }

    @Test
    fun theNextEdgeInsideAnOvernightWindowIsItsEnd() {
        val window = SleepWindow(22 * 60, 7 * 60)

        val edge = SleepSchedule.nextEdge(window, millis(2026, 11, 24, 23, 0), zone)

        assertThat(edge.atMillis).isEqualTo(millis(2026, 11, 25, 7, 0))
        assertThat(edge.turnsOn).isFalse()
    }

    @Test
    fun theNextEdgeOfADaytimeWindowAfterItsEndIsTomorrowsStart() {
        val window = SleepWindow(13 * 60, 15 * 60)

        val edge = SleepSchedule.nextEdge(window, millis(2026, 11, 24, 16, 0), zone)

        assertThat(edge.atMillis).isEqualTo(millis(2026, 11, 25, 13, 0))
        assertThat(edge.turnsOn).isTrue()
    }

    @Test
    fun anEdgeAtExactlyNowIsNotTheNextOne() {
        val window = SleepWindow(22 * 60, 7 * 60)

        val edge = SleepSchedule.nextEdge(window, millis(2026, 11, 23, 22, 0), zone)

        assertThat(edge.atMillis).isEqualTo(millis(2026, 11, 24, 7, 0))
        assertThat(edge.turnsOn).isFalse()
    }

    @Test
    fun theNextEdgeAfterMidnightInsideAnOvernightWindowIsThatMorningsEnd() {
        val window = SleepWindow(22 * 60, 7 * 60)

        val edge = SleepSchedule.nextEdge(window, millis(2026, 11, 25, 1, 0), zone)

        assertThat(edge.atMillis).isEqualTo(millis(2026, 11, 25, 7, 0))
        assertThat(edge.turnsOn).isFalse()
    }

    @Test
    fun theNextEdgeAcrossTheDstGapIsAResolvedFutureInstant() {
        val window = SleepWindow(1 * 60 + 30, 3 * 60)

        val edge = SleepSchedule.nextEdge(window, utc("2026-03-29T00:59:00Z"), zone)

        assertThat(edge.atMillis).isGreaterThan(utc("2026-03-29T00:59:00Z"))
        assertThat(edge.atMillis).isLessThan(utc("2026-03-29T03:00:00Z"))
    }
}
