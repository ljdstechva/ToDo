package com.ljdstechva.todo

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.ljdstechva.todo.data.Repo
import com.ljdstechva.todo.reminder.Notifications
import com.ljdstechva.todo.update.UpdateWorker
import com.ljdstechva.todo.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide scope for work that must outlive a screen (saving, scheduling, updating). */
object AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default)

class TodoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        Notifications.createChannels(this)
        AppVisibility.register(this)
        UpdateWorker.schedule(this, Repo.settings.autoUpdate)
    }
}

/** Whether one of our screens is visible; updates are installed only when none is. */
object AppVisibility : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var started = 0
    private lateinit var app: Application

    val isForeground: Boolean get() = started > 0

    fun register(application: Application) {
        app = application
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        started++
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) Updater.onAppBackgrounded(app)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
