package com.ljdstechva.todo.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ljdstechva.todo.AppScope
import com.ljdstechva.todo.alarm.AlarmService
import com.ljdstechva.todo.data.AlertStyle
import com.ljdstechva.todo.data.Repo
import com.ljdstechva.todo.data.Todo
import com.ljdstechva.todo.data.TodoActions
import com.ljdstechva.todo.ui.Fmt
import kotlinx.coroutines.launch

/** Receives the reminder alarms and the Done / Snooze buttons of reminder notifications. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        AppScope.launch {
            try {
                handle(app, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle ${intent.action}", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, intent: Intent) {
        val id = intent.getLongExtra(AlertRequest.EXTRA_ID, AlertRequest.PREVIEW_ID)
        Log.i(TAG, "${intent.action} for $id")
        when (intent.action) {
            ACTION_FIRE, ACTION_SNOOZE_FIRE -> {
                val todo = TodoActions.onFired(context, id, fromSnooze = intent.action == ACTION_SNOOZE_FIRE) ?: return
                alert(context, requestFor(context, todo))
            }
            ACTION_PREVIEW -> AlertRequest.from(intent)?.let { alert(context, it) }
            ACTION_DONE -> {
                Notifications.cancel(context, id)
                if (id != AlertRequest.PREVIEW_ID) TodoActions.setDone(context, id, true)
            }
            ACTION_SNOOZE -> {
                Notifications.cancel(context, id)
                if (id != AlertRequest.PREVIEW_ID) TodoActions.snooze(context, id)
            }
        }
    }

    companion object {
        private const val TAG = "ReminderReceiver"
        const val ACTION_FIRE = "com.ljdstechva.todo.FIRE"
        const val ACTION_SNOOZE_FIRE = "com.ljdstechva.todo.SNOOZE_FIRE"
        const val ACTION_PREVIEW = "com.ljdstechva.todo.PREVIEW"
        const val ACTION_DONE = "com.ljdstechva.todo.DONE"
        const val ACTION_SNOOZE = "com.ljdstechva.todo.SNOOZE"

        fun requestFor(context: Context, todo: Todo): AlertRequest {
            val r = todo.reminder
            val category = Repo.data.value.categories.firstOrNull { it.id == todo.categoryId }?.name
            val text = listOfNotNull(
                todo.note.takeIf { it.isNotBlank() },
                category,
                r?.let { Fmt.describe(context, it) },
            ).joinToString(" · ")
            return AlertRequest(
                id = todo.id,
                title = todo.title,
                text = text,
                style = r?.style ?: AlertStyle.NOTIFICATION,
                tone = r?.tone ?: Repo.settings.defaultTone,
            )
        }

        /** Rings (alarm) or notifies. An alarm that cannot start its service still notifies. */
        fun alert(context: Context, req: AlertRequest) {
            if (req.style == AlertStyle.ALARM) {
                try {
                    AlarmService.start(context, req)
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Could not start the alarm service, showing a notification instead", e)
                }
            }
            Notifications.showReminder(context, req)
        }
    }
}
