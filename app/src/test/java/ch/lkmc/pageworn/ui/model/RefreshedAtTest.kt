package ch.lkmc.pageworn.ui.model

import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Test

class RefreshedAtTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private val now = millis(2026, 11, 23, 9, 5)

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun refreshedAt(atMs: Long) = Format.refreshedAt(atMs, now, zone, Locale.US)

    @Test fun aRefreshEarlierTodaySaysToday() {
        assertThat(refreshedAt(millis(2026, 11, 23, 0, 10))).isEqualTo("today at 12:10 AM")
    }

    @Test fun aRefreshBeforeMidnightSaysYesterday() {
        assertThat(refreshedAt(millis(2026, 11, 22, 20, 25))).isEqualTo("yesterday at 8:25 PM")
    }

    @Test fun anOlderRefreshThisYearGivesTheDay() {
        assertThat(refreshedAt(millis(2026, 11, 20, 20, 25))).isEqualTo("on Nov 20 at 8:25 PM")
    }

    @Test fun aRefreshInAnotherYearGivesTheYear() {
        assertThat(refreshedAt(millis(2025, 12, 31, 7, 0))).isEqualTo("on Dec 31, 2025 at 7:00 AM")
    }

    @Test fun theDayIsTheListenersLocalDay() {
        // 23:30 in Zurich on the 22nd is already the 23rd in Tokyo.
        val late = millis(2026, 11, 22, 23, 30)
        assertThat(Format.refreshedAt(late, now, ZoneId.of("Asia/Tokyo"), Locale.US)).startsWith("today at ")
        assertThat(Format.refreshedAt(late, now, zone, Locale.US)).startsWith("yesterday at ")
    }

    @Test fun theStatusLineReadsAsASentence() {
        assertThat(Copy.lastRefresh(refreshedAt(millis(2026, 11, 23, 8, 25)))).isEqualTo("Last refreshed today at 8:25 AM.")
    }
}
