package com.morris.ads.tracking

import com.morris.ads.model.AdResponse
import com.morris.ads.model.TrackingEvent
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Трекинг одного показа.
 *
 * Держит два инварианта, которые по отдельности легко нарушить:
 *
 *  - **каждое событие ровно один раз за показ.** Плеер присылает `onProgress`
 *    десятки раз в секунду, а перемотка возвращает позицию назад — без
 *    защиты четверти уходили бы пачками.
 *  - **отправка не на главном потоке.** Пиксель — это сетевой запрос, и висеть
 *    на нём во время воспроизведения нельзя.
 */
public class TrackingSession(
    private val ad: AdResponse,
    private val firer: PixelFirer,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "morris-tracking").apply { isDaemon = true }
    },
) {

    private val fired = Collections.synchronizedSet(LinkedHashSet<TrackingEvent>())
    private val quartiles = QuartileTracker(ad.durationMs)

    /**
     * Отправить событие. Повторный вызов ничего не делает.
     *
     * @return true, если событие ушло именно сейчас.
     */
    public fun fire(event: TrackingEvent): Boolean {
        if (!fired.add(event)) return false
        val urls = ad.tracking.urlsFor(event)
        if (urls.isEmpty()) return true   // событие засчитано, слать некуда
        executor.execute { firer.fireAll(urls) }
        return true
    }

    /**
     * Отправить событие, которое законно повторяется за один показ: пауза,
     * возобновление, звук. Дедупликация [fire] здесь была бы неправдой —
     * пользователь может свернуть приложение трижды, и это три паузы.
     */
    public fun fireRepeatable(event: TrackingEvent): Boolean {
        fired.add(event)
        val urls = ad.tracking.urlsFor(event)
        if (urls.isEmpty()) return true
        executor.execute { firer.fireAll(urls) }
        return true
    }

    /** Позиция плеера сдвинулась. Отправит те четверти, что наступили. */
    public fun onProgress(positionMs: Long) {
        for (e in quartiles.onProgress(positionMs)) fire(e)
    }

    /** Ролик доигран. Досылает всё, что не успело уйти по позиции. */
    public fun onEnded() {
        for (e in quartiles.onEnded()) fire(e)
    }

    public fun wasFired(event: TrackingEvent): Boolean = event in fired

    public fun firedEvents(): Set<TrackingEvent> = synchronized(fired) { LinkedHashSet(fired) }

    /**
     * Показ окончен. Дожидается отправки того, что уже поставлено в очередь:
     * иначе закрытие экрана обрывало бы последние пиксели, в том числе
     * `complete` — самый важный из них.
     */
    public fun shutdown(waitMs: Long = 3_000) {
        executor.shutdown()
        runCatching {
            executor.awaitTermination(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }
}
