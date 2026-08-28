package com.morris.ads.player

import android.view.View

/**
 * Плеер, каким его видит экран показа.
 *
 * Прослойка появилась не «на будущее», а по замеру: настоящий ExoPlayer на JVM
 * создаётся и принимает команды, но не воспроизводит — застревает в состоянии
 * буферизации, потому что декодеров вне устройства нет. Без этого интерфейса
 * весь показ (отсчёт, гейт пропуска, четверти, награда) нельзя было бы
 * проверить ни одним тестом до выхода на живой телефон.
 */
public interface VideoPlayer {

    public interface Listener {
        /** Файл готов, длительность известна. */
        public fun onReady(durationMs: Long)

        /** Досмотрено до конца. */
        public fun onEnded()

        /** Воспроизвести не удалось. Показ на этом заканчивается. */
        public fun onError(message: String)
    }

    /** Поверхность с картинкой. Экран кладёт её под оверлей. */
    public val view: View

    public var listener: Listener?

    public val positionMs: Long

    public val durationMs: Long

    public fun prepare(url: String)

    public fun play()

    public fun pause()

    /**
     * Громкость от 0 до 1.
     *
     * Не булев «выключен/включён»: система умеет просить не замолчать, а
     * приглушиться — например, когда навигатор объявляет поворот.
     */
    public fun setVolume(volume: Float)

    /**
     * Показывать ли собственное управление плеера (перемотку и прочее).
     *
     * Решает не SDK, а ответ бэкенда: у части рекламодателей перемотка внутри
     * ролика запрещена условиями размещения.
     */
    public fun setControlsEnabled(enabled: Boolean)

    public fun release()
}
