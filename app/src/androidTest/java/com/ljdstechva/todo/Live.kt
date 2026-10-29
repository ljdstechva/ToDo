package com.ljdstechva.todo

import android.app.NotificationManager
import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.ljdstechva.todo.alarm.AlarmService
import com.ljdstechva.todo.data.AppData
import com.ljdstechva.todo.data.AppSettings
import com.ljdstechva.todo.data.Repo
import com.ljdstechva.todo.reminder.ReminderScheduler
import kotlinx.coroutines.runBlocking
import java.util.regex.Pattern

/** Helpers for tests that run on a real (emulated) phone: shell, screenshots, waiting, clean state. */
object Live {
    private const val TAG = "LiveTest"
    const val SHOTS = "/data/local/tmp/todo-shots"

    val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val ctx: Context get() = instrumentation.targetContext.applicationContext
    val device: UiDevice get() = UiDevice.getInstance(instrumentation)
    val pkg: String get() = ctx.packageName

    fun shell(cmd: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }.also {
            Log.i(TAG, "$ $cmd\n$it")
        }
    }

    /** Saves a screenshot for the CI artifact (pulled from [SHOTS] after the run). */
    fun shot(name: String) {
        shell("mkdir -p $SHOTS")
        shell("screencap -p $SHOTS/${android.os.Build.VERSION.SDK_INT}-$name.png")
    }

    fun waitUntil(timeoutMs: Long = 15_000, what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(200)
        }
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for: $what")
    }

    fun text(label: String): BySelector = By.text(Pattern.compile("(?i)" + Pattern.quote(label)))

    fun find(selector: BySelector, timeoutMs: Long = 10_000): UiObject2? = device.wait(Until.findObject(selector), timeoutMs)

    /**
     * Finds an action button of the notification titled [title] in the open shade. Collapsed
     * notifications hide their buttons, so it expands the notification when needed.
     */
    fun notificationAction(title: String, action: String): UiObject2 {
        find(text(action), 3_000)?.let { return it }
        val titleObj = find(By.text(title), 10_000) ?: throw AssertionError("Notification \"$title\" not shown")
        titleObj.swipe(Direction.DOWN, 0.8f)
        find(text(action), 3_000)?.let { return it }
        device.findObject(By.desc(Pattern.compile("(?i)expand.*")))?.click()
        return find(text(action), 5_000) ?: throw AssertionError("Button \"$action\" not found on \"$title\"")
    }

    fun closeShade() {
        device.pressHome()
        device.waitForIdle()
    }

    fun wakeAndUnlock() {
        device.wakeUp()
        shell("wm dismiss-keyguard")
        device.waitForIdle()
    }

    fun grantEverything() {
        if (android.os.Build.VERSION.SDK_INT >= 33) shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        if (android.os.Build.VERSION.SDK_INT >= 34) shell("appops set $pkg USE_FULL_SCREEN_INTENT allow")
        shell("cmd deviceidle whitelist +$pkg")
    }

    /** No to-dos, no alarms, no notifications, nothing ringing; update checks off. */
    fun reset() {
        if (AlarmService.ringing.value != null) {
            AlarmService.command(ctx, AlarmService.ACTION_DISMISS) // allowed: the ringing service is in the foreground
            waitUntil(5_000, "previous alarm to stop") { AlarmService.ringing.value == null }
        }
        runBlocking {
            Repo.data.value.todos.forEach { ReminderScheduler.cancelAll(ctx, it.id) }
            Repo.update { d ->
                AppData(
                    todos = emptyList(),
                    categories = d.categories,
                    settings = AppSettings(autoUpdate = false, quietEnabled = false, lastUpdateCheck = System.currentTimeMillis()),
                    nextId = d.nextId,
                )
            }
        }
        ctx.getSystemService(NotificationManager::class.java).cancelAll()
    }
}
