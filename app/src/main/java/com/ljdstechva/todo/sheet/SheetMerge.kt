package com.ljdstechva.todo.sheet

import com.ljdstechva.todo.data.AppData
import com.ljdstechva.todo.data.Category
import com.ljdstechva.todo.data.SheetLine
import com.ljdstechva.todo.data.SheetResponse
import com.ljdstechva.todo.data.Todo

/**
 * Brings the sheet's to-do lines into the app's data. The sheet only adds lines and settles them
 * (a settled line leaves the list), so:
 *
 * - a new line becomes a to-do under Work, marked as coming from the sheet;
 * - a line that left the sheet ticks its to-do off, and a line that comes back reopens it;
 * - what the user did stays: a to-do ticked off here stays ticked, one deleted here stays away,
 *   edits to its title, note or category are kept.
 *
 * Lines are only ticked off when the app got every line the sheet says it has, so a half-read
 * list never ticks off lines that are still there.
 */
object SheetMerge {

    const val WORK = "Work"
    private const val WORK_COLOR = 5

    class Result(
        val data: AppData,
        val added: List<Todo>,
        val ticked: List<Todo>,
        val reopened: List<Todo>,
        /** False when the sheet said it has more lines than it sent; nothing was ticked off then. */
        val complete: Boolean,
    )

    /**
     * A line's identity across syncs: where (without its row number, which shifts when a line
     * above is settled), date, amount and what (without day counts like "8 days ago", which
     * change by themselves). Two equal lines get #2, #3, ... in sheet order.
     */
    fun keys(lines: List<SheetLine>): List<String> {
        val seen = HashMap<String, Int>()
        return lines.map { line ->
            val base = listOf(stripRow(line.where), clean(line.date), clean(line.amount), steady(line.what)).joinToString("|")
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            if (n == 1) base else "$base#$n"
        }
    }

    fun merge(data: AppData, response: SheetResponse, now: Long): Result {
        val lines = response.items.filter { clean(it.what).isNotEmpty() }
        val keys = keys(lines)
        val onSheet = keys.toSet()
        val complete = response.count == null || lines.size >= response.count

        var nextId = data.nextId
        var categories = data.categories
        // Made only when a new line needs it (the user may have deleted it).
        val work by lazy {
            categories.firstOrNull { it.name.trim().equals(WORK, ignoreCase = true) }
                ?: Category(id = nextId++, name = WORK, color = WORK_COLOR).also { categories = categories + it }
        }

        val synced = data.todos.filter { it.sheetKey != null }.associateBy { it.sheetKey!! }
        val added = mutableListOf<Todo>()
        val ticked = mutableListOf<Todo>()
        val reopened = mutableListOf<Todo>()

        val todos = data.todos.map { t ->
            val key = t.sheetKey ?: return@map t
            val i = keys.indexOf(key)
            when {
                i >= 0 -> {
                    val link = lines[i].link ?: t.sheetLink
                    // Reworded on the sheet ("8 days ago" is now "9 days ago"): follow it, unless
                    // the title was changed here.
                    val text = clean(lines[i].what)
                    val title = if (t.title != text && steady(t.title) == steady(text)) text else t.title
                    if (t.done && t.sheetDone) {
                        t.copy(title = title, done = false, doneAt = null, sheetDone = false, sheetLink = link).also { reopened += it }
                    } else {
                        t.copy(title = title, sheetLink = link)
                    }
                }
                complete && !t.done -> t.copy(done = true, doneAt = now, sheetDone = true, snoozedUntil = null).also { ticked += it }
                else -> t
            }
        }.toMutableList()

        lines.forEachIndexed { i, line ->
            val key = keys[i]
            if (key in synced || key in data.sheet.removed) return@forEachIndexed
            val workId = work.id // first: a new Work category takes the next id before the to-do
            val t = Todo(
                id = nextId++,
                title = clean(line.what),
                note = note(line),
                categoryId = workId,
                // The sheet lists oldest first; the app lists open to-dos by when they were added.
                createdAt = now + i,
                sheetKey = key,
                sheetLink = line.link,
            )
            todos += t
            added += t
        }

        val sheet = data.sheet.copy(
            name = response.sheet.ifBlank { data.sheet.name },
            url = response.url.ifBlank { data.sheet.url },
            lastSync = now,
            lastError = if (complete) null else "Got ${lines.size} of the ${response.count} lines the sheet lists, so none were ticked off",
            openLines = lines.size,
            // A deleted line that has left the sheet needs remembering no more (if the list is whole).
            removed = if (complete) data.sheet.removed.filterTo(HashSet()) { it in onSheet } else data.sheet.removed,
        )
        return Result(
            data = data.copy(todos = todos, categories = categories, nextId = nextId, sheet = sheet),
            added = added,
            ticked = ticked,
            reopened = reopened,
            complete = complete,
        )
    }

    /** "₱1,000.00 · Oct 1, 2026 · 🪨 Materials": what the line says besides its title. */
    fun note(line: SheetLine): String =
        listOf(line.amount, line.date, stripRow(line.where)).map { clean(it) }.filter { it.isNotEmpty() }.joinToString(" · ")

    private fun stripRow(where: String) = clean(where).replace(Regex("""\s*·\s*row\s*\d+\s*$""", RegexOption.IGNORE_CASE), "")

    private fun clean(s: String) = s.replace(Regex("""\s+"""), " ").trim()

    private val dayCount = Regex(
        """\b\d+\s*(?:days?|weeks?|months?|hours?|hrs?|minutes?|mins?)\b|\b(?:today|yesterday|tomorrow)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** The text without the parts that change by themselves from day to day. */
    private fun steady(what: String) = clean(what).replace(dayCount, "#")
}
