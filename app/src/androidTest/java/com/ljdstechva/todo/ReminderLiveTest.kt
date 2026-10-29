package com.ljdstechva.todo

import android.app.AlarmManager
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.ljdstechva.todo.Live.ctx
import com.ljdstechva.todo.Live.device
import com.ljdstechva.todo.Live.find
import com.ljdstechva.todo.Live.shot
import com.ljdstechva.todo.Live.text
import com.ljdstechva.todo.Live.waitUntil
import com.ljdstechva.todo.alarm.AlarmService
import com.ljdstechva.todo.data.AlertStyle
import com.ljdstechva.todo.data.Reminder
import com.ljdstechva.todo.data.Repeat
import com.ljdstechva.todo.data.Repo
import com.ljdstechva.todo.data.Todo
import com.ljdstechva.todo.data.TodoActions
import com.ljdstechva.todo.reminder.AlertRequest
import com.ljdstechva.todo.reminder.BootReceiver
import com.ljdstechva.todo.reminder.ReminderScheduler
import com.ljdstechva.todo.reminder.Tones
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Live tests of reminders on a running phone: real AlarmManager alarms, real notifications, the
 * real alarm service and its full-screen screen, pressed through the system UI.
 */
@RunWith(AndroidJUnit4::class)
class ReminderLiveTest {

    @Before fun setUp() {
        Live.grantEverything()
        Live.wakeAndUnlock()
        Live.reset()
        device.pressHome()
    }

    @After fun tearDown() {
        Live.closeShade()
        Live.reset()
        Live.wakeAndUnlock()
    }

    private fun add(title: String, reminder: Reminder?): Todo = runBlocking {
        TodoActions.add(ctx, title, note = "", categoryId = null, reminder = reminder)
    }

    private fun daily(style: AlertStyle, tone: String = "classic") =
        Reminder(style = style, repeat = Repeat.DAILY, hour = 9, minute = 0, startEpochDay = LocalDate.now().toEpochDay(), tone = tone)

    /** Re-arms the to-do's real alarm to go off in [ms], as AlarmManager would at its time. */
    private fun fireIn(todo: Todo, ms: Long = 2_000) =
        ReminderScheduler.setAlarm(ctx, todo.id, todo.reminder!!.style, System.currentTimeMillis() + ms, ReminderScheduler.KIND_FIRE)

    @Test fun notificationReminderShowsAndDoneButtonCompletes() {
        val todo = add("Water the plants", daily(AlertStyle.NOTIFICATION))
        fireIn(todo)
        device.openNotification()
        assertNotNull("reminder notification", find(By.text("Water the plants"), 20_000))
        shot("notification-reminder")
        Live.notificationAction("Water the plants", "Done").click()
        waitUntil(what = "to-do marked done from the notification") { Repo.todo(todo.id)?.done == true }
        // A daily to-do stays scheduled: it comes back tomorrow.
        assertNotNull(Repo.todo(todo.id)?.nextAt)
        assertNotNull(ReminderScheduler.pendingAlarm(ctx, todo.id))
    }

    @Test fun notificationSnoozeButtonSnoozes() {
        val todo = add("Call the bank", daily(AlertStyle.NOTIFICATION))
        fireIn(todo)
        device.openNotification()
        assertNotNull(find(By.text("Call the bank"), 20_000))
        Live.notificationAction("Call the bank", "Snooze 10 min").click()
        waitUntil(what = "snooze stored") { Repo.todo(todo.id)?.snoozedUntil != null }
        val until = Repo.todo(todo.id)!!.snoozedUntil!!
        assertTrue("snoozed about 10 minutes", until - System.currentTimeMillis() in 9 * 60_000L..10 * 60_000L)
        assertNotNull("snooze alarm armed", ReminderScheduler.pendingAlarm(ctx, todo.id, ReminderScheduler.KIND_SNOOZE))
    }

