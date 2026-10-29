package com.ljdstechva.todo.reminder

import com.ljdstechva.todo.data.AlertStyle
import com.ljdstechva.todo.data.Reminder
import com.ljdstechva.todo.data.Repeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** 2026-10-29 is a Thursday. All times are Manila time (no daylight saving). */
class ReminderMathTest {
    private val zone = ZoneId.of("Asia/Manila")
    private val quiet = ReminderMath.Quiet(22 * 60, 7 * 60)

    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()
    private fun day(text: String) = LocalDate.parse(text).toEpochDay()

    private fun reminder(
        repeat: Repeat,
        start: String = "2026-10-29",
        hour: Int = 9,
        minute: Int = 0,
        weekday: Int = 1,
        monthDay: Int = 1,
    ) = Reminder(AlertStyle.NOTIFICATION, repeat, hour, minute, day(start), weekday, monthDay)

    private fun next(r: Reminder, after: String, q: ReminderMath.Quiet? = null) = ReminderMath.next(r, at(after), zone, q)

    @Test fun onceInTheFuture() =
        assertEquals(at("2026-10-29T09:00"), next(reminder(Repeat.ONCE), "2026-10-29T08:00"))

    @Test fun oncePassedIsNull() =
        assertNull(next(reminder(Repeat.ONCE), "2026-10-29T09:00"))

    @Test fun dailyLaterToday() =
        assertEquals(at("2026-10-29T09:00"), next(reminder(Repeat.DAILY), "2026-10-29T06:30"))

    @Test fun dailyAfterTimeGoesToTomorrow() =
        assertEquals(at("2026-10-30T09:00"), next(reminder(Repeat.DAILY), "2026-10-29T09:00"))

    @Test fun dailyWithFutureStart() =
        assertEquals(at("2026-11-05T09:00"), next(reminder(Repeat.DAILY, start = "2026-11-05"), "2026-10-29T10:00"))

    @Test fun everyTwoDaysKeepsItsRhythm() {
        val r = reminder(Repeat.DAYS_2, start = "2026-10-27")
        assertEquals(at("2026-10-29T09:00"), next(r, "2026-10-29T08:00"))
        assertEquals(at("2026-10-31T09:00"), next(r, "2026-10-29T10:00"))
        assertEquals(at("2026-10-31T09:00"), next(r, "2026-10-30T10:00"))
    }

    @Test fun everyThreeDays() =
        assertEquals(at("2026-10-30T09:00"), next(reminder(Repeat.DAYS_3, start = "2026-10-27"), "2026-10-29T08:00"))

    @Test fun everyFifteenDays() =
        assertEquals(at("2026-10-31T09:00"), next(reminder(Repeat.DAYS_15, start = "2026-10-01"), "2026-10-29T08:00"))

    @Test fun weeklyOnMonday() =
        assertEquals(at("2026-11-02T09:00"), next(reminder(Repeat.WEEKLY, weekday = 1), "2026-10-29T08:00"))

    @Test fun weeklyTodayAfterTimeGoesToNextWeek() =
        assertEquals(at("2026-11-05T09:00"), next(reminder(Repeat.WEEKLY, weekday = 4), "2026-10-29T10:00"))

    @Test fun weeklyTodayBeforeTime() =
        assertEquals(at("2026-10-29T09:00"), next(reminder(Repeat.WEEKLY, weekday = 4), "2026-10-29T08:00"))

    @Test fun monthlyNextMonth() =
        assertEquals(at("2026-11-15T09:00"), next(reminder(Repeat.MONTHLY, monthDay = 15), "2026-10-29T08:00"))

    @Test fun monthlyThisMonth() =
        assertEquals(at("2026-10-30T09:00"), next(reminder(Repeat.MONTHLY, monthDay = 30), "2026-10-29T08:00"))

    @Test fun monthlyDay31InFebruaryUsesLastDay() =
        assertEquals(
            at("2027-02-28T09:00"),
            next(reminder(Repeat.MONTHLY, start = "2027-02-01", monthDay = 31), "2027-02-01T08:00"),
        )

    @Test fun monthlyDay31AfterFebruaryComesBack() =
        assertEquals(
            at("2027-03-31T09:00"),
            next(reminder(Repeat.MONTHLY, start = "2027-02-01", monthDay = 31), "2027-02-28T10:00"),
        )

    @Test fun everyThreeHours() {
        val r = reminder(Repeat.HOURS_3)
        assertEquals(at("2026-10-29T09:00"), next(r, "2026-10-29T08:00"))
        assertEquals(at("2026-10-29T12:00"), next(r, "2026-10-29T09:00"))
        assertEquals(at("2026-10-29T12:00"), next(r, "2026-10-29T10:15"))
        assertEquals(at("2026-10-30T00:00"), next(r, "2026-10-29T21:30"))
    }

    @Test fun everyThreeHoursSkipsQuietHours() =
        // 00:00, 03:00 and 06:00 fall inside 22:00-07:00.
        assertEquals(at("2026-10-30T09:00"), next(reminder(Repeat.HOURS_3), "2026-10-29T21:30", quiet))

    @Test fun everySixHoursSkipsQuietHours() {
        val r = reminder(Repeat.HOURS_6)
        assertEquals(at("2026-10-29T21:00"), next(r, "2026-10-29T15:00", quiet))
        assertEquals(at("2026-10-30T09:00"), next(r, "2026-10-29T21:00", quiet))
    }

    @Test fun quietWindowWrapsMidnight() {
        assertTrue(quiet.contains(LocalTime.of(23, 0)))
        assertTrue(quiet.contains(LocalTime.of(2, 0)))
        assertTrue(quiet.contains(LocalTime.of(6, 59)))
        assertFalse(quiet.contains(LocalTime.of(7, 0)))
        assertFalse(quiet.contains(LocalTime.of(21, 59)))
        assertFalse(ReminderMath.Quiet(600, 600).contains(LocalTime.of(10, 0)))
    }

    @Test fun quietWindowSameDay() {
        val q = ReminderMath.Quiet(12 * 60, 13 * 60)
        assertTrue(q.contains(LocalTime.of(12, 30)))
        assertFalse(q.contains(LocalTime.of(13, 0)))
    }

    @Test fun neverReturnsTheSameMoment() {
        Repeat.entries.filter { it != Repeat.ONCE }.forEach { rep ->
            val r = reminder(rep, weekday = 4, monthDay = 29)
            val first = next(r, "2026-10-29T08:59")!!
            val second = ReminderMath.next(r, first, zone)!!
            assertTrue("$rep repeats strictly later", second.isAfter(first))
        }
    }
}
