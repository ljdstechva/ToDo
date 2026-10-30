package com.ljdstechva.todo.data

import kotlinx.serialization.Serializable

@Serializable
enum class AlertStyle { NOTIFICATION, ALARM }

/** Light or dark screens; SYSTEM follows the phone's own dark theme setting. */
@Serializable
enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

@Serializable
enum class Repeat(val label: String) {
    ONCE("Once"),
    HOURS_3("Every 3 hours"),
    HOURS_6("Every 6 hours"),
    DAILY("Every day"),
    DAYS_2("Every 2 days"),
    DAYS_3("Every 3 days"),
    WEEKLY("Every week"),
    DAYS_15("Every 15 days"),
    MONTHLY("Every month");

    val isHourly: Boolean get() = this == HOURS_3 || this == HOURS_6
}

/**
 * When and how a to-do reminds. [startEpochDay] is the date of a one-time reminder and the first
 * day of an interval (every 2/3/15 days, every 3/6 hours); [weekday] (ISO, 1 = Monday) is used by
 * [Repeat.WEEKLY] and [monthDay] (1..31, clamped to the month's last day) by [Repeat.MONTHLY].
 */
@Serializable
data class Reminder(
    val style: AlertStyle = AlertStyle.NOTIFICATION,
    val repeat: Repeat = Repeat.DAILY,
    val hour: Int = 9,
    val minute: Int = 0,
    val startEpochDay: Long = 0,
    val weekday: Int = 1,
    val monthDay: Int = 1,
    val tone: String = DEFAULT_TONE,
)

@Serializable
data class Todo(
    val id: Long,
    val title: String,
    val note: String = "",
    val categoryId: Long? = null,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val createdAt: Long = 0,
    val reminder: Reminder? = null,
    /** Next scheduled reminder (epoch millis), kept for display; the alarm itself is in AlarmManager. */
    val nextAt: Long? = null,
    val snoozedUntil: Long? = null,
)

@Serializable
data class Category(
    val id: Long,
    val name: String,
    val color: Int = 0,
)

@Serializable
data class AppSettings(
    val defaultTone: String = DEFAULT_TONE,
    val snoozeMinutes: Int = 10,
    /** 3- and 6-hour reminders skip this window (minutes after midnight; may wrap past midnight). */
    val quietEnabled: Boolean = true,
    val quietStartMinute: Int = 22 * 60,
    val quietEndMinute: Int = 7 * 60,
    val autoUpdate: Boolean = true,
    val lastUpdateCheck: Long = 0,
    val dynamicColor: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
)

@Serializable
data class AppData(
    val todos: List<Todo> = emptyList(),
    val categories: List<Category> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val nextId: Long = 1,
)

const val DEFAULT_TONE = "classic"

/** Open to-dos in the order every list shows them: next reminder first, then oldest first. */
fun List<Todo>.openInOrder(): List<Todo> =
    filter { !it.done }.sortedWith(compareBy<Todo> { it.nextAt ?: Long.MAX_VALUE }.thenBy { it.createdAt })

/** Done to-dos, most recently ticked off first. */
fun List<Todo>.doneInOrder(): List<Todo> = filter { it.done }.sortedByDescending { it.doneAt ?: 0 }
