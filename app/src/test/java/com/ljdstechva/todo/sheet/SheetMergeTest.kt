package com.ljdstechva.todo.sheet

import com.ljdstechva.todo.data.AppData
import com.ljdstechva.todo.data.Category
import com.ljdstechva.todo.data.SheetLine
import com.ljdstechva.todo.data.SheetResponse
import com.ljdstechva.todo.data.SheetState
import com.ljdstechva.todo.data.Todo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class SheetMergeTest {

    private val start = AppData(
        categories = listOf(Category(1, "Personal", 6), Category(2, "Work", 5), Category(3, "Errands", 3)),
        todos = listOf(Todo(id = 4, title = "Buy milk", createdAt = 1)),
        nextId = 5,
    )
    private val sand = SheetLine("🪨 Materials · row 5", "Oct 1, 2026", "₱1,000.00", "💵 Awaiting payment · Sample Client · Sand", "https://s/#gid=1&range=A5")
    private val hauler = SheetLine("🤝 Funds · row 3", "Oct 2, 2026", "₱250.50", "⏳ To return · Sample Hauler")

    private fun response(vararg lines: SheetLine, count: Int? = lines.size) =
        SheetResponse(ok = true, sheet = "FINANCE", url = "https://s", count = count, items = lines.toList())

    private fun AppData.synced() = todos.filter { it.sheetKey != null }
    private fun AppData.byTitle(title: String) = todos.single { it.title == title }

    @Test fun newLinesComeInUnderWorkInSheetOrder() {
        val r = SheetMerge.merge(start, response(sand, hauler), now = 1000)
        val d = r.data
        assertEquals(2, r.added.size)
        assertEquals(listOf(sand.what, hauler.what), d.synced().sortedBy { it.createdAt }.map { it.title })
        assertTrue(d.synced().all { it.categoryId == 2L && !it.done })
        assertEquals("₱1,000.00 · Oct 1, 2026 · 🪨 Materials", d.byTitle(sand.what).note)
        assertEquals(sand.link, d.byTitle(sand.what).sheetLink)
        assertEquals(listOf(5L, 6L), d.synced().map { it.id })
        assertEquals(7, d.nextId)
        assertEquals("FINANCE", d.sheet.name)
        assertEquals(2, d.sheet.openLines)
        assertEquals(1000, d.sheet.lastSync)
        assertNull(d.sheet.lastError)
        // The user's own to-do is untouched.
        assertEquals(start.todos[0], d.todos.first { it.id == 4L })
    }

    @Test fun syncingTheSameListTwiceChangesNothingButTheTime() {
        val once = SheetMerge.merge(start, response(sand, hauler), now = 1000).data
        val twice = SheetMerge.merge(once, response(sand, hauler), now = 2000)
        assertTrue(twice.added.isEmpty() && twice.ticked.isEmpty() && twice.reopened.isEmpty())
        assertEquals(once.todos, twice.data.todos)
        assertEquals(2000, twice.data.sheet.lastSync)
    }

    @Test fun aLineThatLeftTheSheetIsTickedOffAndComesBackOpen() {
        val once = SheetMerge.merge(start, response(sand, hauler), now = 1000).data
        val gone = SheetMerge.merge(once, response(hauler), now = 2000)
        val t = gone.data.byTitle(sand.what)
        assertEquals(listOf(t.id), gone.ticked.map { it.id })
        assertTrue(t.done && t.sheetDone)
        assertEquals(2000L, t.doneAt)
        assertFalse(gone.data.byTitle(hauler.what).done)

        val back = SheetMerge.merge(gone.data, response(sand, hauler), now = 3000)
        assertEquals(listOf(t.id), back.reopened.map { it.id })
        val again = back.data.byTitle(sand.what)
        assertFalse(again.done || again.sheetDone)
        assertNull(again.doneAt)
    }

    @Test fun anEmptySheetTicksEverythingOff() {
        val once = SheetMerge.merge(start, response(sand, hauler), now = 1000).data
        val r = SheetMerge.merge(once, response(count = 0), now = 2000)
        assertEquals(2, r.ticked.size)
        assertTrue(r.data.synced().all { it.done })
        assertFalse("the user's own to-do stays open", r.data.todos.first { it.id == 4L }.done)
    }

    @Test fun aHalfReadListTicksNothingOffAndSaysSo() {
        val once = SheetMerge.merge(start, response(sand, hauler), now = 1000).data
        val r = SheetMerge.merge(once, response(hauler, count = 2), now = 2000)
        assertFalse(r.complete)
        assertTrue(r.ticked.isEmpty())
        assertFalse(r.data.byTitle(sand.what).done)
        assertEquals("Got 1 of the 2 lines the sheet lists, so none were ticked off", r.data.sheet.lastError)
    }

    @Test fun whatTheUserDidIsKept() {
        val once = SheetMerge.merge(start, response(sand, hauler), now = 1000).data
        val sandId = once.byTitle(sand.what).id
        // Ticked off here while still on the sheet, renamed, moved to Errands.
        val edited = once.copy(
            todos = once.todos.map {
                if (it.id == sandId) it.copy(done = true, doneAt = 1500, title = "Collect from client", categoryId = 3) else it
            },
        )
        val r = SheetMerge.merge(edited, response(sand, hauler), now = 2000)
        val t = r.data.todos.single { it.id == sandId }
        assertTrue("stays ticked", t.done)
        assertEquals("Collect from client", t.title)
        assertEquals(3L, t.categoryId)
        assertTrue(r.added.isEmpty() && r.reopened.isEmpty())
    }

    @Test fun aDeletedLineStaysAwayUntilItLeavesTheSheet() {
        val once = SheetMerge.merge(start, response(sand, hauler), now = 1000).data
        val sandKey = once.byTitle(sand.what).sheetKey!!
        val deleted = once.copy(todos = once.todos.filterNot { it.sheetKey == sandKey }, sheet = once.sheet.copy(removed = setOf(sandKey)))
        val r = SheetMerge.merge(deleted, response(sand, hauler), now = 2000)
        assertTrue(r.added.isEmpty())
        assertEquals(setOf(sandKey), r.data.sheet.removed)
        // Settled on the sheet: forgotten, so a new line just like it comes in again later.
        val settled = SheetMerge.merge(r.data, response(hauler), now = 3000).data
        assertTrue(settled.sheet.removed.isEmpty())
        // A half-read list keeps the memory.
        assertEquals(setOf(sandKey), SheetMerge.merge(r.data, response(hauler, count = 2), now = 3000).data.sheet.removed)
    }

    @Test fun theRowNumberIsNotPartOfTheIdentity() {
        val once = SheetMerge.merge(start, response(sand, hauler), now = 1000).data
        val moved = sand.copy(where = "🪨 Materials · row 4", link = "https://s/#gid=1&range=A4")
        val r = SheetMerge.merge(once, response(moved, hauler), now = 2000)
        assertTrue(r.added.isEmpty() && r.ticked.isEmpty())
        assertEquals("https://s/#gid=1&range=A4", r.data.byTitle(sand.what).sheetLink)
    }

    @Test fun twoEqualLinesAreTwoToDos() {
        val r = SheetMerge.merge(start, response(hauler, hauler), now = 1000)
        assertEquals(2, r.added.size)
        assertEquals(2, r.added.map { it.sheetKey }.toSet().size)
        val one = SheetMerge.merge(r.data, response(hauler), now = 2000)
        assertEquals(1, one.ticked.size)
    }

    @Test fun workIsMadeAgainWhenTheUserDeletedIt() {
        val noWork = start.copy(categories = start.categories.filterNot { it.name == "Work" })
        val r = SheetMerge.merge(noWork, response(sand), now = 1000)
        val work = r.data.categories.single { it.name == "Work" }
        assertEquals(5L, work.id)
        assertEquals(work.id, r.added.single().categoryId)
        assertEquals(6L, r.added.single().id)
        // Not made when nothing new needs it.
        val none = SheetMerge.merge(noWork, response(count = 0), now = 1000).data
        assertTrue(none.categories.none { it.name == "Work" })
        // Found whatever its case.
        val lower = start.copy(categories = listOf(Category(9, " work ", 1)))
        assertEquals(9L, SheetMerge.merge(lower, response(sand), now = 1).added.single().categoryId)
    }

    @Test fun parseReadsTheWebAppAnswer() {
        val r = SheetSync.parse(
            """{"ok":true,"count":1,"sheet":"FINANCE","url":"https://s","items":[{"where":"W","date":"D","amount":"A","what":"X","link":null}],"extra":1}""",
        )
        assertEquals(1, r.count)
        assertEquals("X", r.items.single().what)
    }

    @Test fun parseExplainsWhatToFix() {
        assertFails("Anyone") { SheetSync.parse("<!DOCTYPE html><html>Sign in</html>") }
        assertFails("/exec") { SheetSync.parse("not json") }
        assertFails("/exec") { SheetSync.parse("{}") }
        assertFails("No tab named") { SheetSync.parse("""{"ok":false,"error":"No tab named \"Overview\""}""") }
    }

    @Test fun normalizeCatchesTheUsualMixUps() {
        assertEquals("https://script.google.com/macros/s/AKfy/exec", SheetSync.normalize("  script.google.com/macros/s/AKfy/exec \n"))
        assertEquals("http://127.0.0.1:8080/x", SheetSync.normalize("http://127.0.0.1:8080/x"))
        assertFails("/dev") { SheetSync.normalize("https://script.google.com/macros/s/AKfy/dev") }
        assertFails("sheet's own link") { SheetSync.normalize("https://docs.google.com/spreadsheets/d/abc/edit") }
        assertFails("Paste") { SheetSync.normalize(" ") }
        assertFails("not a web link") { SheetSync.normalize("ftp://x") }
    }

    @Test fun keysAreStableAndIgnoreSpacing() {
        val spaced = sand.copy(what = "💵 Awaiting payment  ·  Sample Client  ·  Sand ", where = "🪨 Materials · row 9")
        assertEquals(SheetMerge.keys(listOf(sand)), SheetMerge.keys(listOf(spaced)))
        assertEquals(SheetState(), AppData().sheet)
    }

    private fun assertFails(part: String, block: () -> Unit) {
        try {
            block()
            fail("expected an error mentioning \"$part\"")
        } catch (e: IOException) {
            assertTrue("\"${e.message}\" should mention \"$part\"", e.message!!.contains(part))
        }
    }
}
