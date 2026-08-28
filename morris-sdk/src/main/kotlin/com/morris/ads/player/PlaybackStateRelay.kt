package com.morris.ads.player

import androidx.media3.common.Player

/**
 * Перевод состояний ExoPlayer в три события [VideoPlayer.Listener].
 *
 * Вынесено из [ExoVideoPlayer] отдельным классом, потому что настоящий плеер на
 * JVM не воспроизводит и его слушатель ни разу не срабатывает — а именно здесь
 * живут два инварианта, которые легко нарушить: «готов» и «доиграл» сообщаются
 * по одному разу за показ. ExoPlayer входит в `STATE_READY` не только на
 * старте, но и после каждой паузы и добуферизации, и без защиты показ
 * засчитывался бы заново на каждой заминке связи.
 */
internal class PlaybackStateRelay(
    private val durationMs: () -> Long,
    private val listener: () -> VideoPlayer.Listener?,
) {

    private var reportedReady = false
    private var reportedEnded = false

    fun onState(state: Int) {
        when (state) {
            Player.STATE_READY -> if (!reportedReady) {
                reportedReady = true
                listener()?.onReady(durationMs())
            }
            Player.STATE_ENDED -> if (!reportedEnded) {
                reportedEnded = true
                listener()?.onEnded()
            }
            else -> Unit
        }
    }

    fun onError(name: String) {
        listener()?.onError(name)
    }
}
