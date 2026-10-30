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
    /** Set on a to-do that comes from the Google Sheet: the sync's key for its line there. */
    val sheetKey: String? = null,
    /** Opens the line's row in the sheet. */
    val sheetLink: String? = null,
    /** Ticked off by the sync because the line left the sheet; it reopens if the line comes back. */
    val sheetDone: Boolean = false,
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
    /** The Google Sheet's web app link; empty when no sheet is connected. */
    val sheetUrl: String = "",
)

/** One line of the sheet's to-do list, as its web app sends it. */
@Serializable
data class SheetLine(
    val where: String = "",
    val date: String = "",
    val amount: String = "",
    val what: String = "",
    val link: String? = null,
)

/** What the sheet's web app answers. [count] is how many lines the sheet says it has. */
@Serializable
data class SheetResponse(
    val ok: Boolean = false,
    val error: String? = null,
    val sheet: String = "",
    val url: String = "",
    val count: Int? = null,
    val items: List<SheetLine> = emptyList(),
)

/** How the Google Sheet sync went last time, and the lines the user deleted from the app. */
@Serializable
data class SheetState(
    val name: String = "",
    val url: String = "",
    val lastSync: Long = 0,
    val lastError: String? = null,
    val openLines: Int = 0,
    /** Keys of lines deleted here while still on the sheet: the sync does not bring them back. */
    val removed: Set<String> = emptySet(),
)

@Serializable
data class AppData(
    val todos: List<Todo> = emptyList(),
    val categories: List<Category> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val nextId: Long = 1,
    val sheet: SheetState = SheetState(),
)

const val DEFAULT_TONE = "classic"

/** Open to-dos in the order every list shows them: next reminder first, then oldest first. */
fun List<Todo>.openInOrder(): List<Todo> =
    filter { !it.done }.sortedWith(compareBy<Todo> { it.nextAt ?: Long.MAX_VALUE }.thenBy { it.createdAt })

/** Done to-dos, most recently ticked off first. */
fun List<Todo>.doneInOrder(): List<Todo> = filter { it.done }.sortedByDescending { it.doneAt ?: 0 }
