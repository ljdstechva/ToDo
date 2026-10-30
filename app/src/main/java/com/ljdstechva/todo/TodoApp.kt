package com.ljdstechva.todo

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.ljdstechva.todo.data.Repo
import com.ljdstechva.todo.reminder.Notifications
import com.ljdstechva.todo.update.UpdateWorker
import com.ljdstechva.todo.update.Updater
import com.ljdstechva.todo.widget.TodoWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Process-wide scope for work that must outlive a screen (saving, scheduling, updating). */
object AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default)

class TodoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        Notifications.createChannels(this)
        AppVisibility.register(this)
        UpdateWorker.schedule(this, Repo.settings.autoUpdate)
        // The Home screen widget shows the list: redraw it whenever what it shows changes.
        AppScope.launch {
            Repo.data
                .map { listOf(it.todos, it.categories, it.settings.theme, it.settings.dynamicColor) }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    try {
                        TodoWidget.refresh(this@TodoApp)
                    } catch (e: Exception) {
                        Log.e("TodoApp", "Could not redraw the widget", e)
                    }
                }
        }
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
