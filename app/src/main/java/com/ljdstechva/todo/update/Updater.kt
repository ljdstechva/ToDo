package com.ljdstechva.todo.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.ljdstechva.todo.AppScope
import com.ljdstechva.todo.AppVisibility
import com.ljdstechva.todo.BuildConfig
import com.ljdstechva.todo.alarm.AlarmService
import com.ljdstechva.todo.data.Repo
import com.ljdstechva.todo.data.TodoActions
import com.ljdstechva.todo.reminder.Notifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Release(
    val tag: String,
    val versionCode: Int,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val size: Long,
    val publishedAt: String,
)

/**
 * Self-update from the public GitHub releases of [REPO] (no token anywhere). A release is newer
 * when the last number of its tag (v1.0.N, N = versionCode) is higher than this build's.
 *
 * Installing uses a PackageInstaller session that asks for no user action. Android 12+ honours
 * that for an app updating itself (UPDATE_PACKAGES_WITHOUT_USER_ACTION); otherwise, or when the
 * phone insists, the system shows its own "Update" confirmation.
 */
object Updater {
    private const val TAG = "Updater"
    val REPO: String = BuildConfig.UPDATE_REPO
    val enabled: Boolean get() = REPO.isNotBlank()

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val checkedAt: Long) : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data class Installing(val release: Release) : State
        data class WaitingForConfirmation(val release: Release) : State
        data class Failed(val message: String, val release: Release? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private val installMutex = Mutex()

    @Volatile
    private var lastSuccess: Pair<Long, Release?>? = null

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Asks GitHub for the newest release. Returns it when it is newer than this build. A result
     * younger than [reuseWithinMs] is reused instead of asking again (rate limit: 60/hour per IP).
     */
    suspend fun check(context: Context, reuseWithinMs: Long = 0): Release? {
        if (!enabled) {
            _state.value = State.Failed("Updates are switched off in this build.")
            return null
        }
        val cached = lastSuccess
        if (cached != null && System.currentTimeMillis() - cached.first < reuseWithinMs) return cached.second
        if (!busy()) _state.value = State.Checking
        return try {
            val latest = fetchLatest()
            val now = System.currentTimeMillis()
            TodoActions.updateSettings(context) { it.copy(lastUpdateCheck = now) }
            val newer = latest?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
            lastSuccess = now to newer
            if (!busy()) _state.value = if (newer != null) State.Available(newer) else State.UpToDate(now)
            newer
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            if (!busy()) _state.value = State.Failed(e.message ?: "Could not reach GitHub.")
            null
        }
    }

    /** A download or install is running; a check must not overwrite its state. */
    private fun busy(): Boolean = _state.value.let {
        it is State.Downloading || it is State.Installing || it is State.WaitingForConfirmation
    }

    private suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        val conn = open("https://api.github.com/repos/$REPO/releases?per_page=30", "application/vnd.github+json")
        try {
            when (val code = conn.responseCode) {
                200 -> newest(parseReleases(conn.inputStream.bufferedReader().use { it.readText() }))
                403, 429 -> fetchLatestByRedirect() // API rate limit: the web redirect has its own limit
                404 -> throw IOException("Release list not found (404). Is the repository $REPO still public?")
                else -> throw IOException("GitHub answered HTTP $code.")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** github.com/<repo>/releases/latest redirects to /releases/tag/<tag>; the APK name is fixed. */
    private fun fetchLatestByRedirect(): Release? {
        val conn = open("https://github.com/$REPO/releases/latest", "text/html")
        conn.instanceFollowRedirects = false
        try {
            val location = conn.getHeaderField("Location") ?: return null
            val tag = location.substringAfterLast("/tag/", "").takeIf { it.isNotBlank() } ?: return null
            val code = versionCodeOf(tag) ?: return null
            return Release(tag, code, "To-Do $tag", "", "https://github.com/$REPO/releases/download/$tag/${apkName(tag)}", -1, "")
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", accept)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "ToDo-Updater/${BuildConfig.VERSION_NAME}")
        }

    fun apkName(tag: String) = "ToDo-$tag.apk"

    /** v1.0.12 -> 12. */
    fun versionCodeOf(tag: String): Int? =
        Regex("""v?(\d+)\.(\d+)\.(\d+)""").matchEntire(tag.trim())?.groupValues?.get(3)?.toIntOrNull()

    /** Releases with an .apk asset, drafts skipped. Pure, unit-tested. */
    fun parseReleases(body: String): List<Release> {
        val out = mutableListOf<Release>()
        for (el in json.parseToJsonElement(body).jsonArray) {
            val rel = el.jsonObject
            if (rel.bool("draft")) continue
            val tag = rel.str("tag_name")
            val code = versionCodeOf(tag) ?: continue
            val asset = rel["assets"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it.str("name").endsWith(".apk", ignoreCase = true) } ?: continue
            out += Release(
                tag = tag,
                versionCode = code,
                title = rel.str("name").ifBlank { tag },
                notes = rel.str("body"),
                apkUrl = asset.str("browser_download_url"),
                size = asset["size"]?.jsonPrimitive?.longOrNull ?: -1,
                publishedAt = rel.str("published_at"),
            )
        }
        return out
    }

    fun newest(releases: List<Release>): Release? = releases.maxByOrNull { it.versionCode }

    private fun JsonObject.str(key: String): String =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.takeIf { it != "null" }.orEmpty()

    private fun JsonObject.bool(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull ?: false

    /**
     * Downloads and installs [release]. An automatic update ([userInitiated] false) waits while a
     * screen of the app is open or an alarm rings, so nobody loses what they are doing.
     */
    suspend fun downloadAndInstall(context: Context, release: Release, userInitiated: Boolean): Unit = installMutex.withLock<Unit> {
        try {
            val file = download(context, release)
            verify(context, file)
            if (!userInitiated && (AppVisibility.isForeground || AlarmService.ringing.value != null)) {
                Log.i(TAG, "Update ${release.tag} downloaded, installing once the app is closed")
                _state.value = State.Available(release)
                return@withLock
            }
            _state.value = State.Installing(release)
            if (!userInitiated) Notifications.showUpdate(context, "Updating To-Do", "Installing ${release.tag}…", Notifications.openAppIntent(context, true), progress = -1)
            install(context, file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Update to ${release.tag} failed", e)
            _state.value = State.Failed(e.message ?: "Update failed.", release)
            if (!userInitiated) Notifications.cancelUpdate(context)
        }
    }

    private fun updatesDir(context: Context) = File(context.cacheDir, "updates").apply { mkdirs() }

    fun clearDownloads(context: Context) {
        updatesDir(context).listFiles()?.forEach { it.delete() }
    }

    private suspend fun download(context: Context, release: Release): File = withContext(Dispatchers.IO) {
        val dir = updatesDir(context)
        val target = File(dir, apkName(release.tag))
        dir.listFiles()?.forEach { if (it.name != target.name) it.delete() }
        if (target.isFile && (release.size < 0 || target.length() == release.size)) return@withContext target
        _state.value = State.Downloading(release, 0f)
        val part = File(dir, target.name + ".part")
        val conn = open(release.apkUrl, "application/octet-stream")
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("Download failed: HTTP $code.")
            val total = if (release.size > 0) release.size else conn.contentLengthLong
            var done = 0L
            var lastShown = -1
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastShown) {
                                lastShown = pct
                                _state.value = State.Downloading(release, pct / 100f)
                            }
                        }
                    }
                }
            }
            if (release.size > 0 && part.length() != release.size) throw IOException("Download incomplete, try again.")
            if (!part.renameTo(target)) throw IOException("Could not save the download.")
            target
        } finally {
            conn.disconnect()
        }
    }

    private fun verify(context: Context, file: File) {
        val info = context.packageManager.getPackageArchiveInfo(file.path, 0)
            ?: throw IOException("The downloaded file is not a valid app.")
        if (info.packageName != context.packageName) throw IOException("The download is a different app (${info.packageName}).")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        if (code <= BuildConfig.VERSION_CODE) throw IOException("The download is not newer than this version.")
    }

    private fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setAction(InstallResultReceiver.ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
        }
        Log.i(TAG, "Install session $sessionId committed")
    }

    internal fun onNeedsConfirmation() {
        currentRelease()?.let { _state.value = State.WaitingForConfirmation(it) }
    }

    internal fun onInstallFailed(message: String) {
        _state.value = State.Failed(message, currentRelease())
    }

    private fun currentRelease(): Release? = when (val s = _state.value) {
        is State.Available -> s.release
        is State.Downloading -> s.release
        is State.Installing -> s.release
        is State.WaitingForConfirmation -> s.release
        is State.Failed -> s.release
        else -> null
    }

    /** Leaving the app with an update waiting: install it now (automatic updates only). */
    fun onAppBackgrounded(context: Context) {
        if (!enabled || !Repo.settings.autoUpdate) return
        if (_state.value is State.Available) UpdateWorker.runNow(context)
    }

    /** Opening the app: look for an update if the last look is older than 3 hours. */
    fun checkOnLaunch(context: Context) {
        if (!enabled) return
        if (System.currentTimeMillis() - Repo.settings.lastUpdateCheck < 3 * 3_600_000L) return
        AppScope.launch { check(context) }
    }
}
