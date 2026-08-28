package com.morris.ads.player

import android.content.Context
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Настоящий плеер поверх media3.
 *
 * Здесь намеренно нет логики показа — только команды плееру. Решения о том,
 * что считать началом и концом просмотра, приняты в [PlaybackStateRelay],
 * который проверяется тестами; всё остальное решается в
 * [com.morris.ads.MorrisAdActivity].
 */
internal class ExoVideoPlayer(context: Context) : VideoPlayer {

    private val exo = ExoPlayer.Builder(context).build()

    private val playerView = PlayerView(context).apply {
        // По умолчанию управления нет: оверлей рисуем мы сами. Ответ бэкенда
        // может это переопределить — см. setControlsEnabled.
        useController = false
        setPlayer(exo)
    }

    override val view: View get() = playerView

    override var listener: VideoPlayer.Listener? = null

    override val positionMs: Long get() = exo.currentPosition.coerceAtLeast(0)

    /** До готовности media3 отдаёт `TIME_UNSET`; наружу это уходит нулём. */
    override val durationMs: Long
        get() = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L

    private val relay = PlaybackStateRelay(
        durationMs = { durationMs },
        listener = { listener },
    )

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) = relay.onState(state)
            override fun onPlayerError(error: PlaybackException) = relay.onError(error.errorCodeName)
        })
    }

    override fun prepare(url: String) {
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
    }

    override fun play() { exo.playWhenReady = true }

    override fun pause() { exo.playWhenReady = false }

    // Зажим на нашей границе. media3 по проверке зажимает и сам, так что это
    // не защита от него, а гарантия контракта [VideoPlayer] для любой другой
    // реализации, которую сюда однажды подставят.
    override fun setVolume(volume: Float) { exo.volume = volume.coerceIn(0f, 1f) }

    override fun setControlsEnabled(enabled: Boolean) { playerView.useController = enabled }

    override fun release() {
        playerView.setPlayer(null)
        exo.release()
    }
}
