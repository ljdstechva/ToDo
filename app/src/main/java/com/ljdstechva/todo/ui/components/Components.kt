package com.ljdstechva.todo.ui.components

import android.text.format.DateFormat
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ljdstechva.todo.data.Category
import com.ljdstechva.todo.ui.theme.CategoryColors
import com.ljdstechva.todo.ui.theme.categoryColor

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 4.dp, bottom = 6.dp),
    )
}

@Composable
fun ColorDot(colorIndex: Int, size: Dp = 10.dp) {
    Box(Modifier.size(size).background(categoryColor(colorIndex), CircleShape))
}

/** Round check box: fills with [color] and draws the tick with a springy pop. */
@Composable
fun AnimatedCheck(checked: Boolean, color: Color, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val progress by animateFloatAsState(if (checked) 1f else 0f, tween(260), label = "tick")
    val scale by animateFloatAsState(
        if (checked) 1f else 0.92f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pop",
    )
    val fill by animateColorAsState(if (checked) color else Color.Transparent, tween(220), label = "fill")
    val outline = if (checked) color else MaterialTheme.colorScheme.outline
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Checkbox) {
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                onToggle()
            }
            .semantics { contentDescription = if (checked) "Mark as not done" else "Mark as done" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(26.dp).scale(scale)) {
            val stroke = 2.dp.toPx()
            drawCircle(fill)
            drawCircle(outline, radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
            if (progress > 0f) {
                val tick = Path().apply {
                    moveTo(size.width * 0.28f, size.height * 0.52f)
                    lineTo(size.width * 0.44f, size.height * 0.68f)
                    lineTo(size.width * 0.74f, size.height * 0.36f)
                }
                val measure = PathMeasure()
                measure.setPath(tick, false)
                val partial = Path()
                measure.getSegment(0f, measure.length * progress, partial, true)
                drawPath(partial, Color.White, style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** Text that gets a line drawn through it, line by line, as [struck] turns on. */
@Composable
fun StrikeText(text: String, struck: Boolean, style: TextStyle, color: Color, modifier: Modifier = Modifier, maxLines: Int = 3) {
    val progress by animateFloatAsState(if (struck) 1f else 0f, tween(if (struck) 380 else 200), label = "strike")
    val textColor by animateColorAsState(if (struck) color.copy(alpha = 0.5f) else color, tween(300), label = "strikeColor")
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text,
        style = style,
        color = textColor,
        maxLines = maxLines,
        onTextLayout = { layout = it },
        modifier = modifier.drawWithContent {
            drawContent()
            val l = layout ?: return@drawWithContent
            if (progress <= 0f) return@drawWithContent
            val lines = l.lineCount
            for (i in 0 until lines) {
                val part = (progress * lines - i).coerceIn(0f, 1f)
                if (part <= 0f) break
                val left = l.getLineLeft(i)
                val right = l.getLineRight(i)
                val y = (l.getLineTop(i) + l.getLineBottom(i)) / 2f
                drawLine(textColor, Offset(left, y), Offset(left + (right - left) * part, y), 1.6.dp.toPx(), StrokeCap.Round)
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryDialog(
    initial: Category?,
    onDismiss: () -> Unit,
    onSave: (name: String, color: Int) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var color by remember { mutableIntStateOf(initial?.color ?: 0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New category" else "Edit category") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("categoryName"),
                )
                Spacer(Modifier.height(16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CategoryColors.forEachIndexed { i, c ->
                        val selected = i == color
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(c)
                                .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { color = i },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (onDelete != null) {
                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete category") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), color) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(title: String, hour: Int, minute: Int, onDismiss: () -> Unit, onConfirm: (hour: Int, minute: Int) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = DateFormat.is24HourFormat(context))
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp))
                TimePicker(state = state)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("OK") }
                }
            }
        }
    }
}
