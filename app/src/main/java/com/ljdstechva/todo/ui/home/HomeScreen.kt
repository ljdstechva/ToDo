package com.ljdstechva.todo.ui.home

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ljdstechva.todo.AppScope
import com.ljdstechva.todo.data.AlertStyle
import com.ljdstechva.todo.data.AppData
import com.ljdstechva.todo.data.Category
import com.ljdstechva.todo.data.Reminder
import com.ljdstechva.todo.data.Repeat
import com.ljdstechva.todo.data.Todo
import com.ljdstechva.todo.data.TodoActions
import com.ljdstechva.todo.ui.Fmt
import com.ljdstechva.todo.ui.components.AnimatedCheck
import com.ljdstechva.todo.ui.components.CategoryDialog
import com.ljdstechva.todo.ui.components.ColorDot
import com.ljdstechva.todo.ui.components.StrikeText
import com.ljdstechva.todo.ui.edit.EditSheet
import com.ljdstechva.todo.ui.theme.categoryColor
import com.ljdstechva.todo.update.Updater
import kotlinx.coroutines.launch

private const val ALL = -1L

/** Stands for "a new to-do" where a to-do id is expected (ids start at 1). */
const val NEW_TODO = 0L

/**
 * The list. [openTodo] is a to-do the widget asked to open ([NEW_TODO]: a new one); its sheet opens
 * once, then [onOpenTodoHandled] is called.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    data: AppData,
    onOpenSettings: () -> Unit,
    onOpenCategories: () -> Unit,
    openTodo: Long? = null,
    onOpenTodoHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var filter by rememberSaveable { mutableStateOf(ALL) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var showCompleted by rememberSaveable { mutableStateOf(true) }
    var newCategory by remember { mutableStateOf(false) }
    val updateState by Updater.state.collectAsStateWithLifecycle()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(openTodo) {
        val id = openTodo ?: return@LaunchedEffect
        creating = id == NEW_TODO
        editingId = id.takeIf { it != NEW_TODO }
        onOpenTodoHandled()
    }

    // A deleted category drops out of the filter.
    LaunchedEffect(data.categories, filter) {
        if (filter != ALL && data.categories.none { it.id == filter }) filter = ALL
    }

    val categories = data.categories.associateBy { it.id }
    val inView = data.todos.filter { filter == ALL || it.categoryId == filter }
    val active = inView.openInOrder()
    val done = inView.doneInOrder()

    fun undoable(message: String, removed: List<Todo>) {
        if (removed.isEmpty()) return
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) TodoActions.restore(context, removed)
        }
    }

    fun afterSave(reminder: Reminder?) {
        if (reminder == null) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        if (reminder.style == AlertStyle.ALARM && Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) {
            scope.launch {
                val r = snackbar.showSnackbar(
                    "Allow full-screen alarms so they show on the lock screen.",
                    actionLabel = "Allow",
                    duration = SnackbarDuration.Long,
                )
                if (r == SnackbarResult.ActionPerformed) {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("To-Do", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenCategories) { Icon(Icons.Rounded.Folder, contentDescription = "Categories") }
                    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("settingsButton")) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("New to-do") },
                expanded = scrollBehavior.state.collapsedFraction < 0.5f,
                modifier = Modifier.testTag("addButton"),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "update") {
                UpdateBanner(updateState, onOpenSettings)
            }
            item(key = "filters") {
                CategoryFilters(
                    categories = data.categories,
                    counts = data.todos.filter { !it.done }.groupingBy { it.categoryId }.eachCount(),
                    total = data.todos.count { !it.done },
                    selected = filter,
                    onSelect = { filter = it },
                    onNew = { newCategory = true },
                )
            }
            if (inView.isNotEmpty()) {
                item(key = "progress") {
                    Progress(done = done.size, total = inView.size, modifier = Modifier.animateItem())
                }
            }
            if (inView.isEmpty()) {
                item(key = "empty") { EmptyState(Modifier.animateItem()) }
            } else if (active.isEmpty()) {
                item(key = "allDone") { AllDone(Modifier.animateItem()) }
            }
            items(active, key = { it.id }) { t ->
                SwipeRow(
                    todo = t,
                    category = categories[t.categoryId].takeIf { filter == ALL },
                    onToggle = { scope.launch { TodoActions.setDone(context, t.id, !t.done) } },
                    onOpen = { editingId = t.id },
                    onDelete = { scope.launch { TodoActions.delete(context, t.id)?.let { undoable("Deleted “${it.title}”", listOf(it)) } } },
                    modifier = Modifier.animateItem(),
                )
            }
            if (done.isNotEmpty()) {
                item(key = "completedHeader") {
                    CompletedHeader(
                        count = done.size,
                        expanded = showCompleted,
                        onToggle = { showCompleted = !showCompleted },
                        onClear = {
                            scope.launch {
                                val ids = done.map { it.id }.toSet()
                                val gone = TodoActions.deleteWhere(context) { it.id in ids }
                                undoable("Cleared ${gone.size} completed", gone)
                            }
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
                if (showCompleted) {
                    items(done, key = { it.id }) { t ->
                        SwipeRow(
                            todo = t,
                            category = categories[t.categoryId].takeIf { filter == ALL },
                            onToggle = { scope.launch { TodoActions.setDone(context, t.id, !t.done) } },
                            onOpen = { editingId = t.id },
                            onDelete = { scope.launch { TodoActions.delete(context, t.id)?.let { undoable("Deleted “${it.title}”", listOf(it)) } } },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    val editing = editingId?.let { id -> data.todos.firstOrNull { it.id == id } }
    if (creating || editing != null) {
        // A fresh sheet per to-do: the widget can switch to another one while a sheet is open.
        key(editing?.id) {
            EditSheet(
                todo = editing,
                defaultCategory = filter.takeIf { it != ALL },
                data = data,
                onDismiss = {
                    creating = false
                    editingId = null
                },
                onSaved = { afterSave(it) },
            )
        }
    }
    LaunchedEffect(editingId, editing) {
        if (editingId != null && editing == null) editingId = null // deleted elsewhere while open
    }
    if (newCategory) {
        CategoryDialog(
            initial = null,
            onDismiss = { newCategory = false },
            onSave = { name, color ->
                newCategory = false
                scope.launch { filter = TodoActions.addCategory(name, color).id }
            },
        )
    }
}

@Composable
private fun UpdateBanner(state: Updater.State, onOpen: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val text = when (state) {
        is Updater.State.Available -> "Version ${state.release.tag} is available"
        is Updater.State.Downloading -> "Downloading ${state.release.tag}… ${(state.progress * 100).toInt()}%"
        is Updater.State.Installing -> "Installing ${state.release.tag}…"
        is Updater.State.WaitingForConfirmation -> "Tap to finish installing ${state.release.tag}"
        else -> null
    }
    AnimatedVisibility(visible = text != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Surface(
            onClick = onOpen,
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(12.dp))
                Text(
                    text.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                if (state is Updater.State.Available) {
                    Button(onClick = {
                        AppScope.launch { Updater.downloadAndInstall(context, state.release, userInitiated = true) }
                    }) { Text("Update") }
                }
            }
        }
    }
}

@Composable
private fun CategoryFilters(
    categories: List<Category>,
    counts: Map<Long?, Int>,
    total: Int,
    selected: Long,
    onSelect: (Long) -> Unit,
    onNew: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            FilterChip(
                selected = selected == ALL,
                onClick = { onSelect(ALL) },
                label = { Text(if (total > 0) "All · $total" else "All") },
            )
        }
        items(categories, key = { it.id }) { c ->
            val n = counts[c.id] ?: 0
            FilterChip(
                selected = selected == c.id,
                onClick = { onSelect(c.id) },
                label = { Text(if (n > 0) "${c.name} · $n" else c.name) },
                leadingIcon = { ColorDot(c.color) },
                modifier = Modifier.animateItem(),
            )
        }
        item(key = "new") {
            FilterChip(
                selected = false,
                onClick = onNew,
                label = { Text("New") },
                leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

@Composable
private fun Progress(done: Int, total: Int, modifier: Modifier = Modifier) {
    val fraction by animateFloatAsState(if (total == 0) 0f else done.toFloat() / total, tween(600, easing = FastOutSlowInEasing), label = "progress")
    Column(modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text(
            "$done of $total done",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(
    todo: Todo,
    category: Category?,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberSwipeToDismissBoxState()
    LaunchedEffect(state.currentValue) {
        if (state.currentValue != SwipeToDismissBoxValue.Settled) {
            onDelete()
            state.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier.padding(horizontal = 16.dp),
        backgroundContent = {
            val active = state.targetValue != SwipeToDismissBoxValue.Settled
            val bg by animateColorAsState(
                if (active) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
                label = "swipeBg",
            )
            val alignment = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd
            Box(
                Modifier
                    .fillMaxSize()
                    .background(bg, RoundedCornerShape(20.dp))
                    .padding(horizontal = 24.dp),
                contentAlignment = alignment,
            ) {
                val iconScale by animateFloatAsState(if (active) 1.2f else 0.8f, label = "swipeIcon")
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size((24 * iconScale).dp),
                )
            }
        },
    ) {
        TodoRow(todo, category, onToggle, onOpen)
    }
}

@Composable
fun TodoRow(todo: Todo, category: Category?, onToggle: () -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    val container by animateColorAsState(
        if (todo.done) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
        tween(300),
        label = "rowBg",
    )
    val accent = category?.let { categoryColor(it.color) } ?: MaterialTheme.colorScheme.primary
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(20.dp),
        color = container,
        modifier = Modifier.fillMaxWidth().testTag("todo-${todo.id}"),
    ) {
        Row(Modifier.padding(start = 6.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedCheck(checked = todo.done, color = accent, onToggle = onToggle)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                StrikeText(
                    todo.title,
                    struck = todo.done,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (todo.note.isNotBlank()) {
                    Text(
                        todo.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val r = todo.reminder
                if (r != null || category != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (r != null) {
                            Icon(
                                if (r.style == AlertStyle.ALARM) Icons.Rounded.Alarm else Icons.Rounded.Notifications,
                                contentDescription = if (r.style == AlertStyle.ALARM) "Alarm" else "Notification",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            val next = todo.snoozedUntil?.takeIf { it > System.currentTimeMillis() && !todo.done }
                                ?.let { "Snoozed to ${Fmt.time(context, it)}" }
                                ?: todo.nextAt?.let { Fmt.moment(context, it) }
                            Text(
                                listOfNotNull(next, Fmt.describe(context, r).takeIf { r.repeat != Repeat.ONCE }).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        if (category != null) {
                            if (r != null) Spacer(Modifier.width(10.dp))
                            ColorDot(category.color, 8.dp)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                category.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompletedHeader(count: Int, expanded: Boolean, onToggle: () -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val rotation by animateFloatAsState(if (expanded) 0f else -90f, label = "chevron")
    Row(
        modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onToggle) {
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.rotate(rotation))
            Spacer(Modifier.width(6.dp))
            Text("Completed · $count", style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onClear) { Text("Clear") }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    val bob = rememberInfiniteTransition(label = "bob")
    val y by bob.animateFloat(0f, -10f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "y")
    Column(
        modifier.fillMaxWidth().padding(top = 72.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .offset(y = y.dp)
                .size(96.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.TaskAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Nothing to do yet", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "Tap New to-do to add one. You can give it a reminder or an alarm.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AllDone(modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(visible = shown, enter = scaleIn(initialScale = 0.85f) + fadeIn(), modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Celebration, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("All done!", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text(
                        "Repeating to-dos come back when their reminder is due.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}
