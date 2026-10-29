package com.ljdstechva.todo

import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljdstechva.todo.Live.ctx
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Update notifications open the app on Settings; that must survive the screen being recreated. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class OpenSettingsTest {

    @get:Rule val rule = createEmptyComposeRule()

    @Before fun setUp() {
        Live.grantEverything()
        Live.reset()
    }

    @After fun tearDown() = Live.reset()

    @Test fun openSettingsLinkLandsOnSettingsOnceAcrossRecreation() {
        val intent = Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_SETTINGS)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            rule.waitUntilExactlyOneExists(hasTestTag("checkUpdates"), 10_000)

            scenario.recreate() // e.g. rotation or a theme change
            rule.waitUntilExactlyOneExists(hasTestTag("checkUpdates"), 10_000)

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            rule.waitUntilExactlyOneExists(hasTestTag("addButton"), 10_000)

            // The launch was handled: recreating the home screen must not jump to Settings again.
            scenario.recreate()
            rule.waitUntilExactlyOneExists(hasTestTag("addButton"), 10_000)
            rule.waitForIdle()
            Thread.sleep(1_000)
            rule.waitUntilDoesNotExist(hasTestTag("checkUpdates"), 2_000)
        }
    }
}
