package com.ljdstechva.todo.ui.edit

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ljdstechva.todo.data.AlertStyle
import com.ljdstechva.todo.data.AppData
import com.ljdstechva.todo.data.AppSettings
import com.ljdstechva.todo.data.Reminder
import com.ljdstechva.todo.data.Repeat
import com.ljdstechva.todo.data.Todo
import com.ljdstechva.todo.data.TodoActions
import com.ljdstechva.todo.reminder.AlertRequest
import com.ljdstechva.todo.reminder.ReminderMath
import com.ljdstechva.todo.reminder.ReminderScheduler
import com.ljdstechva.todo.reminder.TonePreview
import com.ljdstechva.todo.reminder.Tones
import com.ljdstechva.todo.reminder.rememberTonePreview
import com.ljdstechva.todo.ui.Fmt
import com.ljdstechva.todo.ui.components.CategoryDialog
import com.ljdstechva.todo.ui.components.ColorDot
import com.ljdstechva.todo.ui.components.SectionLabel
import com.ljdstechva.todo.ui.components.TimeDialog
import com.ljdstechva.todo.ui.components.sheetGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle

/** Editable copy of a to-do while the sheet is open. */
@Stable
class Draft(todo: Todo?, defaultCategory: Long?, settings: AppSettings) {
    private val today = LocalDate.now()
    var title by mutableStateOf(todo?.title.orEmpty())
    var note by mutableStateOf(todo?.note.orEmpty())
    var categoryId by mutableStateOf(todo?.categoryId ?: defaultCategory)
    var remind by mutableStateOf(todo?.reminder != null)
    var style by mutableStateOf(todo?.reminder?.style ?: AlertStyle.NOTIFICATION)
    var repeat by mutableStateOf(todo?.reminder?.repeat ?: Repeat.DAILY)
        private set
    var hour by mutableIntStateOf(todo?.reminder?.hour ?: 9)
    var minute by mutableIntStateOf(todo?.reminder?.minute ?: 0)
    var startDay by mutableLongStateOf(todo?.reminder?.startEpochDay ?: today.toEpochDay())
    var weekday by mutableIntStateOf(todo?.reminder?.weekday ?: today.dayOfWeek.value)
    var monthDay by mutableIntStateOf(todo?.reminder?.monthDay ?: today.dayOfMonth)
    var tone by mutableStateOf(todo?.reminder?.tone ?: settings.defaultTone)

    fun reminder(): Reminder {
        // Daily / weekly / monthly have no date field on screen: they start today.
        val start = when (repeat) {
            Repeat.DAILY, Repeat.WEEKLY, Repeat.MONTHLY -> minOf(startDay, LocalDate.now().toEpochDay())
            else -> startDay
        }
        return Reminder(style, repeat, hour, minute, start, weekday, monthDay, tone)
    }

    fun selectRepeat(r: Repeat) {
        repeat = r
        if (r == Repeat.ONCE && ReminderMath.next(reminder(), Instant.now(), ZoneId.systemDefault()) == null) {
            startDay = LocalDate.now().plusDays(1).toEpochDay()
        }
    }
}

private enum class Extra { WEEKDAY, MONTHDAY, DATE, NONE }

