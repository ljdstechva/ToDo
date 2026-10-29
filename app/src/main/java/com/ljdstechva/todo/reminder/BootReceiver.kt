package com.ljdstechva.todo.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ljdstechva.todo.AppScope
import com.ljdstechva.todo.BuildConfig
import com.ljdstechva.todo.data.Repo
import com.ljdstechva.todo.data.TodoActions
import com.ljdstechva.todo.update.Updater
import com.ljdstechva.todo.update.UpdateWorker
import kotlinx.coroutines.launch

/** Re-arms all reminders after a reboot, a clock / time-zone change or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        AppScope.launch {
            try {
                handle(app, intent.action)
            } catch (e: Exception) {
                Log.e("BootReceiver", "Rescheduling after ${intent.action} failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        suspend fun handle(context: Context, action: String?) {
            TodoActions.rescheduleAll(context)
            if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                Updater.clearDownloads(context)
                UpdateWorker.schedule(context, Repo.settings.autoUpdate)
                Notifications.showUpdate(
                    context,
                    title = "To-Do updated to ${BuildConfig.VERSION_NAME}",
                    text = "Tap to open. Your to-dos and reminders are kept.",
                    intent = Notifications.openAppIntent(context),
                )
            }
        }
    }
}
