package com.ljdstechva.todo.reminder

import com.ljdstechva.todo.data.AppSettings
import com.ljdstechva.todo.data.Reminder
import com.ljdstechva.todo.data.Repeat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Pure scheduling maths: when does a reminder ring next? No Android classes, unit-tested. */
object ReminderMath {

    /** A daily window (minutes after midnight) in which 3/6-hour reminders stay silent. */
    data class Quiet(val startMinute: Int, val endMinute: Int) {
        fun contains(time: LocalTime): Boolean {
            val m = time.hour * 60 + time.minute
            return when {
                startMinute == endMinute -> false
                startMinute < endMinute -> m in startMinute until endMinute
                else -> m >= startMinute || m < endMinute
            }
        }
    }

    fun quietOf(settings: AppSettings): Quiet? =
        if (settings.quietEnabled) Quiet(settings.quietStartMinute, settings.quietEndMinute) else null

    /** The first moment strictly after [after] at which [r] rings, or null when it never will again. */
    fun next(r: Reminder, after: Instant, zone: ZoneId, quiet: Quiet? = null): Instant? {
        val time = LocalTime.of(r.hour.coerceIn(0, 23), r.minute.coerceIn(0, 59))
        val start = LocalDate.ofEpochDay(r.startEpochDay)
        val today = after.atZone(zone).toLocalDate()
        val from = if (today.isBefore(start)) start else today
        fun at(d: LocalDate): Instant = d.atTime(time).atZone(zone).toInstant()

        return when (r.repeat) {
            Repeat.ONCE -> at(start).takeIf { it.isAfter(after) }
            Repeat.HOURS_3 -> hourly(at(start), 3, after, zone, quiet)
            Repeat.HOURS_6 -> hourly(at(start), 6, after, zone, quiet)
            Repeat.DAILY -> everyDays(start, 1, today, after) { at(it) }
            Repeat.DAYS_2 -> everyDays(start, 2, today, after) { at(it) }
            Repeat.DAYS_3 -> everyDays(start, 3, today, after) { at(it) }
            Repeat.DAYS_15 -> everyDays(start, 15, today, after) { at(it) }
            Repeat.WEEKLY -> {
                var d = from.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(r.weekday.coerceIn(1, 7))))
                while (!at(d).isAfter(after)) d = d.plusWeeks(1)
                at(d)
            }
            Repeat.MONTHLY -> {
                var month = YearMonth.from(from)
                repeat(24) {
                    val d = month.atDay(minOf(r.monthDay.coerceIn(1, 31), month.lengthOfMonth()))
                    if (!d.isBefore(start) && at(d).isAfter(after)) return at(d)
                    month = month.plusMonths(1)
                }
                null
            }
        }
    }

    private fun everyDays(start: LocalDate, n: Int, today: LocalDate, after: Instant, at: (LocalDate) -> Instant): Instant {
        var d = if (today.isAfter(start)) {
            val diff = ChronoUnit.DAYS.between(start, today)
            start.plusDays(((diff + n - 1) / n) * n)
        } else {
            start
        }
        while (!at(d).isAfter(after)) d = d.plusDays(n.toLong())
        return at(d)
    }

    private fun hourly(anchor: Instant, hours: Int, after: Instant, zone: ZoneId, quiet: Quiet?): Instant {
        val step = hours * 3_600_000L
        val a = anchor.toEpochMilli()
        var t = if (after.toEpochMilli() < a) a else a + ((after.toEpochMilli() - a) / step + 1) * step
        if (quiet != null) {
            repeat(48) {
                if (!quiet.contains(Instant.ofEpochMilli(t).atZone(zone).toLocalTime())) return Instant.ofEpochMilli(t)
                t += step
            }
        }
        return Instant.ofEpochMilli(t)
    }
}
