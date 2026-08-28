package com.morris.ads

import android.content.Context
import android.view.View
import com.morris.ads.player.VideoPlayer

/**
 * Плеер, которым управляет тест.
 *
 * Нужен по замеру, а не для удобства: настоящий ExoPlayer на JVM создаётся и
 * принимает команды, но остаётся в состоянии буферизации и позицию не двигает —
 * декодеров вне устройства нет. Без подмены отсчёт, момент появления пропуска,
 * четверти и награда впервые проверялись бы на живом телефоне.
 */
class FakeVideoPlayer(context: Context) : VideoPlayer {

    override val view: View = View(context)
    override var listener: VideoPlayer.Listener? = null

    var position: Long = 0
    override val positionMs: Long get() = position

    override var durationMs: Long = 15_000

    var playing: Boolean = false; private set
    var released: Boolean = false; private set
    var preparedUrl: String? = null; private set
    var volume: Float = 1f; private set
    var controlsEnabled: Boolean? = null; private set

    override fun prepare(url: String) { preparedUrl = url }
    override fun play() { playing = true }
    override fun pause() { playing = false }
    override fun setVolume(volume: Float) { this.volume = volume }
    override fun setControlsEnabled(enabled: Boolean) { this.controlsEnabled = enabled }
    override fun release() { released = true; playing = false }

    // --- то, чем управляет тест -------------------------------------------

    /** Файл готов — плеер сообщает об этом экрану. */
    fun becomeReady() { listener?.onReady(durationMs) }

    /** Ролик доигран до конца. */
    fun playOut() { position = durationMs; listener?.onEnded() }

    fun fail(message: String = "DECODER_ERROR") { listener?.onError(message) }
}
