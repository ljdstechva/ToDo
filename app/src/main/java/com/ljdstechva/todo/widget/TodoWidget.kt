package com.ljdstechva.todo.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.CheckboxDefaults
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.ToggleableStateKey
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.components.TitleBar
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.material3.ColorProviders
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.ljdstechva.todo.AppScope
import com.ljdstechva.todo.MainActivity
import com.ljdstechva.todo.R
import com.ljdstechva.todo.data.AppData
import com.ljdstechva.todo.data.AppSettings
import com.ljdstechva.todo.data.Category
import com.ljdstechva.todo.data.Repo
import com.ljdstechva.todo.data.ThemeMode
import com.ljdstechva.todo.data.Todo
import com.ljdstechva.todo.data.TodoActions
import com.ljdstechva.todo.data.doneInOrder
import com.ljdstechva.todo.data.openInOrder
import com.ljdstechva.todo.ui.Fmt
import com.ljdstechva.todo.ui.home.NEW_TODO
import com.ljdstechva.todo.ui.theme.categoryColor
import com.ljdstechva.todo.ui.theme.todoColorScheme
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Home screen widget: the list in the app's order, a checkbox per to-do that ticks it off
 * (crossed out, like in the app) and a + for a new one. Tapping a to-do opens it in the app.
 */
class TodoWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        scheduleMidnightRefresh(context)
        provideContent {
            val data by Repo.data.collectAsState()
            WidgetTheme(context, data.settings) { Content(context, data) }
        }
    }

    companion object {
        /** Redraws every placed widget; does nothing when none is placed. */
        suspend fun refresh(context: Context) = TodoWidget().updateAll(context)

        /** Asks the launcher to place the widget. False when it cannot; then it is added by hand. */
        suspend fun requestPin(context: Context): Boolean =
            GlanceAppWidgetManager(context).requestPinGlanceAppWidget(TodoWidgetReceiver::class.java, previewSize = null)
    }
}

class TodoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodoWidget()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REFRESH) return super.onReceive(context, intent)
        val pending = goAsync()
        AppScope.launch {
            try {
                glanceAppWidget.updateAll(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        context.getSystemService(AlarmManager::class.java).cancel(refreshIntent(context))
    }

    companion object {
        const val ACTION_REFRESH = "com.ljdstechva.todo.action.WIDGET_REFRESH"
    }
}

/** A widget checkbox was tapped: tick the to-do off, or back on, exactly as the app does. */
class ToggleTodo : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[TODO_ID] ?: return
        val done = parameters[ToggleableStateKey] ?: return
        TodoActions.setDone(context.applicationContext, id, done)
        // Shows the saved state even when nothing changed (say the to-do was deleted meanwhile).
        TodoWidget().update(context, glanceId)
    }

    companion object {
        val TODO_ID = ActionParameters.Key<Long>("todoId")
    }
}

/** The app's colours and its Light / Dark / System choice. */
@Composable
private fun WidgetTheme(context: Context, s: AppSettings, content: @Composable () -> Unit) {
    when {
        // The launcher's wallpaper colours, which follow wallpaper changes by themselves.
        s.theme == ThemeMode.SYSTEM && s.dynamicColor && Build.VERSION.SDK_INT >= 31 -> GlanceTheme(content = content)
        s.theme == ThemeMode.SYSTEM -> GlanceTheme(
            ColorProviders(light = todoColorScheme(context, false, false), dark = todoColorScheme(context, true, false)),
            content,
        )
        else -> GlanceTheme(ColorProviders(todoColorScheme(context, s.theme == ThemeMode.DARK, s.dynamicColor)), content)
    }
}

@Composable
private fun Content(context: Context, data: AppData) {
    val open = data.todos.openInOrder()
    val done = data.todos.doneInOrder()
    val categories = data.categories.associateBy { it.id }
    val summary = when {
        open.isNotEmpty() -> "${open.size} to do"
        done.isNotEmpty() -> "All done"
        else -> ""
    }
    Scaffold(
        titleBar = {
            TitleBar(
                startIcon = ImageProvider(R.drawable.ic_stat_todo),
                title = context.getString(R.string.app_name),
                iconColor = GlanceTheme.colors.primary,
                // The title bar has no end inset of its own; without one the + touches the edge.
                modifier = GlanceModifier.clickable(openApp(context)).padding(top = 4.dp, end = 8.dp),
            ) {
                if (summary.isNotEmpty()) {
                    Text(
                        summary,
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                        modifier = GlanceModifier.padding(end = 8.dp),
                    )
                }
                CircleIconButton(
                    imageProvider = ImageProvider(R.drawable.ic_add),
                    contentDescription = "New to-do",
                    onClick = openApp(context, NEW_TODO),
                    backgroundColor = GlanceTheme.colors.primary,
                    contentColor = GlanceTheme.colors.onPrimary,
                )
            }
        },
    ) {
        if (open.isEmpty() && done.isEmpty()) {
            Empty()
        } else {
            LazyColumn(GlanceModifier.fillMaxSize()) {
                items(open + done, itemId = { it.id }) { t -> Item(context, t, categories[t.categoryId]) }
                item { Spacer(GlanceModifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun Item(context: Context, t: Todo, category: Category?) {
    val accent = category?.let { ColorProvider(categoryColor(it.color)) } ?: GlanceTheme.colors.primary
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        CheckBox(
            checked = t.done,
            onCheckedChange = actionRunCallback<ToggleTodo>(actionParametersOf(ToggleTodo.TODO_ID to t.id)),
            colors = CheckboxDefaults.colors(checkedColor = accent, uncheckedColor = GlanceTheme.colors.outline),
            modifier = GlanceModifier.semantics { contentDescription = t.title },
        )
        Column(GlanceModifier.defaultWeight().padding(vertical = 8.dp).clickable(openApp(context, t.id))) {
            Text(
                t.title,
                style = TextStyle(
                    color = if (t.done) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface,
                    fontSize = 15.sp,
                    textDecoration = if (t.done) TextDecoration.LineThrough else null,
                ),
                maxLines = 2,
            )
            val next = if (t.done) null else nextLabel(context, t)
            if (next != null) {
                Text(next, style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp), maxLines = 1)
            }
        }
    }
}

@Composable
private fun Empty() {
    Column(
        GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        Text("Nothing to do", style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium))
        Text("Tap + to add a to-do", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
    }
}

/** The same next-reminder line as the app's list. */
private fun nextLabel(context: Context, t: Todo): String? =
    t.snoozedUntil?.takeIf { it > System.currentTimeMillis() }?.let { "Snoozed to ${Fmt.time(context, it)}" }
        ?: t.nextAt?.let { Fmt.moment(context, it) }

/** Opens the app; with [todoId], on that to-do's sheet ([NEW_TODO]: a new one). */
private fun openApp(context: Context, todoId: Long? = null): Action {
    val intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    if (todoId != null) {
        intent.putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_TODO).putExtra(MainActivity.EXTRA_TODO_ID, todoId)
    }
    return actionStartActivity(intent)
}

/** "Today" and "Tomorrow" change at midnight: redraw then, or when the phone next wakes up. */
private fun scheduleMidnightRefresh(context: Context) {
    val at = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 60_000
    context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, at, refreshIntent(context))
}

private fun refreshIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
    context,
    0,
    Intent(context, TodoWidgetReceiver::class.java).setAction(TodoWidgetReceiver.ACTION_REFRESH),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)
