package com.ljdstechva.todo

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ljdstechva.todo.data.Repo
import com.ljdstechva.todo.data.ThemeMode
import com.ljdstechva.todo.data.TodoActions
import com.ljdstechva.todo.ui.categories.CategoriesScreen
import com.ljdstechva.todo.ui.home.HomeScreen
import com.ljdstechva.todo.ui.settings.SettingsScreen
import com.ljdstechva.todo.ui.theme.TodoTheme
import com.ljdstechva.todo.update.Updater
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Bumped when a notification asks for the Settings screen while the app is already open. */
    private var openSettingsRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val data by Repo.data.collectAsStateWithLifecycle()
            val dark = when (data.settings.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Status and navigation bar icons follow the app's theme, which may differ from the phone's.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose {}
            }
            TodoTheme(dark = dark, dynamicColor = data.settings.dynamicColor) {
                val nav = rememberNavController()
                // Saved with the screen, so a recreation during start-up (a configuration change)
                // neither loses an "open Settings" launch nor repeats it later.
                var launchHandled by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(openSettingsRequest) {
                    val fromLaunch = !launchHandled && wantsSettings(intent)
                    launchHandled = true
                    if ((fromLaunch || openSettingsRequest > 0) && nav.currentDestination?.route != SETTINGS) {
                        nav.navigate(SETTINGS) { launchSingleTop = true }
                    }
                }
                val duration = 380
                NavHost(
                    navController = nav,
                    startDestination = HOME,
                    enterTransition = { slideIntoContainer(SlideDirection.Start, tween(duration)) + fadeIn(tween(duration)) },
                    exitTransition = { slideOutOfContainer(SlideDirection.Start, tween(duration), targetOffset = { it / 4 }) + fadeOut(tween(duration)) },
                    popEnterTransition = { slideIntoContainer(SlideDirection.End, tween(duration), initialOffset = { it / 4 }) + fadeIn(tween(duration)) },
                    popExitTransition = { slideOutOfContainer(SlideDirection.End, tween(duration)) + fadeOut(tween(duration)) },
                ) {
                    composable(HOME) {
                        HomeScreen(
                            data = data,
                            onOpenSettings = { nav.navigate(SETTINGS) { launchSingleTop = true } },
                            onOpenCategories = { nav.navigate(CATEGORIES) { launchSingleTop = true } },
                        )
                    }
                    composable(SETTINGS) { SettingsScreen(data = data, onBack = { nav.popBackStack() }) }
                    composable(CATEGORIES) { CategoriesScreen(data = data, onBack = { nav.popBackStack() }) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (wantsSettings(intent)) openSettingsRequest++
    }

    override fun onStart() {
        super.onStart()
        Updater.checkOnLaunch(applicationContext)
        // Cheap and idempotent: repairs alarms the system dropped (force stop, clock change, ...).
        AppScope.launch { TodoActions.rescheduleAll(applicationContext) }
    }

    private fun wantsSettings(intent: Intent?) = intent?.getStringExtra(EXTRA_OPEN) == OPEN_SETTINGS

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_SETTINGS = "settings"
        // The scrims enableEdgeToEdge() uses by default for 3-button navigation.
        private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
        private const val HOME = "home"
        private const val SETTINGS = "settings"
        private const val CATEGORIES = "categories"
    }
}
