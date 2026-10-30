package com.ljdstechva.todo

import android.content.Intent
import android.util.Log
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ljdstechva.todo.Live.ctx
import com.ljdstechva.todo.Live.device
import com.ljdstechva.todo.Live.shot
import com.ljdstechva.todo.data.Repo
import com.ljdstechva.todo.data.SheetLine
import com.ljdstechva.todo.data.SheetResponse
import com.ljdstechva.todo.data.Todo
import com.ljdstechva.todo.sheet.SheetSync
import com.ljdstechva.todo.sheet.SheetSyncWorker
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Google Sheet sync through the real screens, against a stand-in for the sheet's web app on
 * this phone: connect in Settings, the lines come in under Work with the Google Sheet badge, a
 * line settled on the sheet is ticked off, a new one comes in when the app is opened.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SheetSyncLiveTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val sheet = FakeSheet()

    @Before fun setUp() {
        Live.grantEverything()
        Live.reset()
        sheet.start()
        rule.waitForIdle()
    }

    @After fun tearDown() {
        runBlocking { SheetSync.disconnect(ctx) }
        sheet.stop()
        Live.reset()
    }

    private fun synced(): List<Todo> = Repo.data.value.todos.filter { it.sheetKey != null }
    private fun synced(title: String) = synced().single { it.title == title }
    private fun periodic(): List<WorkInfo> = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(SheetSyncWorker.PERIODIC).get()

    private fun openSettings(tag: String) {
        rule.onNodeWithTag("settingsButton").performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag(tag), 5_000)
        rule.onNodeWithTag(tag).performScrollTo()
    }

    /**
     * Waits for [matcher] in the unmerged tree: a to-do row merges its texts and tags into one
     * node, where its badge's tag and its category text are no longer found on their own.
     */
    private fun waitFor(matcher: SemanticsMatcher, timeoutMs: Long = 5_000) {
        try {
            rule.waitUntil(timeoutMs) { rule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("Not shown within ${timeoutMs}ms: ${matcher.description}", e)
        }
    }

    private fun back() {
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitUntilExactlyOneExists(hasTestTag("settingsButton"), 5_000)
    }

    @Test fun connectSyncTickOffAndSyncOnOpen() {
        sheet.lines = listOf(SAND, HAULER)

        // A link that opens a sign-in page: the dialog says what to fix and nothing is saved.
        openSettings("sheetConnect")
        shot("sheet-settings")
        rule.onNodeWithTag("sheetConnect").performClick()
        rule.waitUntilExactlyOneExists(hasTestTag("sheetUrlField"), 5_000)
        rule.onNodeWithTag("sheetUrlField").performTextInput(sheet.url("/signin"))
        rule.onNodeWithTag("sheetConnectButton").performClick()
        waitFor(hasTestTag("sheetConnectError") and hasText("Anyone", substring = true), 10_000)
        assertEquals("", Repo.settings.sheetUrl)
        shot("sheet-connect-error")

        // The /exec link, which redirects like Google's does.
        rule.onNodeWithTag("sheetUrlField").performTextClearance()
        rule.onNodeWithTag("sheetUrlField").performTextInput(sheet.url("/exec"))
        rule.onNodeWithTag("sheetConnectButton").performClick()
        rule.waitUntil(10_000) { synced().size == 2 }
        rule.waitUntilDoesNotExist(hasTestTag("sheetUrlField"), 5_000)
        assertTrue("followed the redirect: ${sheet.hits}", "/echo" in sheet.hits)
        assertEquals(sheet.url("/exec"), Repo.settings.sheetUrl)
        waitFor(hasTestTag("sheetStatus") and hasText("2 lines on the sheet", substring = true))
        assertTrue("background sync scheduled: ${periodic()}", periodic().any { !it.state.isFinished })
        rule.onNodeWithTag("sheetStatus").performScrollTo()
        shot("sheet-connected")

        // On the list: both under Work, with the Google Sheet badge, in the sheet's order.
        back()
        val work = Repo.data.value.categories.single { it.name == "Work" }
        assertEquals(listOf(SAND.what, HAULER.what), synced().sortedBy { it.createdAt }.map { it.title })
        for (t in synced()) {
            assertEquals(work.id, t.categoryId)
            waitFor(hasTestTag("sheetBadge") and hasAnyAncestor(hasTestTag("todo-${t.id}")))
            rule.onNode(hasText("Work") and hasAnyAncestor(hasTestTag("todo-${t.id}")), useUnmergedTree = true).assertExists()
        }
        assertEquals("₱1,000.00 · Oct 1, 2026 · 🪨 Materials", synced(SAND.what).note)
        shot("sheet-home")

        // Opened: the editor says where it came from and can open its row in the sheet.
        val sand = synced(SAND.what)
        rule.onNodeWithTag("todo-${sand.id}").performClick()
        rule.waitUntilExactlyOneExists(hasTestTag("sheetBanner"), 5_000)
        waitFor(hasText("From your Google Sheet (Sample Sheet)"))
        rule.onNodeWithTag("openInSheet").assertExists()
        shot("sheet-edit-banner")
        device.pressBack()
        rule.waitUntilDoesNotExist(hasTestTag("sheetBanner"), 5_000)

        // The sand line is settled on the sheet: Sync now ticks it off; the other stays open.
        sheet.lines = listOf(HAULER)
        openSettings("sheetSyncNow")
        rule.onNodeWithTag("sheetSyncNow").performClick()
        rule.waitUntil(10_000) { Repo.todo(sand.id)?.let { it.done && it.sheetDone } == true }
        assertFalse(synced(HAULER.what).done)
        waitFor(hasTestTag("sheetStatus") and hasText("1 line on the sheet", substring = true))
        back()
        rule.waitUntilExactlyOneExists(hasText("Completed · 1"), 5_000)
        rule.onNodeWithTag("todo-${sand.id}").assertExists()
        Thread.sleep(600) // the strike-through animation, for the screenshot
        shot("sheet-ticked-off")

        // A new line while the app is in the background comes in when it is opened again.
        sheet.lines = listOf(HAULER, PERMIT)
        device.pressHome()
        device.waitForIdle()
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        Live.waitUntil(30_000, "the new line after opening the app") { synced().any { it.title == PERMIT.what } }
        rule.waitUntilExactlyOneExists(hasText(PERMIT.what), 5_000)
        shot("sheet-synced-on-open")

        // Disconnect: no more syncing, the to-dos stay.
        openSettings("sheetDisconnect")
        rule.onNodeWithTag("sheetDisconnect").performClick()
        rule.waitUntilExactlyOneExists(hasTestTag("sheetDisconnectConfirm"), 5_000)
        rule.onNodeWithTag("sheetDisconnectConfirm").performClick()
        rule.waitUntil(5_000) { Repo.settings.sheetUrl.isEmpty() }
        rule.waitUntilExactlyOneExists(hasTestTag("sheetConnect"), 5_000)
        Live.waitUntil(5_000, "the background sync to stop") { periodic().all { it.state.isFinished } }
        assertEquals(3, synced().size)
    }

    /** Stand-in for the sheet's Apps Script web app: /exec redirects (as Google's does) to /echo, the list. */
    private class FakeSheet {
        @Volatile var lines: List<SheetLine> = emptyList()
        val hits = CopyOnWriteArrayList<String>()
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

        fun url(path: String) = "http://127.0.0.1:${socket.localPort}$path"

        fun start() = Thread { serve() }.apply { isDaemon = true }.start()

        fun stop() = socket.close()

        private fun serve() {
            while (true) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    return // closed by stop()
                }
                try {
                    client.use { answer(it) }
                } catch (e: IOException) {
                    Log.w("FakeSheet", "Request failed", e)
                }
            }
        }

        private fun answer(client: Socket) {
            val input = client.getInputStream().bufferedReader()
            val path = input.readLine()?.split(" ")?.getOrNull(1)?.substringBefore("?") ?: return
            while (!input.readLine().isNullOrEmpty()) Unit // the request headers
            hits += path
            val (status, headers, body) = when (path) {
                "/exec" -> Triple("302 Found", "Location: ${url("/echo")}\r\n", "")
                "/echo" -> Triple("200 OK", "Content-Type: application/json; charset=utf-8\r\n", list())
                "/signin" -> Triple("200 OK", "Content-Type: text/html; charset=utf-8\r\n", "<!DOCTYPE html><html><body>Sign in</body></html>")
                else -> Triple("404 Not Found", "", "")
            }
            val bytes = body.toByteArray()
            client.getOutputStream().apply {
                write("HTTP/1.1 $status\r\n${headers}Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes)
                flush()
            }
        }

        private fun list(): String {
            val current = lines
            return Json.encodeToString(
                SheetResponse.serializer(),
                SheetResponse(ok = true, sheet = "Sample Sheet", url = "https://docs.google.com/spreadsheets/d/sample/edit", count = current.size, items = current),
            )
        }
    }

    private companion object {
        val SAND = SheetLine("🪨 Materials · row 5", "Oct 1, 2026", "₱1,000.00", "💵 Awaiting payment · Sample Client · Sand", "https://docs.google.com/spreadsheets/d/sample/edit#gid=1&range=A5")
        val HAULER = SheetLine("🤝 Funds · row 3", "Oct 2, 2026", "₱250.50", "⏳ To return · Sample Hauler")
        val PERMIT = SheetLine("🦺 PCO · row 7", "Oct 3, 2026", "₱500.00", "🧾 Renew the sample permit")
    }
}
