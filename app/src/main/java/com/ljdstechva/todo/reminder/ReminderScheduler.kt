package com.ljdstechva.todo.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.ljdstechva.todo.data.AlertStyle
import com.ljdstechva.todo.data.AppSettings
import com.ljdstechva.todo.data.Repeat
import com.ljdstechva.todo.data.Todo
import com.ljdstechva.todo.reminder.Notifications.openAppIntent
import java.time.Instant
import java.time.ZoneId

/**
 * Puts reminders into AlarmManager. Alarm-style reminders use setAlarmClock (shown as the next
 * alarm, never delayed by Doze); notification-style ones use exact allow-while-idle alarms. Every
 * to-do has at most one "fire" alarm and one "snooze" alarm, told apart by the intent's data URI.
 */
object ReminderScheduler {
    private const val TAG = "ReminderScheduler"
    const val KIND_FIRE = ReminderReceiver.ACTION_FIRE
    const val KIND_SNOOZE = ReminderReceiver.ACTION_SNOOZE_FIRE
    const val KIND_PREVIEW = ReminderReceiver.ACTION_PREVIEW

    private fun alarmManager(context: Context) = context.getSystemService(AlarmManager::class.java)

    fun canExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || alarmManager(context).canScheduleExactAlarms()

    private fun intent(context: Context, todoId: Long, kind: String): Intent =
        Intent(context, ReminderReceiver::class.java)
            .setAction(kind)
            .setData(Uri.parse("todo://$kind/$todoId"))
            .putExtra(AlertRequest.EXTRA_ID, todoId)

    private fun pending(context: Context, todoId: Long, kind: String, flags: Int, extras: AlertRequest? = null): PendingIntent? {
        val i = intent(context, todoId, kind)
        extras?.putInto(i)
        return PendingIntent.getBroadcast(context, todoId.toInt(), i, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    /** The scheduled alarm of this kind, or null (used by tests and the reliability check). */
    fun pendingAlarm(context: Context, todoId: Long, kind: String = KIND_FIRE): PendingIntent? =
        pending(context, todoId, kind, PendingIntent.FLAG_NO_CREATE)

    /** Sets (or moves) the alarm of [kind] for [todoId] to [at]. */
    fun setAlarm(context: Context, todoId: Long, style: AlertStyle, at: Long, kind: String, extras: AlertRequest? = null) {
        val am = alarmManager(context)
        val pi = pending(context, todoId, kind, PendingIntent.FLAG_UPDATE_CURRENT, extras) ?: return
        try {
            when {
                style == AlertStyle.ALARM && canExact(context) ->
                    am.setAlarmClock(AlarmManager.AlarmClockInfo(at, openAppIntent(context)), pi)
                canExact(context) -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm refused, using an inexact one for $todoId", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(context: Context, todoId: Long, kind: String) {
        val pi = pendingAlarm(context, todoId, kind) ?: return
        alarmManager(context).cancel(pi)
        pi.cancel()
    }

    fun cancelAll(context: Context, todoId: Long) {
        cancel(context, todoId, KIND_FIRE)
        cancel(context, todoId, KIND_SNOOZE)
    }

    /** Next reminder time of [todo] under [settings], or null when nothing should ring. */
    fun nextFor(todo: Todo, settings: AppSettings, now: Long = System.currentTimeMillis()): Long? {
        val r = todo.reminder ?: return null
        if (todo.done && r.repeat == Repeat.ONCE) return null
        return ReminderMath.next(r, Instant.ofEpochMilli(now), ZoneId.systemDefault(), ReminderMath.quietOf(settings))?.toEpochMilli()
    }

    /** Schedules or cancels the regular alarm of [todo]; returns the time it was set for. */
    fun apply(context: Context, todo: Todo, settings: AppSettings): Long? {
        val next = nextFor(todo, settings)
        if (next == null) cancel(context, todo.id, KIND_FIRE)
        else setAlarm(context, todo.id, todo.reminder!!.style, next, KIND_FIRE)
        return next
    }

    /** A one-off test of a reminder that is not saved yet: rings [delayMs] from now. */
    fun preview(context: Context, req: AlertRequest, delayMs: Long) {
        setAlarm(context, AlertRequest.PREVIEW_ID, req.style, System.currentTimeMillis() + delayMs, KIND_PREVIEW, req)
    }
}
