package com.ljdstechva.todo.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * The whole app state in one small JSON file (files/todo-data.json), held in memory as a
 * [StateFlow]. Writes go through [update], one at a time, and are atomic on disk.
 */
object Repo {
    private const val TAG = "TodoRepo"
    private const val FILE = "todo-data.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val mutex = Mutex()
    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> get() = _data

    private lateinit var file: AtomicFile

    @Volatile
    private var ready = false

    fun init(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            file = AtomicFile(File(context.filesDir, FILE))
            _data.value = load(context) ?: seed().also { write(it) }
            ready = true
        }
    }

    fun todo(id: Long): Todo? = _data.value.todos.firstOrNull { it.id == id }

    val settings: AppSettings get() = _data.value.settings

    suspend fun update(transform: (AppData) -> AppData): AppData = mutex.withLock {
        val before = _data.value
        val after = transform(before)
        if (after != before) {
            _data.value = after
            withContext(Dispatchers.IO) { write(after) }
        }
        after
    }

    suspend fun updateTodo(id: Long, transform: (Todo) -> Todo): Todo? {
        val d = update { data -> data.copy(todos = data.todos.map { if (it.id == id) transform(it) else it }) }
        return d.todos.firstOrNull { it.id == id }
    }

    private fun load(context: Context): AppData? {
        if (!file.baseFile.exists()) return null
        return try {
            json.decodeFromString(AppData.serializer(), file.readFully().decodeToString())
        } catch (e: Exception) {
            // Keep the unreadable file for inspection instead of silently overwriting it.
            val bad = File(context.filesDir, "todo-data.unreadable-${System.currentTimeMillis()}.json")
            file.baseFile.copyTo(bad, overwrite = true)
            Log.e(TAG, "Could not read $FILE, saved a copy as ${bad.name} and started fresh", e)
            null
        }
    }

    private fun write(data: AppData) {
        val out = file.startWrite()
        try {
            out.write(json.encodeToString(AppData.serializer(), data).toByteArray())
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            Log.e(TAG, "Could not save $FILE", e)
            throw e
        }
    }

    private fun seed() = AppData(
        categories = listOf(
            Category(id = 1, name = "Personal", color = 6),
            Category(id = 2, name = "Work", color = 5),
            Category(id = 3, name = "Errands", color = 3),
        ),
        nextId = 4,
    )
}
