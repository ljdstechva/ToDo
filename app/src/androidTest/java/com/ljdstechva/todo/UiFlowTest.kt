package com.ljdstechva.todo

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljdstechva.todo.Live.ctx
import com.ljdstechva.todo.Live.shot
import com.ljdstechva.todo.data.AlertStyle
import com.ljdstechva.todo.data.Repeat
import com.ljdstechva.todo.data.Reminder
import com.ljdstechva.todo.data.Repo
import com.ljdstechva.todo.data.ThemeMode
import com.ljdstechva.todo.data.TodoActions
import com.ljdstechva.todo.reminder.ReminderScheduler
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** The everyday flow through the real screens: add, set a reminder, check off, swipe away, undo. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UiFlowTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun setUp() {
        Live.grantEverything()
        Live.reset()
        rule.waitForIdle()
    }

    @After fun tearDown() = Live.reset()

    private fun only() = Repo.data.value.todos.single()

    @Test fun addRemindCheckSwipeUndo() {
        rule.onNodeWithText("Nothing to do yet").assertExists()
        shot("ui-empty")

        // Add a weekly Friday alarm.
        rule.onNodeWithTag("addButton").performClick()
        rule.waitUntilExactlyOneExists(hasTestTag("titleField"), 5_000)
        rule.onNodeWithTag("titleField").performTextInput("Submit the report")
        rule.onNodeWithTag("remindSwitch").performClick()
        rule.waitUntilExactlyOneExists(hasTestTag("repeat-WEEKLY"), 5_000)
        rule.onNodeWithTag("styleAlarm").performScrollTo().performClick()
        rule.onNodeWithTag("repeat-WEEKLY").performScrollTo().performClick()
        rule.waitUntilExactlyOneExists(hasTestTag("weekday-5"), 5_000)
        rule.onNodeWithTag("weekday-5").performScrollTo().performClick()
        rule.waitForIdle()
        shot("ui-edit-weekly-alarm")
        rule.onNodeWithTag("saveButton").performScrollTo().performClick()
        rule.waitUntilExactlyOneExists(hasText("Submit the report"), 5_000)
        rule.waitUntil(5_000) { Repo.data.value.todos.size == 1 && only().nextAt != null }

        var t = only()
        assertEquals(Repeat.WEEKLY, t.reminder!!.repeat)
        assertEquals(5, t.reminder!!.weekday)
        assertEquals(AlertStyle.ALARM, t.reminder!!.style)
        assertNotNull("alarm armed", ReminderScheduler.pendingAlarm(ctx, t.id))
        rule.waitForIdle()
        shot("ui-list")

        // Change it to monthly on the 31st.
        rule.onNodeWithText("Submit the report").performClick()
        rule.waitUntilExactlyOneExists(hasTestTag("repeat-MONTHLY"), 5_000)
        rule.onNodeWithTag("repeat-MONTHLY").performScrollTo().performClick()
        rule.waitUntilExactlyOneExists(hasTestTag("monthDay-31"), 5_000)
        rule.onNodeWithTag("monthDay-31").performScrollTo().performClick()
        rule.waitForIdle()
        shot("ui-edit-monthly")
        rule.onNodeWithTag("saveButton").performScrollTo().performClick()
        rule.waitUntil(5_000) { only().reminder?.repeat == Repeat.MONTHLY }
        assertEquals(31, only().reminder!!.monthDay)

        // Check it off.
        rule.waitUntilExactlyOneExists(hasText("Submit the report"), 5_000)
        rule.onNodeWithContentDescription("Mark as done").performClick()
        rule.waitUntil(5_000) { only().done }
        rule.waitUntilExactlyOneExists(hasText("Completed · 1"), 5_000)
        rule.onNodeWithText("All done!").assertExists()
        rule.waitForIdle()
        Thread.sleep(600) // let the tick and strike-through animations finish for the screenshot
        shot("ui-done")

        // Swipe it away, then undo.
        t = only()
        rule.onNodeWithTag("todo-${t.id}").performTouchInput { swipeLeft() }
        rule.waitUntil(5_000) { Repo.data.value.todos.isEmpty() }
        assertNull(ReminderScheduler.pendingAlarm(ctx, t.id))
        rule.waitUntilExactlyOneExists(hasText("Undo"), 5_000)
        shot("ui-deleted-undo")
        rule.onNodeWithText("Undo").performClick()
        rule.waitUntil(5_000) { Repo.data.value.todos.size == 1 }
        assertEquals(t.id, only().id)
        assertTrue(only().done)
    }

    @Test fun settingsShowsUpdateControls() {
        rule.onNodeWithTag("settingsButton").performClick()
        rule.waitUntilExactlyOneExists(hasTestTag("checkUpdates"), 5_000)
        rule.onNodeWithTag("autoUpdateSwitch").assertExists()
        rule.onNodeWithText("Reliable reminders").performScrollTo()
        rule.waitForIdle()
        shot("ui-settings")
    }

    /** Brightness of the screen background at the left gutter, middle height (0 black, 1 white). */
    private fun backgroundLuminance(): Float {
        rule.waitForIdle()
        val px = rule.onRoot().captureToImage().toPixelMap()
        return px[4, px.height / 2].luminance()
    }

    @Test fun darkThemeFromSettings() {
        runBlocking {
            val today = LocalDate.now().toEpochDay()
            TodoActions.add(ctx, "Water the plants", "", null, Reminder(AlertStyle.NOTIFICATION, Repeat.DAILY, 9, 0, today))
            val paid = TodoActions.add(ctx, "Pay electricity", "", null, Reminder(AlertStyle.ALARM, Repeat.MONTHLY, 8, 30, today, monthDay = 15))
            TodoActions.add(ctx, "Buy rice", "", null, null)
            TodoActions.setDone(ctx, paid.id, true)
        }
        assertTrue("starts light (emulator default)", backgroundLuminance() > 0.6f)

        rule.onNodeWithTag("settingsButton").performClick()
        rule.waitUntilExactlyOneExists(hasTestTag("theme-DARK"), 5_000)
        rule.onNodeWithTag("theme-DARK").performScrollTo().performClick()
        rule.waitUntil(5_000) { Repo.settings.theme == ThemeMode.DARK }
        assertTrue("settings turned dark", backgroundLuminance() < 0.2f)
        shot("ui-dark-settings")

        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntilExactlyOneExists(hasTestTag("addButton"), 5_000)
        assertTrue("home is dark too", backgroundLuminance() < 0.2f)
        shot("ui-dark-home")

        rule.onNodeWithTag("settingsButton").performClick()
        rule.waitUntilExactlyOneExists(hasTestTag("theme-LIGHT"), 5_000)
        rule.onNodeWithTag("theme-LIGHT").performScrollTo().performClick()
        rule.waitUntil(5_000) { Repo.settings.theme == ThemeMode.LIGHT }
        assertTrue("back to light", backgroundLuminance() > 0.6f)
    }

    @Test fun categoriesGroupToDos() {
        rule.onNodeWithTag("addButton").performClick()
        rule.waitUntilExactlyOneExists(hasTestTag("titleField"), 5_000)
        rule.onNodeWithTag("titleField").performTextInput("Buy rice")
        val errands = Repo.data.value.categories.first { it.name == "Errands" }
        rule.onNodeWithTag("category-${errands.id}").performScrollTo().performClick()
        rule.onNodeWithTag("saveButton").performClick()
        rule.waitUntil(5_000) { Repo.data.value.todos.size == 1 }
        assertEquals(errands.id, only().categoryId)
        assertNull(only().reminder)
        rule.waitForIdle()
        shot("ui-category")
    }
}
