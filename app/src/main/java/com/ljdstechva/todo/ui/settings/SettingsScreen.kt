package com.ljdstechva.todo.ui.settings

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ljdstechva.todo.AppScope
import com.ljdstechva.todo.BuildConfig
import com.ljdstechva.todo.data.AppData
import com.ljdstechva.todo.data.AppSettings
import com.ljdstechva.todo.data.Repo
import com.ljdstechva.todo.data.ThemeMode
import com.ljdstechva.todo.data.TodoActions
import com.ljdstechva.todo.reminder.ReminderScheduler
import com.ljdstechva.todo.reminder.Tones
import com.ljdstechva.todo.reminder.rememberTonePreview
import com.ljdstechva.todo.sheet.SheetMerge
import com.ljdstechva.todo.sheet.SheetSync
import com.ljdstechva.todo.ui.Fmt
import com.ljdstechva.todo.ui.components.TimeDialog
import com.ljdstechva.todo.ui.components.sheetGreen
import com.ljdstechva.todo.ui.edit.TonePicker
import com.ljdstechva.todo.update.Updater
import com.ljdstechva.todo.widget.TodoWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(data: AppData, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = data.settings
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var toneDialog by remember { mutableStateOf(false) }
    var quietPicker by remember { mutableStateOf<String?>(null) }

    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { TodoActions.updateSettings(context.applicationContext, transform) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            UpdatesCard(s, onAutoUpdate = { on -> update { it.copy(autoUpdate = on) } })

            Card("Reminders") {
                ClickRow("Default alarm sound", Tones.byId(s.defaultTone).name) { toneDialog = true }
                Divider()
                Column(Modifier.padding(16.dp)) {
                    Text("Snooze for", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 10, 15, 30).forEach { m ->
                            FilterChip(
                                selected = s.snoozeMinutes == m,
                                onClick = { update { it.copy(snoozeMinutes = m) } },
                                label = { Text("$m min") },
                            )
                        }
                    }
                }
                Divider()
                SwitchRow(
                    title = "Quiet hours",
                    subtitle = "3- and 6-hour reminders skip these hours",
                    checked = s.quietEnabled,
                    onChange = { on -> update { it.copy(quietEnabled = on) } },
                )
                AnimatedVisibility(s.quietEnabled, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column {
                        ClickRow("From", Fmt.minutesOfDay(context, s.quietStartMinute)) { quietPicker = "start" }
                        ClickRow("Until", Fmt.minutesOfDay(context, s.quietEndMinute)) { quietPicker = "end" }
                    }
                }
            }

            Card("Appearance") {
                Column(Modifier.padding(16.dp)) {
                    Text("Theme", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { i, mode ->
                            SegmentedButton(
                                selected = s.theme == mode,
                                onClick = { update { it.copy(theme = mode) } },
                                shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                                icon = { Icon(mode.icon(), contentDescription = null, modifier = Modifier.size(18.dp)) },
                                modifier = Modifier.testTag("theme-${mode.name}"),
                            ) { Text(mode.label) }
                        }
                    }
                    if (s.theme == ThemeMode.SYSTEM) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Follows your phone's dark theme setting.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    Divider()
                    SwitchRow(
                        title = "Match wallpaper colors",
                        subtitle = "Use your phone's color theme",
                        checked = s.dynamicColor,
                        onChange = { on -> update { it.copy(dynamicColor = on) } },
                    )
                }
            }

            Card("Home screen widget") {
                ClickRow("Add the widget", "Tick off to-dos right from your Home screen", tag = "addWidget") {
                    scope.launch {
                        if (!TodoWidget.requestPin(context)) {
                            Toast.makeText(context, "Touch and hold your Home screen, tap Widgets, then pick To-Do.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }

            SheetCard(data)

            ReliabilityCard()

            Card("About") {
                if (Updater.enabled) {
                    ClickRow("Source code and releases", "github.com/${Updater.REPO}", icon = true) {
                        openUrl(context, "https://github.com/${Updater.REPO}/releases")
                    }
                }
                ClickRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") {}
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (toneDialog) {
        val preview = rememberTonePreview()
        AlertDialog(
            onDismissRequest = {
                preview.stop()
                toneDialog = false
            },
            title = { Text("Default alarm sound") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "New alarms start with this sound. Each to-do can still pick its own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    TonePicker(selected = s.defaultTone, preview = preview, onSelect = { id -> update { it.copy(defaultTone = id) } })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    preview.stop()
                    toneDialog = false
                }) { Text("Done") }
            },
        )
    }
    quietPicker?.let { which ->
        val minutes = if (which == "start") s.quietStartMinute else s.quietEndMinute
        TimeDialog(
            title = if (which == "start") "Quiet from" else "Quiet until",
            hour = minutes / 60,
            minute = minutes % 60,
            onDismiss = { quietPicker = null },
            onConfirm = { h, m ->
                quietPicker = null
                val v = h * 60 + m
                update { if (which == "start") it.copy(quietStartMinute = v) else it.copy(quietEndMinute = v) }
            },
        )
    }
}

@Composable
private fun UpdatesCard(s: AppSettings, onAutoUpdate: (Boolean) -> Unit) {
    val context = LocalContext.current.applicationContext
    val state by Updater.state.collectAsStateWithLifecycle()
    var showNotes by remember { mutableStateOf(false) }
    val working = state is Updater.State.Checking || state is Updater.State.Downloading || state is Updater.State.Installing

    Card("Updates") {
        Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("To-Do ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
            val status = when (val st = state) {
                Updater.State.Idle -> if (s.lastUpdateCheck > 0) "Last checked ${Fmt.moment(context, s.lastUpdateCheck)}" else "Not checked yet"
                Updater.State.Checking -> "Checking GitHub…"
                is Updater.State.UpToDate -> "You have the newest version (checked ${Fmt.time(context, st.checkedAt)})"
                is Updater.State.Available -> "Version ${st.release.tag} is available" + sizeText(st.release.size)
                is Updater.State.Downloading -> "Downloading ${st.release.tag}… ${(st.progress * 100).toInt()}%"
                is Updater.State.Installing -> "Installing ${st.release.tag}…"
                is Updater.State.WaitingForConfirmation -> "Confirm the install on the screen or in the notification."
                is Updater.State.Failed -> st.message
            }
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state is Updater.State.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("updateStatus"),
            )
            when (val st = state) {
                is Updater.State.Downloading -> LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth())
                Updater.State.Checking, is Updater.State.Installing -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> Unit
            }
            val release = when (val st = state) {
                is Updater.State.Available -> st.release
                is Updater.State.Failed -> st.release
                is Updater.State.WaitingForConfirmation -> st.release
                else -> null
            }
            if (release != null && release.notes.isNotBlank()) {
                TextButton(onClick = { showNotes = !showNotes }, modifier = Modifier.padding(start = 0.dp)) {
                    Text(if (showNotes) "Hide what's new" else "What's new in ${release.tag}")
                }
                AnimatedVisibility(showNotes) {
                    Text(release.notes.trim(), style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (release != null) {
                    Button(
                        onClick = { AppScope.launch { Updater.downloadAndInstall(context, release, userInitiated = true) } },
                        enabled = !working,
                        modifier = Modifier.testTag("updateNow"),
                    ) { Text(if (state is Updater.State.Available) "Update now" else "Try again") }
                }
                OutlinedButton(
                    onClick = { AppScope.launch { Updater.check(context) } },
                    enabled = !working && Updater.enabled,
                    modifier = Modifier.testTag("checkUpdates"),
                ) { Text("Check for updates") }
            }
            if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                Text(
                    "The first update asks you to allow To-Do to install apps. After that, updates install by themselves.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Divider()
        SwitchRow(
            title = "Automatic updates",
            subtitle = "Download and install new versions when you're not using the app",
            checked = s.autoUpdate,
            onChange = onAutoUpdate,
            tag = "autoUpdateSwitch",
        )
    }
}

/** Settings > Google Sheet: connect the sheet's web app, see how the last sync went. */
@Composable
private fun SheetCard(data: AppData) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val running by SheetSync.running.collectAsStateWithLifecycle()
    var connectDialog by remember { mutableStateOf(false) }
    var disconnectDialog by remember { mutableStateOf(false) }
    val sheet = data.sheet

    Card("Google Sheet") {
        if (data.settings.sheetUrl.isBlank()) {
            ClickRow(
                "Connect a Google Sheet",
                "Its to-do list comes in under ${SheetMerge.WORK}, and is ticked off here when it's done there",
                tag = "sheetConnect",
            ) { connectDialog = true }
        } else {
            Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.TableChart, contentDescription = null, tint = sheetGreen(), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (sheet.name.isBlank()) "Synced with your sheet" else "Synced with ${sheet.name}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                val status = when {
                    running -> "Syncing…"
                    sheet.lastSync == 0L -> "Not synced yet"
                    else -> "${lines(sheet.openLines)} on the sheet · synced ${Fmt.moment(context, sheet.lastSync)}"
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("sheetStatus"),
                )
                val error = sheet.lastError
                if (error != null && !running) {
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("sheetError"))
                }
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "Syncs about every 15 minutes and when you open the app. Changes here never go back to the sheet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { scope.launch { SheetSync.sync(context) } },
                        enabled = !running,
                        modifier = Modifier.testTag("sheetSyncNow"),
                    ) { Text("Sync now") }
                    if (sheet.url.isNotBlank()) {
                        OutlinedButton(onClick = { openUrl(context, sheet.url) }) { Text("Open the sheet") }
                    }
                }
            }
            Divider()
            ClickRow("Disconnect", "Stops syncing. The to-dos from the sheet stay.", tag = "sheetDisconnect") { disconnectDialog = true }
        }
    }

    if (connectDialog) {
        ConnectSheetDialog(running = running, onDismiss = { connectDialog = false }) { link, onError ->
            scope.launch {
                val error = SheetSync.connect(context, link)
                if (error != null) {
                    onError(error)
                } else {
                    connectDialog = false
                    val n = Repo.data.value.todos.count { it.sheetKey != null && !it.done }
                    // A toast needs the main thread, and this may resume elsewhere (it does in tests).
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Connected. ${if (n == 0) "Nothing to do on the sheet" else "${todos(n)} from the sheet"}.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
    if (disconnectDialog) {
        AlertDialog(
            onDismissRequest = { disconnectDialog = false },
            title = { Text("Disconnect the sheet?") },
            text = { Text("New lines on the sheet stop coming in. The to-dos already here stay, and you can connect again any time.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        disconnectDialog = false
                        scope.launch { SheetSync.disconnect(context) }
                    },
                    modifier = Modifier.testTag("sheetDisconnectConfirm"),
                ) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { disconnectDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConnectSheetDialog(running: Boolean, onDismiss: () -> Unit, onConnect: (link: String, onError: (String) -> Unit) -> Unit) {
    val context = LocalContext.current
    var link by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("Connect a Google Sheet") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Once, in the sheet on a computer:", style = MaterialTheme.typography.bodyMedium)
                listOf(
                    "Extensions > Apps Script. Paste the To-Do script and save.",
                    "Deploy > New deployment > Web app. Execute as: Me. Who has access: Anyone.",
                    "Copy the web app link (it ends in /exec) and paste it below.",
                ).forEachIndexed { i, step ->
                    Text("${i + 1}. $step", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { openUrl(context, "https://github.com/${Updater.REPO}#google-sheet-sync") }) {
                    Text("The script and the full steps")
                }
                val shownError = error
                OutlinedTextField(
                    value = link,
                    onValueChange = {
                        link = it
                        error = null
                    },
                    label = { Text("Web app link") },
                    placeholder = { Text("https://script.google.com/macros/s/…/exec") },
                    isError = shownError != null,
                    supportingText = if (shownError != null) {
                        { Text(shownError, modifier = Modifier.testTag("sheetConnectError")) }
                    } else {
                        null
                    },
                    maxLines = 3,
                    // A link: no autocorrect or suggestions rewriting it while it is typed or edited.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth().testTag("sheetUrlField"),
                )
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConnect(link) { error = it } },
                enabled = !running && link.isNotBlank(),
                modifier = Modifier.testTag("sheetConnectButton"),
            ) { Text(if (running) "Connecting…" else "Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !running) { Text("Cancel") } },
    )
}

private fun lines(n: Int) = if (n == 1) "1 line" else "$n lines"

private fun todos(n: Int) = if (n == 1) "1 to-do" else "$n to-dos"

private fun ThemeMode.icon() = when (this) {
    ThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto
    ThemeMode.LIGHT -> Icons.Rounded.LightMode
    ThemeMode.DARK -> Icons.Rounded.DarkMode
}

private fun sizeText(bytes: Long) = if (bytes > 0) " (%.1f MB)".format(bytes / 1_048_576.0) else ""

private data class Check(val title: String, val ok: Boolean, val okText: String, val fixText: String, val fix: Intent?)

@Composable
private fun ReliabilityCard() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val checks = remember(refresh) { reliabilityChecks(context) }
    Card("Reliable reminders") {
        checks.forEachIndexed { i, c ->
            if (i > 0) Divider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (c.ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                    contentDescription = if (c.ok) "OK" else "Needs attention",
                    tint = if (c.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (c.ok) c.okText else c.fixText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!c.ok && c.fix != null) {
                    TextButton(onClick = { launch(context, c.fix) }) { Text("Fix") }
                }
            }
        }
        brandTip()?.let { tip ->
            Divider()
            Text(
                tip,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

private fun reliabilityChecks(context: Context): List<Check> {
    val pkg = Uri.parse("package:${context.packageName}")
    val list = mutableListOf<Check>()
    list += Check(
        "Notifications",
        NotificationManagerCompat.from(context).areNotificationsEnabled(),
        "Allowed",
        "Blocked: reminders cannot show",
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )
    list += Check(
        "Exact timing",
        ReminderScheduler.canExact(context),
        "Reminders ring on the minute",
        "Off: reminders may be a few minutes late",
        if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg) else null,
    )
    if (Build.VERSION.SDK_INT >= 34) {
        list += Check(
            "Full-screen alarms",
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent(),
            "Alarms open over the lock screen",
            "Off: alarms show only as a notification",
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg),
        )
    }
    list += Check(
        "Battery",
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
        "Not restricted",
        "Optimized: some phones delay reminders",
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg),
    )
    list += Check(
        "Install updates",
        context.packageManager.canRequestPackageInstalls(),
        "Allowed",
        "Needed once so updates can install",
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg),
    )
    return list
}

private fun brandTip(): String? = when (Build.MANUFACTURER.lowercase()) {
    "honor", "huawei" -> "On ${Build.MANUFACTURER} phones also open Settings → Battery → App launch → To-Do, choose Manage manually and keep all three switches on."
    "xiaomi", "redmi", "poco" -> "On Xiaomi phones also turn on Autostart for To-Do and set Battery saver to No restrictions."
    "oppo", "realme", "oneplus", "vivo" -> "On ${Build.MANUFACTURER} phones also allow To-Do to auto-launch and run in the background."
    "samsung" -> "On Samsung phones also make sure To-Do is not in Sleeping apps (Battery → Background usage limits)."
    else -> null
}

private fun launch(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            Toast.makeText(context, "Open the phone's Settings → Apps → To-Do", Toast.LENGTH_LONG).show()
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, url, Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
        )
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

@Composable
private fun ClickRow(title: String, value: String, icon: Boolean = false, tag: String? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (icon) Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}
