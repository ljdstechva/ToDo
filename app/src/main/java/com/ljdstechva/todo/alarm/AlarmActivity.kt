package com.ljdstechva.todo.alarm

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ljdstechva.todo.data.Repo
import com.ljdstechva.todo.reminder.AlertRequest
import com.ljdstechva.todo.ui.Fmt
import com.ljdstechva.todo.ui.theme.TodoTheme
import kotlinx.coroutines.delay

/** Full-screen alarm, shown over the lock screen while [AlarmService] rings. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            TodoTheme(dark = true) {
                val ringing by AlarmService.ringing.collectAsStateWithLifecycle()
                val current = ringing
                var seen by remember { mutableStateOf(false) }
                LaunchedEffect(current) {
                    if (current != null) {
                        seen = true
                    } else {
                        // Opened before the service published its alarm? Give it a moment first.
                        if (!seen) delay(1500)
                        if (AlarmService.ringing.value == null) finish()
                    }
                }
                if (current != null) {
                    AlarmScreen(
                        req = current.req,
                        snoozeMinutes = Repo.settings.snoozeMinutes,
                        onDismiss = { AlarmService.command(this, AlarmService.ACTION_DISMISS) },
                        onSnooze = { AlarmService.command(this, AlarmService.ACTION_SNOOZE) },
                        onDone = { AlarmService.command(this, AlarmService.ACTION_DONE) },
                    )
                }
            }
        }
    }
}

@Composable
fun AlarmScreen(req: AlertRequest, snoozeMinutes: Int, onDismiss: () -> Unit, onSnooze: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ringScale by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "ringScale",
    )
    val ringAlpha by pulse.animateFloat(
        initialValue = 0.45f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "ringAlpha",
    )
    val shake by pulse.animateFloat(
        initialValue = 0f, targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 1400
                0f at 0
                -14f at 70
                14f at 140
                -14f at 210
                14f at 280
                -8f at 350
                0f at 420
            },
        ),
        label = "shake",
    )
    var clock by remember { mutableStateOf(Fmt.time(context, System.currentTimeMillis())) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = Fmt.time(context, System.currentTimeMillis())
            delay(1000)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1E1B4B), Color(0xFF312E81), Color(0xFF5B21B6)))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Text(clock, color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(36.dp))
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(132.dp)
                        .graphicsLayer {
                            scaleX = ringScale
                            scaleY = ringScale
                            alpha = ringAlpha
                        }
                        .background(Color.White, CircleShape),
                )
                Box(Modifier.size(132.dp).background(Color.White.copy(alpha = 0.14f), CircleShape))
                Icon(
                    Icons.Rounded.Alarm, contentDescription = null, tint = Color.White,
                    modifier = Modifier.size(64.dp).graphicsLayer { rotationZ = shake },
                )
            }
            Spacer(Modifier.height(36.dp))
            Text(
                req.title, color = Color.White, style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
            if (req.text.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    req.text, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.weight(1.2f))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF312E81)),
            ) {
                Text("Dismiss", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onSnooze, modifier = Modifier.weight(1f).height(56.dp)) {
                    Icon(Icons.Rounded.Snooze, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Snooze $snoozeMinutes min")
                }
                if (!req.isPreview) {
                    FilledTonalButton(onClick = onDone, modifier = Modifier.weight(1f).height(56.dp)) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Mark done")
                    }
                }
            }
        }
    }
}
