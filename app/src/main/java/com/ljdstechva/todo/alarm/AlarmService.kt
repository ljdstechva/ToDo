package com.ljdstechva.todo.alarm

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ljdstechva.todo.data.TodoActions
import com.ljdstechva.todo.reminder.AlertRequest
import com.ljdstechva.todo.reminder.Notifications
import com.ljdstechva.todo.reminder.Tones
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Rings an alarm-style reminder: a foreground service that loops the chosen tone on the alarm
 * volume and vibrates until the user dismisses, snoozes or completes it (or 5 minutes pass, which
 * leaves a "missed alarm" notification). Its notification carries the full-screen intent that
 * opens [AlarmActivity] over the lock screen.
 */
class AlarmService : Service() {

    data class Ringing(val req: AlertRequest, val startedAt: Long)

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var rampJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val timeout = Runnable { onTimeout() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> {
                val req = AlertRequest.from(intent)
                if (req == null) stopEverything() else ring(req)
            }
            ACTION_DISMISS -> stopEverything()
            ACTION_SNOOZE -> afterStop { id -> TodoActions.snooze(applicationContext, id) }
            ACTION_DONE -> afterStop { id -> TodoActions.setDone(applicationContext, id, true) }
            else -> if (_ringing.value == null) stopEverything()
        }
        return START_NOT_STICKY
    }

    private fun ring(req: AlertRequest) {
        // Publish first: the full-screen activity may open as soon as the notification is posted.
        _ringing.value = Ringing(req, System.currentTimeMillis())
        startForegroundCompat(Notifications.alarmNotification(this, req))
        stopSound()
        playSound(req.tone)
        startVibration()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, TIMEOUT_MS)
        Log.i(TAG, "Ringing ${req.id} tone=${req.tone} fgsType=$foregroundType")
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startForeground(Notifications.ALARM_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
                foregroundType = "systemExempted"
                return
            } catch (e: Exception) {
                Log.w(TAG, "systemExempted refused, using mediaPlayback", e)
            }
            startForeground(Notifications.ALARM_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            foregroundType = "mediaPlayback"
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifications.ALARM_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            foregroundType = "mediaPlayback"
        } else {
            startForeground(Notifications.ALARM_ID, n)
            foregroundType = "legacy"
        }
    }

    private fun playSound(toneId: String) {
        val tone = Tones.byId(toneId)
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(Tones.alarmAttributes)
            mp.setDataSource(this, Tones.uri(this, tone))
            mp.isLooping = true
            mp.prepare()
            if (tone.ramp) mp.setVolume(RAMP_START, RAMP_START)
            mp.start()
            player = mp
            isPlayingSound = true
            if (tone.ramp) {
                rampJob = scope.launch {
                    var v = RAMP_START
                    while (v < 1f) {
                        delay(1000)
                        v = (v + 0.03f).coerceAtMost(1f)
                        player?.setVolume(v, v)
                    }
                }
            }
        } catch (e: Exception) {
            mp.release()
            Log.w(TAG, "Cannot play tone ${tone.id}", e)
            if (tone.id != FALLBACK_TONE) playSound(FALLBACK_TONE)
        }
    }

    private fun stopSound() {
        rampJob?.cancel()
        rampJob = null
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        isPlayingSound = false
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }
        if (v == null || !v.hasVibrator()) return
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0), Tones.alarmAttributes)
        vibrator = v
    }

    /** Stops ringing, then runs [action] for the ringing to-do (not for a test alarm). */
    private fun afterStop(action: suspend (Long) -> Unit) {
        val req = _ringing.value?.req
        silence()
        if (req == null || req.isPreview) {
            stopEverything()
            return
        }
        scope.launch {
            try {
                action(req.id)
            } finally {
                stopEverything()
            }
        }
    }

    private fun silence() {
        handler.removeCallbacks(timeout)
        stopSound()
        vibrator?.cancel()
        vibrator = null
        _ringing.value = null
    }

    private fun stopEverything() {
        silence()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onTimeout() {
        val req = _ringing.value?.req
        stopEverything()
        if (req != null && !req.isPreview) Notifications.showMissed(this, req)
    }

    override fun onDestroy() {
        silence()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlarmService"
        const val ACTION_RING = "com.ljdstechva.todo.alarm.RING"
        const val ACTION_DISMISS = "com.ljdstechva.todo.alarm.DISMISS"
        const val ACTION_SNOOZE = "com.ljdstechva.todo.alarm.SNOOZE"
        const val ACTION_DONE = "com.ljdstechva.todo.alarm.DONE"
        const val TIMEOUT_MS = 5 * 60_000L
        private const val RAMP_START = 0.15f
        private const val FALLBACK_TONE = "classic"

        private val _ringing = MutableStateFlow<Ringing?>(null)
        val ringing: StateFlow<Ringing?> = _ringing

        /** True while the tone is playing (read by the live tests). */
        @Volatile
        var isPlayingSound = false
            private set

        /** Foreground-service type the system accepted for the last alarm (diagnostics). */
        @Volatile
        var foregroundType = ""
            private set

        fun start(context: Context, req: AlertRequest) {
            val i = req.putInto(Intent(context, AlarmService::class.java).setAction(ACTION_RING))
            ContextCompat.startForegroundService(context, i)
        }

        fun command(context: Context, action: String) {
            context.startService(Intent(context, AlarmService::class.java).setAction(action))
        }
    }
}
