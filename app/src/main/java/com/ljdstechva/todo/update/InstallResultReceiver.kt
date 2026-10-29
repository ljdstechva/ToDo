package com.ljdstechva.todo.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.ljdstechva.todo.AppVisibility
import com.ljdstechva.todo.reminder.Notifications

/** Result of an update install session. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i("InstallResult", "status=$status message=$message")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    Updater.onInstallFailed("The phone did not show an install screen.")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Updater.onNeedsConfirmation()
                if (AppVisibility.isForeground) {
                    try {
                        context.startActivity(confirm)
                        return
                    } catch (e: Exception) {
                        Log.w("InstallResult", "Cannot open the confirmation screen", e)
                    }
                }
                Notifications.showUpdate(
                    context,
                    title = "To-Do update ready",
                    text = "Tap to install the new version.",
                    intent = PendingIntent.getActivity(context, 4, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            }
            // Nothing to do: the new version's BootReceiver (MY_PACKAGE_REPLACED) replaces the
            // progress notice with "To-Do updated". Cancelling here raced with it and removed it.
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val text = message ?: "Install failed (code $status)."
                Updater.onInstallFailed(text)
                Notifications.showUpdate(context, "To-Do update failed", text, Notifications.openAppIntent(context, openSettings = true))
            }
        }
    }

    companion object {
        const val ACTION = "com.ljdstechva.todo.INSTALL_RESULT"
    }
}
