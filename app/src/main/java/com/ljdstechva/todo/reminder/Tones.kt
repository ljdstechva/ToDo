package com.ljdstechva.todo.reminder

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.annotation.RawRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.ljdstechva.todo.R
import com.ljdstechva.todo.data.DEFAULT_TONE

data class Tone(
    val id: String,
    val name: String,
    val description: String,
    @param:RawRes val res: Int?,
    /** Calm tones start quiet and get louder over ~30 s. */
    val ramp: Boolean = false,
)

object Tones {
    const val SYSTEM_ID = "system"

    val all = listOf(
        Tone("siren", "Siren", "Very loud, impossible to miss", R.raw.tone_siren),
        Tone("classic", "Classic beeps", "Digital alarm clock", R.raw.tone_classic),
        Tone("phone", "Retro phone", "Old telephone bell", R.raw.tone_phone),
        Tone("chimes", "Calm chimes", "Soft bells that slowly get louder", R.raw.tone_chimes, ramp = true),
        Tone("sunrise", "Gentle sunrise", "Warm, calming swell", R.raw.tone_sunrise, ramp = true),
        Tone(SYSTEM_ID, "Phone's alarm sound", "The alarm tone set on this phone", null),
    )

    fun byId(id: String?): Tone = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT_TONE }

    fun uri(context: Context, tone: Tone): Uri = if (tone.res != null) {
        Uri.parse("android.resource://${context.packageName}/${tone.res}")
    } else {
        RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: Settings.System.DEFAULT_ALARM_ALERT_URI
    }

    val alarmAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
}

/** Plays one tone once (for choosing a tone), on the alarm volume like the real alarm. */
class TonePreview(private val context: Context) {
    private var player: MediaPlayer? = null
    var playingId by mutableStateOf<String?>(null)
        private set

    fun toggle(id: String) = if (playingId == id) stop() else play(id)

    fun play(id: String) {
        stop()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(Tones.alarmAttributes)
            mp.setDataSource(context, Tones.uri(context, Tones.byId(id)))
            mp.setOnCompletionListener { stop() }
            mp.prepare()
            mp.start()
            player = mp
            playingId = id
        } catch (e: Exception) {
            Log.w("TonePreview", "Cannot preview tone $id", e)
            mp.release()
        }
    }

    fun stop() {
        player?.release()
        player = null
        playingId = null
    }
}

@Composable
fun rememberTonePreview(): TonePreview {
    val context = LocalContext.current.applicationContext
    val preview = remember { TonePreview(context) }
    DisposableEffect(Unit) { onDispose { preview.stop() } }
    return preview
}
