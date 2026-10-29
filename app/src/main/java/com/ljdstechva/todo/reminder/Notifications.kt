package com.ljdstechva.todo.reminder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ljdstechva.todo.MainActivity
import com.ljdstechva.todo.R
import com.ljdstechva.todo.alarm.AlarmActivity
import com.ljdstechva.todo.alarm.AlarmService
import com.ljdstechva.todo.data.Repo

object Notifications {
    const val CH_REMINDERS = "reminders"
    const val CH_ALARMS = "alarms"
    const val CH_UPDATES = "updates"

    const val ALARM_ID = 7
    const val UPDATE_ID = 8
    private const val BRAND = 0xFF5B54E8.toInt()

    fun reminderId(todoId: Long): Int = 10_000 + (todoId and 0xFFFFF).toInt()

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val reminders = NotificationChannel(CH_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "To-do reminders shown as notifications"
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        // The alarm sound comes from AlarmService (looping, alarm volume), so the channel is silent.
        val alarms = NotificationChannel(CH_ALARMS, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Ringing to-do alarms"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val updates = NotificationChannel(CH_UPDATES, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "New versions of To-Do"
        }
        nm.createNotificationChannels(listOf(reminders, alarms, updates))
    }

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    @SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, notification: Notification) {
        if (canPost(context)) NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun cancel(context: Context, todoId: Long) = NotificationManagerCompat.from(context).cancel(reminderId(todoId))

    fun openAppIntent(context: Context, openSettings: Boolean = false): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (openSettings) intent.putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_SETTINGS)
        return PendingIntent.getActivity(
            context, if (openSettings) 2 else 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun receiverAction(context: Context, action: String, todoId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(action)
            .setData(android.net.Uri.parse("todo://$action/$todoId"))
            .putExtra(AlertRequest.EXTRA_ID, todoId)
        return PendingIntent.getBroadcast(context, reminderId(todoId), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun showReminder(context: Context, req: AlertRequest) {
        val snooze = Repo.settings.snoozeMinutes
        val n = NotificationCompat.Builder(context, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_todo)
            .setColor(BRAND)
            .setContentTitle(req.title)
            .setContentText(req.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(req.text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .addAction(R.drawable.ic_check, "Done", receiverAction(context, ReminderReceiver.ACTION_DONE, req.id))
            .addAction(R.drawable.ic_snooze, "Snooze $snooze min", receiverAction(context, ReminderReceiver.ACTION_SNOOZE, req.id))
            .build()
        post(context, reminderId(req.id), n)
    }

    fun showMissed(context: Context, req: AlertRequest) {
        val n = NotificationCompat.Builder(context, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(BRAND)
            .setContentTitle("Missed alarm: ${req.title}")
            .setContentText(req.text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .addAction(R.drawable.ic_check, "Done", receiverAction(context, ReminderReceiver.ACTION_DONE, req.id))
            .build()
        post(context, reminderId(req.id), n)
    }

    private fun alarmServiceAction(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            context, code, Intent(context, AlarmService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun alarmNotification(context: Context, req: AlertRequest): Notification {
        val fullScreen = PendingIntent.getActivity(
            context, 3,
            Intent(context, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val snooze = Repo.settings.snoozeMinutes
        val b = NotificationCompat.Builder(context, CH_ALARMS)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(BRAND)
            .setContentTitle(req.title)
            .setContentText(req.text.ifBlank { "Alarm" })
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            // No setSilent(): NotificationCompat makes a silent notification "group alert summary",
            // and Android then refuses its full-screen intent. The channel itself has no sound.
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_close, "Dismiss", alarmServiceAction(context, AlarmService.ACTION_DISMISS, 11))
            .addAction(R.drawable.ic_snooze, "Snooze $snooze min", alarmServiceAction(context, AlarmService.ACTION_SNOOZE, 12))
        if (!req.isPreview) b.addAction(R.drawable.ic_check, "Done", alarmServiceAction(context, AlarmService.ACTION_DONE, 13))
        return b.build()
    }

    fun showUpdate(context: Context, title: String, text: String, intent: PendingIntent, progress: Int? = null) {
        val b = NotificationCompat.Builder(context, CH_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setColor(BRAND)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(progress == null)
            .setOnlyAlertOnce(true)
            .setContentIntent(intent)
        if (progress != null) b.setProgress(100, progress, progress < 0).setOngoing(true)
        post(context, UPDATE_ID, b.build())
    }

    fun cancelUpdate(context: Context) = NotificationManagerCompat.from(context).cancel(UPDATE_ID)
}
