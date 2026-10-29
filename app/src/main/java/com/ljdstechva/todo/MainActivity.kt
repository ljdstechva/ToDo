package com.ljdstechva.todo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ljdstechva.todo.data.Repo
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
        if (savedInstanceState == null && wantsSettings(intent)) openSettingsRequest++

        setContent {
            val data by Repo.data.collectAsStateWithLifecycle()
            TodoTheme(dynamicColor = data.settings.dynamicColor) {
                val nav = rememberNavController()
                LaunchedEffect(openSettingsRequest) {
                    if (openSettingsRequest > 0 && nav.currentDestination?.route != SETTINGS) {
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
        private const val HOME = "home"
        private const val SETTINGS = "settings"
        private const val CATEGORIES = "categories"
    }
}