    @Test fun alarmRingsOverTheLockScreenAndSnoozes() {
        val todo = add("Take medicine", daily(AlertStyle.ALARM, tone = "siren"))
        device.sleep()
        waitUntil(5_000, "screen off") { !device.isScreenOn }
        fireIn(todo, 3_000)
        waitUntil(20_000, "alarm to ring") { AlarmService.ringing.value?.req?.id == todo.id }
        assertTrue("tone playing", AlarmService.isPlayingSound)
        val snooze = find(By.text("Snooze 10 min"), 15_000)
        assertNotNull("full-screen alarm shown on the lock screen", snooze)
        assertTrue("alarm turned the screen on", device.isScreenOn)
        assertNotNull(find(By.text("Take medicine"), 2_000))
        shot("alarm-lockscreen")
        Thread.sleep(1_500) // let it ring audibly for a moment
        snooze!!.click()
        waitUntil(what = "alarm stopped") { AlarmService.ringing.value == null }
        assertFalse(AlarmService.isPlayingSound)
        waitUntil(what = "snooze stored") { Repo.todo(todo.id)?.snoozedUntil != null }
        assertNotNull(ReminderScheduler.pendingAlarm(ctx, todo.id, ReminderScheduler.KIND_SNOOZE))
        assertFalse(Repo.todo(todo.id)!!.done)
    }

    @Test fun alarmMarkDoneFromTheAlarmScreen() {
        val todo = add("Pay electricity", daily(AlertStyle.ALARM, tone = "chimes"))
        device.sleep()
        waitUntil(5_000, "screen off") { !device.isScreenOn }
        fireIn(todo, 3_000)
        waitUntil(20_000, "alarm to ring") { AlarmService.ringing.value != null }
        val done = find(By.text("Mark done"), 15_000)
        assertNotNull("Mark done on the alarm screen", done)
        done!!.click()
        waitUntil(what = "to-do done") { Repo.todo(todo.id)?.done == true }
        waitUntil(what = "alarm stopped") { AlarmService.ringing.value == null }
    }

    @Test fun alarmWhileUsingThePhoneCanBeDismissedFromTheNotification() {
        val todo = add("Stretch", daily(AlertStyle.ALARM, tone = "phone"))
        fireIn(todo)
        waitUntil(20_000, "alarm to ring") { AlarmService.ringing.value != null }
        assertEquals("Stretch", AlarmService.ringing.value!!.req.title)
        Thread.sleep(1_000)
        shot("alarm-unlocked")
        // In use, Android shows the alarm as a heads-up notification (or the alarm screen on some versions).
        val dismiss = find(By.text("Dismiss"), 3_000) ?: run {
            device.openNotification()
            Live.notificationAction("Stretch", "Dismiss")
        }
        dismiss.click()
        waitUntil(what = "alarm stopped") { AlarmService.ringing.value == null }
        assertFalse(AlarmService.isPlayingSound)
        val after = Repo.todo(todo.id)!!
        assertFalse("dismiss does not complete", after.done)
        assertNull("dismiss does not snooze", after.snoozedUntil)
    }

    @Test fun testButtonPreviewFiresANotification() {
        val req = AlertRequest(AlertRequest.PREVIEW_ID, "Preview check", "Test · Every day at 9:00 AM", AlertStyle.NOTIFICATION, "classic")
        ReminderScheduler.preview(ctx, req, delayMs = 3_000)
        device.openNotification()
        assertNotNull("preview notification", find(By.text("Preview check"), 20_000))
        shot("preview-notification")
    }

    @Test fun testButtonPreviewRingsAnAlarmWithoutTouchingData() {
        val before = Repo.data.value.todos
        val req = AlertRequest(AlertRequest.PREVIEW_ID, "Alarm preview", "Test", AlertStyle.ALARM, "sunrise")
        ReminderScheduler.preview(ctx, req, delayMs = 3_000)
        waitUntil(20_000, "preview alarm to ring") { AlarmService.ringing.value?.req?.isPreview == true }
        assertTrue(AlarmService.isPlayingSound)
        val dismiss = find(By.text("Dismiss"), 3_000) ?: run {
            device.openNotification()
            Live.notificationAction("Alarm preview", "Dismiss")
        }
        dismiss.click()
        waitUntil(what = "preview stopped") { AlarmService.ringing.value == null }
        assertEquals(before, Repo.data.value.todos)
    }