private fun Repeat.extra(): Extra = when (this) {
    Repeat.WEEKLY -> Extra.WEEKDAY
    Repeat.MONTHLY -> Extra.MONTHDAY
    Repeat.DAILY -> Extra.NONE
    else -> Extra.DATE
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditSheet(
    todo: Todo?,
    defaultCategory: Long?,
    data: AppData,
    onDismiss: () -> Unit,
    onSaved: (Reminder?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val draft = remember { Draft(todo, defaultCategory, data.settings) }
    val tonePreview = rememberTonePreview()
    var timeDialog by remember { mutableStateOf(false) }
    var dateDialog by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun close(after: () -> Unit = {}) {
        tonePreview.stop()
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            after()
        }
    }

    fun save() {
        val title = draft.title.trim()
        if (title.isEmpty()) return
        val reminder = if (draft.remind) draft.reminder() else null
        scope.launch {
            if (todo == null) {
                TodoActions.add(context, title, draft.note.trim(), draft.categoryId, reminder)
            } else {
                TodoActions.save(context, todo.copy(title = title, note = draft.note.trim(), categoryId = draft.categoryId, reminder = reminder))
            }
            close { onSaved(reminder) }
        }
    }

    fun testNow() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val req = AlertRequest(
            id = AlertRequest.PREVIEW_ID,
            title = draft.title.trim().ifEmpty { "Test reminder" },
            text = "Test · " + Fmt.describe(context, draft.reminder()),
            style = draft.style,
            tone = draft.tone,
        )
        ReminderScheduler.preview(context, req, delayMs = 5_000)
        val hint = if (draft.style == AlertStyle.ALARM) "Alarm in 5 seconds. Lock the phone to see the full-screen alarm." else "Notification in 5 seconds."
        Toast.makeText(context, hint, Toast.LENGTH_LONG).show()
    }

    ModalBottomSheet(
        onDismissRequest = {
            tonePreview.stop()
            onDismiss()
        },
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (todo == null) "New to-do" else "Edit to-do",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { save() }, enabled = draft.title.isNotBlank(), modifier = Modifier.testTag("saveButton")) { Text("Save") }
            }
            if (todo?.sheetKey != null) SheetBanner(data.sheet.name, todo.sheetLink ?: data.sheet.url.ifBlank { null })
            OutlinedTextField(
                value = draft.title,
                onValueChange = { draft.title = it },
                label = { Text("What do you need to do?") },
                maxLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("titleField"),
            )
            if (todo == null) {
                LaunchedEffect(Unit) {
                    delay(350)
                    focus.requestFocus()
                }
            }
            OutlinedTextField(
                value = draft.note,
                onValueChange = { draft.note = it },
                label = { Text("Note (optional)") },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            Column {
                SectionLabel("Category")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = draft.categoryId == null, onClick = { draft.categoryId = null }, label = { Text("None") })
                    data.categories.forEach { c ->
                        FilterChip(
                            selected = draft.categoryId == c.id,
                            onClick = { draft.categoryId = c.id },
                            label = { Text(c.name) },
                            leadingIcon = { ColorDot(c.color) },
                            modifier = Modifier.testTag("category-${c.id}"),
                        )
                    }
                    AssistChip(
                        onClick = { newCategory = true },
                        label = { Text("New") },
                        leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
            }

            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(16.dp).animateContentSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .toggleable(value = draft.remind, onValueChange = { draft.remind = it }, role = Role.Switch)
                            .testTag("remindSwitch"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Remind me", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (draft.remind) Fmt.describe(context, draft.reminder()) else "No reminder",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = draft.remind, onCheckedChange = null)
                    }
                    AnimatedVisibility(
                        visible = draft.remind,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        ReminderOptions(
                            draft = draft,
                            settings = data.settings,
                            tonePreview = tonePreview,
                            onPickTime = { timeDialog = true },
                            onPickDate = { dateDialog = true },
                            onTest = { testNow() },
                        )
                    }
                }
            }

            if (todo != null) {
                TextButton(
                    onClick = {
                        scope.launch {
                            TodoActions.delete(context, todo.id)
                            close()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Delete to-do")
                }
            }
        }
    }

    if (timeDialog) {
        TimeDialog(
            title = if (draft.repeat.isHourly) "First reminder at" else "Remind at",
            hour = draft.hour,
            minute = draft.minute,
            onDismiss = { timeDialog = false },
            onConfirm = { h, m ->
                draft.hour = h
                draft.minute = m
                timeDialog = false
            },
        )
    }
    if (dateDialog) {
        val todayUtc = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.ofEpochDay(draft.startDay).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayUtc
            },
        )
        DatePickerDialog(
            onDismissRequest = { dateDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        draft.startDay = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                    }
                    dateDialog = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { dateDialog = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state)
        }
    }
    if (newCategory) {
        CategoryDialog(
            initial = null,
            onDismiss = { newCategory = false },
            onSave = { name, color ->
                newCategory = false
                scope.launch { draft.categoryId = TodoActions.addCategory(name, color).id }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReminderOptions(
    draft: Draft,
    settings: AppSettings,
    tonePreview: TonePreview,
    onPickTime: () -> Unit,
    onPickDate: () -> Unit,
    onTest: () -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = draft.style == AlertStyle.NOTIFICATION,
                onClick = { draft.style = AlertStyle.NOTIFICATION },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = { Icon(Icons.Rounded.Notifications, contentDescription = null, modifier = Modifier.size(18.dp)) },
            ) { Text("Notification") }
            SegmentedButton(
                selected = draft.style == AlertStyle.ALARM,
                onClick = { draft.style = AlertStyle.ALARM },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = { Icon(Icons.Rounded.Alarm, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.testTag("styleAlarm"),
            ) { Text("Alarm") }
        }
        AnimatedContent(targetState = draft.style, label = "styleHint", transitionSpec = { fadeIn() togetherWith fadeOut() }) { s ->
            Text(
                if (s == AlertStyle.ALARM) "Rings with sound until you stop it, even on the lock screen."
                else "A notification with Done and Snooze buttons.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column {
            SectionLabel("Repeat")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Repeat.entries.forEach { r ->
                    FilterChip(
                        selected = draft.repeat == r,
                        onClick = { draft.selectRepeat(r) },
                        label = { Text(r.label) },
                        modifier = Modifier.testTag("repeat-${r.name}"),
                    )
                }
            }
        }

        AnimatedContent(
            targetState = draft.repeat.extra(),
            label = "repeatExtra",
            transitionSpec = { (fadeIn() + expandVertically()) togetherWith (fadeOut() + shrinkVertically()) },
        ) { extra ->
            when (extra) {
                Extra.WEEKDAY -> WeekdayPicker(draft.weekday) { draft.weekday = it }
                Extra.MONTHDAY -> MonthDayPicker(draft.monthDay) { draft.monthDay = it }
                Extra.DATE -> PickerRow(
                    icon = Icons.Rounded.Event,
                    label = if (draft.repeat == Repeat.ONCE) "Date" else "Starting",
                    value = Fmt.day(draft.startDay),
                    onClick = onPickDate,
                )
                Extra.NONE -> Spacer(Modifier.height(0.dp))
            }
        }

        PickerRow(
            icon = Icons.Rounded.Schedule,
            label = if (draft.repeat.isHourly) "First reminder at" else "Time",
            value = Fmt.time(context, draft.hour, draft.minute),
            onClick = onPickTime,
        )

        if (draft.repeat.isHourly && settings.quietEnabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bedtime, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(
                    "Quiet from ${Fmt.minutesOfDay(context, settings.quietStartMinute)} to ${Fmt.minutesOfDay(context, settings.quietEndMinute)} (change in Settings)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AnimatedVisibility(visible = draft.style == AlertStyle.ALARM, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            TonePicker(selected = draft.tone, preview = tonePreview, onSelect = { draft.tone = it })
        }

        val reminder = draft.reminder()
        val next = remember(reminder, settings) {
            ReminderMath.next(reminder, Instant.now(), ZoneId.systemDefault(), ReminderMath.quietOf(settings))?.toEpochMilli()
        }
        AnimatedContent(targetState = next, label = "next", transitionSpec = { fadeIn() togetherWith fadeOut() }) { n ->
            Text(
                if (n == null) "This time has already passed. Pick a later date or time." else "Next reminder: ${Fmt.moment(context, n)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (n == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }

        OutlinedButton(onClick = onTest, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Test it in 5 seconds")
        }
    }
}

@Composable
private fun PickerRow(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun DayCell(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLowest,
        label = "dayBg",
    )
    val fg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        label = "dayFg",
    )
    Box(
        modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun WeekdayPicker(selected: Int, onSelect: (Int) -> Unit) {
    Column {
        SectionLabel("On")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            (1..7).forEach { d ->
                DayCell(Fmt.weekday(d, TextStyle.NARROW), selected == d, Modifier.testTag("weekday-$d")) { onSelect(d) }
            }
        }
        Text(
            "Every ${Fmt.weekday(selected)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonthDayPicker(selected: Int, onSelect: (Int) -> Unit) {
    Column {
        SectionLabel("Day of the month")
        FlowRow(
            maxItemsInEachRow = 7,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            (1..31).forEach { d ->
                DayCell("$d", selected == d, Modifier.testTag("monthDay-$d")) { onSelect(d) }
            }
        }
        Text(
            if (selected > 28) "On the ${Fmt.ordinal(selected)}; shorter months use their last day."
            else "On the ${Fmt.ordinal(selected)} of every month",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
fun TonePicker(selected: String, preview: TonePreview, onSelect: (String) -> Unit) {
    Column {
        SectionLabel("Alarm sound")
        Tones.all.forEach { t ->
            val isSelected = t.id == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .selectable(selected = isSelected, onClick = { onSelect(t.id) }, role = Role.RadioButton)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSelected, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name, style = MaterialTheme.typography.bodyLarge)
                    Text(t.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { preview.toggle(t.id) }) {
                    Icon(
                        if (preview.playingId == t.id) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        contentDescription = "Play ${t.name}",
                    )
                }
            }
        }
    }
}

/** Says the to-do came from the Google Sheet, and opens its row there. */
@Composable
private fun SheetBanner(sheetName: String, link: String?) {
    val context = LocalContext.current
    val green = sheetGreen()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = green.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth().testTag("sheetBanner"),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.TableChart, contentDescription = null, tint = green, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (sheetName.isBlank()) "From your Google Sheet" else "From your Google Sheet ($sheetName)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Ticked off here when it's done there",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (link != null) {
                TextButton(onClick = { openInSheets(context, link) }, modifier = Modifier.testTag("openInSheet")) { Text("Open") }
            }
        }
    }
}

private fun openInSheets(context: Context, link: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, link, Toast.LENGTH_LONG).show()
    }
}
