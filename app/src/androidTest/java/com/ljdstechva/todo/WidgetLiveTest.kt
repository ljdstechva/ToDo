package com.ljdstechva.todo

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.ljdstechva.todo.Live.ctx
import com.ljdstechva.todo.Live.device
import com.ljdstechva.todo.Live.shot
import com.ljdstechva.todo.data.AlertStyle
import com.ljdstechva.todo.data.Reminder
import com.ljdstechva.todo.data.Repeat
import com.ljdstechva.todo.data.Repo
import com.ljdstechva.todo.data.TodoActions
import com.ljdstechva.todo.widget.TodoWidgetReceiver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.regex.Pattern

/**
 * The widget on the phone's real Home screen: added from Settings through the launcher's own
 * dialog, a to-do ticked off and on with its checkbox, the app opened from + and from a to-do.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class WidgetLiveTest {

    @get:Rule val rule = createEmptyComposeRule()

    @Before fun setUp() {
        Live.grantEverything()
        Live.reset()
    }

    @After fun tearDown() {
        finishActivities()
        Live.reset()
        device.pressHome()
    }

    @Test fun addFromSettingsTickOffOnHomeScreenAndOpen() {
        val category = Repo.data.value.categories.firstOrNull()?.id
        val tomorrow = LocalDate.now().plusDays(1).toEpochDay()
        val feed = runBlocking { TodoActions.add(ctx, "Feed the cat", "", category, null) }
        val permit = runBlocking { TodoActions.add(ctx, "Renew the permit", "", null, Reminder(AlertStyle.NOTIFICATION, Repeat.ONCE, 9, 0, tomorrow)) }
        runBlocking { TodoActions.add(ctx, "Buy milk", "", null, null).also { TodoActions.setDone(ctx, it.id, true) } }

        // Settings > Add the widget > the launcher's "Add to home screen".
        ctx.startActivity(appIntent().putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_SETTINGS))
        rule.waitUntilExactlyOneExists(hasTestTag("addWidget"), 10_000)
        rule.onNodeWithTag("addWidget").performScrollTo().performClick()
        val add = findDismissingDialogs(By.text(Pattern.compile("(?i)add to home ?screen|add automatically|add")), 15_000)
            ?: run {
                shot("widget-no-pin-dialog")
                throw AssertionError("The launcher did not offer to add the widget")
            }
        shot("widget-pin-dialog")
        add.click()
        Live.waitUntil(15_000, "the widget to be placed") { widgetIds().isNotEmpty() }

        // On the Home screen: the done to-do crossed out, the others not, the next reminder shown.
        device.pressHome()
        expect("Buy milk crossed out", crossedOutOnHome("Buy milk", true))
        expect("Feed the cat not crossed out", crossedOutOnHome("Feed the cat", false))
        expect("Renew the permit not crossed out", crossedOutOnHome("Renew the permit", false))
        expect("next reminder shown", findDismissingDialogs(By.textStartsWith("Tomorrow"), 5_000) != null)

        // Tick "Feed the cat" off with its checkbox.
        assertFalse("starts unticked", checkbox("Feed the cat").isChecked)
        shot("widget-home")
        checkbox("Feed the cat").click()
        Live.waitUntil(10_000, "the to-do to be done in the app") { Repo.todo(feed.id)?.done == true }
        Live.waitUntil(10_000, "the widget to show it ticked") { checkbox("Feed the cat").isChecked }
        expect("Feed the cat crossed out", crossedOutOnHome("Feed the cat", true))
        shot("widget-ticked")

        // And back on.
        checkbox("Feed the cat").click()
        Live.waitUntil(10_000, "the to-do to be open again") { Repo.todo(feed.id)?.done == false }
        Live.waitUntil(10_000, "the widget to show it unticked") { !checkbox("Feed the cat").isChecked }
        expect("Feed the cat no longer crossed out", crossedOutOnHome("Feed the cat", false))

        // + opens the app on a new to-do.
        onHome(By.desc("New to-do")).click()
        rule.waitUntilExactlyOneExists(hasTestTag("titleField"), 10_000)
        shot("widget-new-todo")

        // A to-do's title opens that to-do (here while the new one's sheet is still open).
        device.pressHome()
        onHome(By.text("Renew the permit")).click()
        rule.waitUntilExactlyOneExists(hasTestTag("titleField") and hasText("Renew the permit"), 10_000)
        shot("widget-open-todo")
        assertEquals("nothing else changed", false, Repo.todo(permit.id)?.done)
        assertTrue("What the widget drew:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    /** Drawing problems; the test still checks the rest and reports them all at the end. */
    private val problems = mutableListOf<String>()

    private fun expect(what: String, ok: Boolean) {
        if (!ok) problems += what
    }

    private fun appIntent() = Intent(ctx, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun widgetIds(): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, TodoWidgetReceiver::class.java))

    /** The widget's checkbox for [title]; it carries the title as its description. */
    private fun checkbox(title: String): UiObject2 {
        val node = onHome(By.desc(title))
        return if (node.isCheckable) node else node.findObject(By.checkable(true)) ?: node
    }

    /** Finds [selector] on the Home screen; the launcher puts a new widget on the first page with room. */
    private fun onHome(selector: BySelector): UiObject2 {
        findDismissingDialogs(selector, 5_000)?.let { return it }
        device.pressHome()
        device.pressHome() // the second press goes to the first page
        repeat(4) {
            findDismissingDialogs(selector, 3_000)?.let { return it }
            device.swipe(device.displayWidth * 9 / 10, device.displayHeight / 2, device.displayWidth / 10, device.displayHeight / 2, 20)
        }
        shot("widget-not-found")
        throw AssertionError("Not on the Home screen: $selector")
    }

    private fun findDismissingDialogs(selector: BySelector, timeoutMs: Long): UiObject2? {
        val end = System.currentTimeMillis() + timeoutMs
        do {
            Live.dismissSystemDialog()
            device.wait(Until.findObject(selector), 1_000)?.let { return it }
        } while (System.currentTimeMillis() < end)
        return null
    }

    /**
     * Whether the Home screen shows [title] crossed out as [expected], waiting up to 5 s for the
     * launcher to redraw. Its accessibility text keeps the strike-through span the widget sent.
     */
    private fun crossedOutOnHome(title: String, expected: Boolean): Boolean {
        onHome(By.text(title))
        val end = System.currentTimeMillis() + 5_000
        var seen: Boolean? = null
        do {
            val text = Live.instrumentation.uiAutomation.rootInActiveWindow?.let { findText(it, title) }
            seen = text?.let { it is Spanned && it.getSpans(0, it.length, StrikethroughSpan::class.java).isNotEmpty() }
            if (seen == expected) return true
            Thread.sleep(250)
        } while (System.currentTimeMillis() < end)
        Log.w("WidgetLiveTest", "$title: crossed out $seen, expected $expected")
        return false
    }

    private fun findText(node: AccessibilityNodeInfo, title: String): CharSequence? {
        node.text?.takeIf { it.toString() == title }?.let { return it }
        for (i in 0 until node.childCount) node.getChild(i)?.let { findText(it, title) }?.let { return it }
        return null
    }

    /** Activities this test opened outside a scenario; later tests start their own. */
    private fun finishActivities() {
        Live.instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values().flatMap { monitor.getActivitiesInStage(it) }.forEach { it.finish() }
        }
        device.waitForIdle()
    }
}
