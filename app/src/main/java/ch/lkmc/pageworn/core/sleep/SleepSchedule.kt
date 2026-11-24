package ch.lkmc.pageworn.core.sleep

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** How long one sleep timer runs once a book starts playing. */
sealed interface SleepLength {
    /** A wall-clock duration. */
    data class Minutes(val minutes: Int) : SleepLength {
        init {
            require(minutes in MIN_MINUTES..MAX_MINUTES) { "sleep length out of range: $minutes" }
        }
    }

    /** Until the end of the chapter, track or part that is playing. */
    data object EndOfSegment : SleepLength

    companion object {
        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 12 * 60
    }
}

/**
 * A daily stretch of local time during which sleep mode turns itself on, as minutes after
 * midnight. A window whose end is earlier than its start runs overnight: 22:00 to 07:00 is
 * `SleepWindow(1320, 420)`. The start belongs to the window and the end does not.
 */
data class SleepWindow(val startMinute: Int, val endMinute: Int) {
    init {
        require(startMinute in 0 until MINUTES_PER_DAY && endMinute in 0 until MINUTES_PER_DAY) { "minute of day out of range" }
        require(startMinute != endMinute) { "a sleep window needs a start and an end that differ" }
    }

    fun contains(minuteOfDay: Int): Boolean =
        if (startMinute < endMinute) minuteOfDay in startMinute until endMinute
        else minuteOfDay >= startMinute || minuteOfDay < endMinute

    companion object {
        const val MINUTES_PER_DAY = 24 * 60

        /** 22:00 to 07:00. */
        val DEFAULT = SleepWindow(22 * 60, 7 * 60)
    }
}

/** A moment at which a [SleepWindow] began ([turnsOn]) or ended. */
data class SleepEdge(val atMillis: Long, val turnsOn: Boolean)

/**
 * The schedule's rules in local time. A time that a
 * daylight-saving change skips moves later by the length of the change (02:30 becomes 03:30), and
 * a time it repeats counts once, at its first occurrence. A start that falls at or after its
 * resolved end is skipped, while the end still turns the mode off.
 */
object SleepSchedule {

    /** Whether the window's latest edge was a start, so daylight-saving shifts move both alike. */
    fun isInside(window: SleepWindow, nowMillis: Long, zone: ZoneId): Boolean =
        latestEdge(window, nowMillis, zone).turnsOn

    /**
     * The latest start or end of [window] at or before [nowMillis]. The daily end still occurs
     * when a start is skipped, so only yesterday's and today's edges are needed.
     */
    fun latestEdge(window: SleepWindow, nowMillis: Long, zone: ZoneId): SleepEdge {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return listOf(today.minusDays(1), today)
            .flatMap { day -> edgesOn(day, window, zone) }
            .filter { it.atMillis <= nowMillis }
            // An overnight window can start exactly when a shifted earlier window ends.
            .maxWith(compareBy<SleepEdge> { it.atMillis }.thenBy { it.turnsOn })
    }

    /**
     * The earliest start or end of [window] strictly after [nowMillis]: the moment a waiter
     * sleeps until instead of polling. A daily window always has one within two days; the day
     * before today is included so an overnight window's end after local midnight is seen.
     */
    fun nextEdge(window: SleepWindow, nowMillis: Long, zone: ZoneId): SleepEdge {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return listOf(today.minusDays(1), today, today.plusDays(1), today.plusDays(2))
            .flatMap { day -> edgesOn(day, window, zone) }
            .filter { it.atMillis > nowMillis }
            .minWithOrNull(compareBy<SleepEdge> { it.atMillis }.thenBy { it.turnsOn })
            ?: error("No sleep-schedule edge within two days of $nowMillis for $window")
    }

    private fun edgesOn(day: LocalDate, window: SleepWindow, zone: ZoneId): List<SleepEdge> {
        val start = at(day, window.startMinute, zone)
        val end = at(day, window.endMinute, zone)
        val endOfThisWindow = if (window.startMinute < window.endMinute) end
            else at(day.plusDays(1), window.endMinute, zone)
        return buildList {
            if (start < endOfThisWindow) add(SleepEdge(start, true))
            add(SleepEdge(end, false))
        }
    }

    private fun at(day: LocalDate, minuteOfDay: Int, zone: ZoneId): Long =
        ZonedDateTime.of(day, LocalTime.of(minuteOfDay / 60, minuteOfDay % 60), zone).toInstant().toEpochMilli()
}
