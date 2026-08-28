package com.morris.ads.tracking

import com.morris.ads.model.TrackingEvent

/**
 * Превращает позицию плеера в события просмотра.
 *
 * Отдельный класс с чистой логикой, потому что здесь живут все классические
 * ошибки трекинга: событие уходит дважды, уходит по таймеру вместо реального
 * воспроизведения, теряется при перемотке назад, `complete` не приходит, если
 * последний тик пришёл на 99%.
 *
 * Правила:
 *  - событие уходит РОВНО ОДИН РАЗ за показ;
 *  - порог считается от фактической позиции, а не от прошедшего времени —
 *    пауза и буферизация не должны двигать квартили;
 *  - перемотка назад ничего не отменяет: уже засчитанное остаётся засчитанным;
 *  - перепрыгнули через порог — событие всё равно уходит, иначе на медленном
 *    устройстве, где тики редкие, четверти терялись бы.
 */
public class QuartileTracker(private val durationMs: Long) {

    private val fired = LinkedHashSet<TrackingEvent>()

    /**
     * @return события, которые надо отправить именно сейчас, в порядке шкалы.
     */
    public fun onProgress(positionMs: Long): List<TrackingEvent> {
        if (durationMs <= 0L) return emptyList()

        val ratio = positionMs.toDouble() / durationMs
        val due = ArrayList<TrackingEvent>(2)

        for ((threshold, event) in THRESHOLDS) {
            if (ratio >= threshold && fired.add(event)) due += event
        }
        return due
    }

    /**
     * Плеер сообщил, что ролик кончился.
     *
     * Отдельно от [onProgress], потому что последний тик редко приходит ровно
     * на 100%: обычно это 98–99%, и по позиции `complete` не наступил бы
     * никогда.
     */
    public fun onEnded(): List<TrackingEvent> {
        val due = ArrayList<TrackingEvent>(4)
        for ((_, event) in THRESHOLDS) {
            if (fired.add(event)) due += event
        }
        return due
    }

    public fun alreadyFired(event: TrackingEvent): Boolean = event in fired

    public fun firedSoFar(): Set<TrackingEvent> = LinkedHashSet(fired)

    private companion object {
        /** Порядок важен: события уходят по возрастанию шкалы. */
        val THRESHOLDS = listOf(
            0.0 to TrackingEvent.START,
            0.25 to TrackingEvent.FIRST_QUARTILE,
            0.50 to TrackingEvent.MIDPOINT,
            0.75 to TrackingEvent.THIRD_QUARTILE,
            1.0 to TrackingEvent.COMPLETE,
        )
    }
}