    @Test fun repeatingToDoReopensWhenItsReminderFires() {
        val todo = add("Clean the filter", daily(AlertStyle.NOTIFICATION))
        runBlocking { TodoActions.setDone(ctx, todo.id, true) }
        assertTrue(Repo.todo(todo.id)!!.done)
        assertNotNull("still scheduled while done", ReminderScheduler.pendingAlarm(ctx, todo.id))
        fireIn(todo)
        waitUntil(20_000, what = "to-do reopened") { Repo.todo(todo.id)?.done == false }
        val next = Repo.todo(todo.id)!!.nextAt!!
        assertTrue("next reminder is in the future", next > System.currentTimeMillis())
    }

    @Test fun checkingOffAOneTimeToDoCancelsItsAlarm() {
        val tomorrow = LocalDate.now().plusDays(1).toEpochDay()
        val todo = add("Renew permit", Reminder(AlertStyle.ALARM, Repeat.ONCE, 9, 0, tomorrow))
        assertNotNull(ReminderScheduler.pendingAlarm(ctx, todo.id))
        runBlocking { TodoActions.setDone(ctx, todo.id, true) }
        assertNull("alarm cancelled", ReminderScheduler.pendingAlarm(ctx, todo.id))
        assertNull(Repo.todo(todo.id)!!.nextAt)
        runBlocking { TodoActions.setDone(ctx, todo.id, false) }
        assertNotNull("unchecking re-arms it", ReminderScheduler.pendingAlarm(ctx, todo.id))
    }

    @Test fun alarmsComeBackAfterAReboot() {
        val a = add("Daily", daily(AlertStyle.NOTIFICATION))
        val b = add("Monthly", Reminder(AlertStyle.ALARM, Repeat.MONTHLY, 8, 30, LocalDate.now().toEpochDay(), monthDay = 31))
        // A reboot clears every alarm.
        ReminderScheduler.cancelAll(ctx, a.id)
        ReminderScheduler.cancelAll(ctx, b.id)
        assertNull(ReminderScheduler.pendingAlarm(ctx, a.id))
        runBlocking { BootReceiver.handle(ctx, Intent.ACTION_BOOT_COMPLETED) }
        assertNotNull(ReminderScheduler.pendingAlarm(ctx, a.id))
        assertNotNull(ReminderScheduler.pendingAlarm(ctx, b.id))
    }

    @Test fun alarmStyleShowsAsTheNextAlarmClock() {
        val tomorrow = LocalDate.now().plusDays(1).toEpochDay()
        val todo = add("Morning run", Reminder(AlertStyle.ALARM, Repeat.ONCE, 6, 15, tomorrow))
        val clock = ctx.getSystemService(AlarmManager::class.java).nextAlarmClock
        assertNotNull("alarm clock registered", clock)
        assertEquals(Repo.todo(todo.id)!!.nextAt, clock!!.triggerTime)
    }

    @Test fun everyToneIsPlayable() {
        for (tone in Tones.all) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(Tones.alarmAttributes)
                mp.setDataSource(ctx, Tones.uri(ctx, tone))
                mp.prepare()
                if (tone.res != null) assertTrue("${tone.id} has sound", mp.duration > 1_000)
                mp.start()
                Thread.sleep(300)
                assertTrue("${tone.id} plays", mp.isPlaying)
            } catch (e: Exception) {
                // Some emulator images ship without an alarm sound; the five built-in tones must always work.
                if (tone.res != null) throw AssertionError("${tone.id}: $e", e)
                android.util.Log.w("LiveTest", "Phone alarm sound not playable on this image (SDK ${Build.VERSION.SDK_INT})", e)
            } finally {
                mp.release()
            }
        }
    }
}
