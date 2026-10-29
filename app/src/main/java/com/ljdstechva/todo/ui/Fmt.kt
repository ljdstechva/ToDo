package com.ljdstechva.todo.ui

import android.content.Context
import android.text.format.DateFormat
import com.ljdstechva.todo.data.Reminder
import com.ljdstechva.todo.data.Repeat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Human-readable times and reminder descriptions. */
object Fmt {
    fun time(context: Context, hour: Int, minute: Int): String {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        return LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
    }

    fun time(context: Context, millis: Long): String {
        val t = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime()
        return time(context, t.hour, t.minute)
    }

    fun day(epochDay: Long): String {
        val today = LocalDate.now().toEpochDay()
        return when (epochDay) {
            today -> "Today"
            today + 1 -> "Tomorrow"
            today - 1 -> "Yesterday"
            else -> LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()))
        }
    }

    /** "Today 3:00 PM", "Tomorrow 9:00 AM", "Thu, Oct 30 9:00 AM". */
    fun moment(context: Context, millis: Long): String {
        val d = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
        return "${day(d)} ${time(context, millis)}"
    }

    fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    fun weekday(isoDay: Int, style: TextStyle = TextStyle.FULL): String =
        DayOfWeek.of(isoDay.coerceIn(1, 7)).getDisplayName(style, Locale.getDefault())

    fun describe(context: Context, r: Reminder): String {
        val t = time(context, r.hour, r.minute)
        return when (r.repeat) {
            Repeat.ONCE -> "${day(r.startEpochDay)} at $t"
            Repeat.HOURS_3 -> "Every 3 hours from $t"
            Repeat.HOURS_6 -> "Every 6 hours from $t"
            Repeat.DAILY -> "Every day at $t"
            Repeat.DAYS_2 -> "Every 2 days at $t"
            Repeat.DAYS_3 -> "Every 3 days at $t"
            Repeat.WEEKLY -> "Every ${weekday(r.weekday)} at $t"
            Repeat.DAYS_15 -> "Every 15 days at $t"
            Repeat.MONTHLY -> "Monthly on the ${ordinal(r.monthDay)} at $t"
        }
    }

    fun minutesOfDay(context: Context, minutes: Int): String = time(context, minutes / 60, minutes % 60)
}
