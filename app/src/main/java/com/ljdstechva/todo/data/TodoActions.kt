package com.ljdstechva.todo.data

import android.content.Context
import com.ljdstechva.todo.reminder.Notifications
import com.ljdstechva.todo.reminder.ReminderScheduler
import com.ljdstechva.todo.update.UpdateWorker

/**
 * Every change to the to-do list goes through here so the saved data and the alarms in
 * AlarmManager never disagree.
 *
 * Repeating to-dos: ticking one off marks it done until its next reminder, which reopens it and
 * reminds again. One-time to-dos: ticking one off cancels its reminder.
 */
object TodoActions {

    private fun now() = System.currentTimeMillis()

    suspend fun add(context: Context, title: String, note: String, categoryId: Long?, reminder: Reminder?): Todo {
        var created: Todo? = null
        Repo.update { d ->
            val t = Todo(id = d.nextId, title = title, note = note, categoryId = categoryId, createdAt = now(), reminder = reminder)
            created = t
            d.copy(todos = d.todos + t, nextId = d.nextId + 1)
        }
        return reschedule(context, created!!.id) ?: created!!
    }

    /** Saves an edited to-do. A changed reminder drops any pending snooze. */
    suspend fun save(context: Context, todo: Todo): Todo? {
        val old = Repo.todo(todo.id)
        val reminderChanged = old?.reminder != todo.reminder
        Repo.update { d -> d.copy(todos = d.todos.map { if (it.id == todo.id) todo.copy(snoozedUntil = if (reminderChanged) null else it.snoozedUntil) else it }) }
        if (reminderChanged) ReminderScheduler.cancel(context, todo.id, ReminderScheduler.KIND_SNOOZE)
        return reschedule(context, todo.id)
    }

    suspend fun setDone(context: Context, id: Long, done: Boolean): Todo? {
        Repo.updateTodo(id) { it.copy(done = done, doneAt = if (done) now() else null, snoozedUntil = if (done) null else it.snoozedUntil) }
        if (done) {
            ReminderScheduler.cancel(context, id, ReminderScheduler.KIND_SNOOZE)
            Notifications.cancel(context, id)
        }
        return reschedule(context, id)
    }

    suspend fun delete(context: Context, id: Long): Todo? {
        val t = Repo.todo(id) ?: return null
        Repo.update { d -> d.copy(todos = d.todos.filterNot { it.id == id }) }
        ReminderScheduler.cancelAll(context, id)
        Notifications.cancel(context, id)
        return t
    }

    /** Deletes every to-do matching [predicate] (e.g. "clear completed"); returns how many. */
    suspend fun deleteWhere(context: Context, predicate: (Todo) -> Boolean): List<Todo> {
        val gone = Repo.data.value.todos.filter(predicate)
        if (gone.isEmpty()) return gone
        val ids = gone.map { it.id }.toSet()
        Repo.update { d -> d.copy(todos = d.todos.filterNot { it.id in ids }) }
        gone.forEach {
            ReminderScheduler.cancelAll(context, it.id)
            Notifications.cancel(context, it.id)
        }
        return gone
    }

    /** Puts back deleted to-dos (undo). */
    suspend fun restore(context: Context, todos: List<Todo>) {
        if (todos.isEmpty()) return
        Repo.update { d ->
            val present = d.todos.map { it.id }.toSet()
            d.copy(todos = d.todos + todos.filterNot { it.id in present })
        }
        todos.forEach { reschedule(context, it.id) }
    }

    suspend fun snooze(context: Context, id: Long): Todo? {
        val t = Repo.todo(id) ?: return null
        val at = now() + Repo.settings.snoozeMinutes * 60_000L
        // Arm first: once the to-do shows as snoozed, its snooze alarm must already exist.
        ReminderScheduler.setAlarm(context, id, t.reminder?.style ?: AlertStyle.NOTIFICATION, at, ReminderScheduler.KIND_SNOOZE)
        Repo.updateTodo(id) { it.copy(snoozedUntil = at) }
        Notifications.cancel(context, id)
        return Repo.todo(id)
    }

    /**
     * A reminder alarm went off. Moves the schedule on (a repeating to-do reopens) and returns the
     * to-do to alert about, or null when there is nothing to show.
     */
    suspend fun onFired(context: Context, id: Long, fromSnooze: Boolean): Todo? {
        val t = Repo.todo(id) ?: return null
        val r = t.reminder ?: return null
        if (fromSnooze) {
            if (t.done) return null
            return Repo.updateTodo(id) { it.copy(snoozedUntil = null) }
        }
        if (t.done && r.repeat == Repeat.ONCE) return null
        Repo.updateTodo(id) { it.copy(done = false, doneAt = null, snoozedUntil = null) }
        ReminderScheduler.cancel(context, id, ReminderScheduler.KIND_SNOOZE)
        return reschedule(context, id)
    }

    /** Re-arms the alarm of one to-do from the saved data and stores its next time. */
    suspend fun reschedule(context: Context, id: Long): Todo? {
        val d = Repo.data.value
        val t = d.todos.firstOrNull { it.id == id } ?: return null
        val next = ReminderScheduler.apply(context, t, d.settings)
        return if (next != t.nextAt) Repo.updateTodo(id) { it.copy(nextAt = next) } else t
    }

    /** After a reboot, time change, app update or app start: re-arm every alarm. */
    suspend fun rescheduleAll(context: Context) {
        val now = now()
        for (t in Repo.data.value.todos) {
            reschedule(context, t.id)
            val snoozed = t.snoozedUntil
            if (snoozed != null) {
                if (snoozed > now && !t.done) {
                    ReminderScheduler.setAlarm(context, t.id, t.reminder?.style ?: AlertStyle.NOTIFICATION, snoozed, ReminderScheduler.KIND_SNOOZE)
                } else {
                    Repo.updateTodo(t.id) { it.copy(snoozedUntil = null) }
                }
            }
        }
    }

    suspend fun addCategory(name: String, color: Int): Category {
        var created: Category? = null
        Repo.update { d ->
            val c = Category(id = d.nextId, name = name, color = color)
            created = c
            d.copy(categories = d.categories + c, nextId = d.nextId + 1)
        }
        return created!!
    }

    suspend fun updateCategory(category: Category) {
        Repo.update { d -> d.copy(categories = d.categories.map { if (it.id == category.id) category else it }) }
    }

    /** Removes a category; its to-dos stay, without a category. */
    suspend fun deleteCategory(id: Long) {
        Repo.update { d ->
            d.copy(
                categories = d.categories.filterNot { it.id == id },
                todos = d.todos.map { if (it.categoryId == id) it.copy(categoryId = null) else it },
            )
        }
    }

    suspend fun updateSettings(context: Context, transform: (AppSettings) -> AppSettings) {
        val before = Repo.settings
        val after = Repo.update { d -> d.copy(settings = transform(d.settings)) }.settings
        if (before.quietEnabled != after.quietEnabled || before.quietStartMinute != after.quietStartMinute ||
            before.quietEndMinute != after.quietEndMinute
        ) {
            rescheduleAll(context)
        }
        if (before.autoUpdate != after.autoUpdate) UpdateWorker.schedule(context, after.autoUpdate)
    }
}
