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
 * The schedule's rules in local time. Each day has one start and one end. A time that a
 * daylight-saving change skips moves later by the length of the change (02:30 becomes 03:30), and
 * a time it repeats counts once, at its first occurrence.
 */
object SleepSchedule {

    fun isInside(window: SleepWindow, nowMillis: Long, zone: ZoneId): Boolean {
        val local = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalTime()
        return window.contains(local.hour * 60 + local.minute)
    }

    /**
     * The latest start or end of [window] at or before [nowMillis]. Both happen once every day,
     * so the latest is always among yesterday's and today's.
     */
    fun latestEdge(window: SleepWindow, nowMillis: Long, zone: ZoneId): SleepEdge {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return listOf(today.minusDays(1), today)
            .flatMap { day -> listOf(SleepEdge(at(day, window.startMinute, zone), true), SleepEdge(at(day, window.endMinute, zone), false)) }
            .filter { it.atMillis <= nowMillis }
            .maxBy { it.atMillis }
    }

    private fun at(day: LocalDate, minuteOfDay: Int, zone: ZoneId): Long =
        ZonedDateTime.of(day, LocalTime.of(minuteOfDay / 60, minuteOfDay % 60), zone).toInstant().toEpochMilli()
}
