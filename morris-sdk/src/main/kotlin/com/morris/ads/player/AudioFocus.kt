package com.morris.ads.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Аудиофокус.
 *
 * Без него реклама продолжает говорить поверх входящего звонка и поверх музыки,
 * которую включил пользователь. Система сообщает о смене фокуса, но зовёт
 * слушателя с произвольного потока — поэтому вызов перекладывается на главный
 * до того, как его увидит показ.
 */
internal class AudioFocus(
    context: Context,
    private val onChange: (Int) -> Unit,
) {

    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val main = Handler(Looper.getMainLooper())

    private val listener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onChange(focusChange)
        } else {
            main.post { onChange(focusChange) }
        }
    }

    private var request: AudioFocusRequest? = null

    /**
     * Запросить фокус.
     *
     * @return дали ли. Отказ не повод не показывать рекламу: мы всё равно
     *   играем, просто без права на чужой звук.
     */
    @Suppress("DEPRECATION")
    fun request(): Boolean {
        val am = manager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setOnAudioFocusChangeListener(listener)
                .build()
            request = req
            am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            am.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    /** Отпустить. Не отпустить — значит оставить чужую музыку приглушённой. */
    @Suppress("DEPRECATION")
    fun abandon() {
        val am = manager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request?.let { am.abandonAudioFocusRequest(it) }
            request = null
        } else {
            am.abandonAudioFocus(listener)
        }
    }
}
