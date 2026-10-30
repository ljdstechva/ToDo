package com.ljdstechva.todo.sheet

import android.content.Context
import android.util.Log
import com.ljdstechva.todo.BuildConfig
import com.ljdstechva.todo.data.Repo
import com.ljdstechva.todo.data.SheetResponse
import com.ljdstechva.todo.data.SheetState
import com.ljdstechva.todo.data.TodoActions
import com.ljdstechva.todo.reminder.Notifications
import com.ljdstechva.todo.reminder.ReminderScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the to-do list from the Google Sheet's web app (sheet-sync/Code.gs) and brings it into
 * the app ([SheetMerge]). Runs every 15 minutes ([SheetSyncWorker]), when the app opens and from
 * Settings > Google Sheet > Sync now. One-way: the app never writes to the sheet.
 */
object SheetSync {
    private const val TAG = "SheetSync"

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> get() = _running

    val connected: Boolean get() = Repo.settings.sheetUrl.isNotBlank()

    /**
     * Checks [link] and, when it answers with a to-do list, saves it and brings the list in.
     * Returns null when connected, else why not (nothing is saved then).
     */
    suspend fun connect(context: Context, link: String): String? {
        val url = try {
            normalize(link)
        } catch (e: IOException) {
            return e.message
        }
        val error = mutex.withLock {
            _running.value = true
            try {
                val response = fetch(url)
                Repo.update { d -> d.copy(settings = d.settings.copy(sheetUrl = url), sheet = d.sheet.copy(lastError = null)) }
                bringIn(context, response)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Connecting failed", e)
                e.message ?: "Could not reach the sheet."
            } finally {
                _running.value = false
            }
        }
        if (error == null) SheetSyncWorker.schedule(context)
        return error
    }

    /** Stops syncing. The to-dos that came from the sheet stay, as they are. */
    suspend fun disconnect(context: Context) {
        SheetSyncWorker.cancel(context)
        mutex.withLock {
            Repo.update { d -> d.copy(settings = d.settings.copy(sheetUrl = ""), sheet = SheetState()) }
        }
    }

    /** Syncs now. Returns null when it worked, else what went wrong (also kept for Settings). */
    suspend fun sync(context: Context): String? = mutex.withLock {
        val url = Repo.settings.sheetUrl
        if (url.isBlank()) return@withLock null
        _running.value = true
        try {
            bringIn(context, fetch(url))
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed", e)
            val why = e.message ?: "Could not reach the sheet."
            Repo.update { d -> if (d.settings.sheetUrl == url) d.copy(sheet = d.sheet.copy(lastError = why)) else d }
            why
        } finally {
            _running.value = false
        }
    }

    private suspend fun bringIn(context: Context, response: SheetResponse) {
        var result: SheetMerge.Result? = null
        Repo.update { d -> SheetMerge.merge(d, response, System.currentTimeMillis()).also { result = it }.data }
        val r = result ?: return
        // Ticked off by the sheet: the same clean-up as ticking it off by hand.
        for (t in r.ticked) {
            ReminderScheduler.cancel(context, t.id, ReminderScheduler.KIND_SNOOZE)
            Notifications.cancel(context, t.id)
        }
        (r.ticked + r.reopened + r.added).forEach { TodoActions.reschedule(context, it.id) }
        Log.i(TAG, "Synced: ${r.added.size} new, ${r.ticked.size} ticked off, ${r.reopened.size} reopened, complete=${r.complete}")
    }

    private suspend fun fetch(url: String): SheetResponse = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            // Apps Script answers /exec with a redirect to googleusercontent.com, which has the list.
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ToDo-SheetSync/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = conn.responseCode
            when {
                code == 404 -> throw IOException("The link was not found (404). Was the web app deployment deleted?")
                code !in 200..299 -> throw IOException("The sheet's web app answered HTTP $code.")
            }
            parse(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** The web app's answer, or an error that says what to fix. Pure, unit-tested. */
    fun parse(body: String): SheetResponse {
        val text = body.trim()
        if (text.startsWith("<")) {
            throw IOException(
                "The link opened a web page, not the to-do list. In Apps Script, check the web app's " +
                    "\"Who has access\" is Anyone (Deploy > Manage deployments).",
            )
        }
        val response = try {
            json.decodeFromString(SheetResponse.serializer(), text)
        } catch (e: Exception) {
            throw IOException("The link did not answer with the to-do list. Use the web app's /exec link.")
        }
        if (!response.ok) {
            throw IOException(response.error?.let { "The sheet's script says: $it" } ?: "The link did not answer with the to-do list. Use the web app's /exec link.")
        }
        return response
    }

    /** Trims a pasted link and catches the usual mix-ups. Pure, unit-tested. */
    fun normalize(link: String): String {
        var url = link.trim()
        if (url.isEmpty()) throw IOException("Paste the web app link first.")
        if (!url.contains("://")) url = "https://$url"
        if (!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("That is not a web link.")
        if (Regex("""script\.google\.com/.+/dev/?(\?.*)?$""").containsMatchIn(url)) {
            throw IOException("That is the /dev test link, which only works in your browser. Use the /exec link from Deploy > Manage deployments.")
        }
        if (url.contains("docs.google.com/spreadsheets")) {
            throw IOException("That is the sheet's own link. Use the web app's /exec link from Apps Script (see the steps).")
        }
        return url
    }
}
