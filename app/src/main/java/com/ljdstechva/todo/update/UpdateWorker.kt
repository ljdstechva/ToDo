package com.ljdstechva.todo.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ljdstechva.todo.AppVisibility
import com.ljdstechva.todo.data.Repo
import com.ljdstechva.todo.reminder.Notifications
import java.util.concurrent.TimeUnit

/**
 * Background update check (every 6 hours, and right after the app is closed with an update
 * waiting). With automatic updates on it installs the new version itself; otherwise it posts a
 * notification.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!Updater.enabled) return Result.success()
        val release = Updater.check(ctx, reuseWithinMs = 10 * 60_000L) ?: return Result.success()
        when {
            AppVisibility.isForeground -> Unit // the in-app banner shows it; installs after the app closes
            Repo.settings.autoUpdate -> Updater.downloadAndInstall(ctx, release, userInitiated = false)
            else -> Notifications.showUpdate(
                ctx,
                title = "To-Do ${release.tag} is available",
                text = "Tap to update.",
                intent = Notifications.openAppIntent(ctx, openSettings = true),
            )
        }
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "update-check"
        private const val NOW = "update-now"

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled || !Updater.enabled) {
                wm.cancelUniqueWork(PERIODIC)
                return
            }
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(network)
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
        }
    }
}
