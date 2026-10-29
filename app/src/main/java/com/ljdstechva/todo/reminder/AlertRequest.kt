package com.ljdstechva.todo.reminder

import android.content.Intent
import com.ljdstechva.todo.data.AlertStyle

/** What to show or ring for one reminder. [id] is the to-do id, or [PREVIEW_ID] for a test. */
data class AlertRequest(
    val id: Long,
    val title: String,
    val text: String,
    val style: AlertStyle,
    val tone: String,
) {
    val isPreview: Boolean get() = id == PREVIEW_ID

    fun putInto(intent: Intent): Intent = intent
        .putExtra(EXTRA_ID, id)
        .putExtra(EXTRA_TITLE, title)
        .putExtra(EXTRA_TEXT, text)
        .putExtra(EXTRA_STYLE, style.name)
        .putExtra(EXTRA_TONE, tone)

    companion object {
        const val PREVIEW_ID = -1L
        const val EXTRA_ID = "todo_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_STYLE = "style"
        private const val EXTRA_TONE = "tone"

        fun from(intent: Intent): AlertRequest? {
            if (!intent.hasExtra(EXTRA_TITLE)) return null
            return AlertRequest(
                id = intent.getLongExtra(EXTRA_ID, PREVIEW_ID),
                title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                text = intent.getStringExtra(EXTRA_TEXT).orEmpty(),
                style = runCatching { AlertStyle.valueOf(intent.getStringExtra(EXTRA_STYLE).orEmpty()) }
                    .getOrDefault(AlertStyle.NOTIFICATION),
                tone = intent.getStringExtra(EXTRA_TONE).orEmpty(),
            )
        }
    }
}
