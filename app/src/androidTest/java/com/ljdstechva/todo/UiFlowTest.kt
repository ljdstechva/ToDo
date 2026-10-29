package com.ljdstechva.todo

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import com.ljdstechva.todo.data.Repo
import com.ljdstechva.todo.reminder.ReminderScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
