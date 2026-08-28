package com.morris.ads

import com.morris.ads.model.TrackingEvent
import com.morris.ads.tracking.QuartileTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Здесь живут классические ошибки трекинга, поэтому проверяется каждая:
 * дубли, потери на редких тиках, перемотка, отсутствие `complete`.
 */
class QuartileTrackerTest {

    private val duration = 6_000L

    @Test
    fun `события идут по возрастанию шкалы`() {
        val t = QuartileTracker(duration)
        assertEquals(listOf(TrackingEvent.START), t.onProgress(0))
        assertEquals(listOf(TrackingEvent.FIRST_QUARTILE), t.onProgress(1_500))
        assertEquals(listOf(TrackingEvent.MIDPOINT), t.onProgress(3_000))
        assertEquals(listOf(TrackingEvent.THIRD_QUARTILE), t.onProgress(4_500))
        assertEquals(listOf(TrackingEvent.COMPLETE), t.onProgress(6_000))
    }

    @Test
    fun `частые тики не порождают дублей`() {
        val t = QuartileTracker(duration)
        t.onProgress(0)
        // Плеер зовёт onProgress десятки раз в секунду.
        val extra = (0..200).flatMap { t.onProgress(1_600 + it.toLong()) }
        assertEquals("после первой четверти ничего лишнего", listOf(TrackingEvent.FIRST_QUARTILE), extra)
    }

    @Test
    fun `перемотка назад ничего не отменяет и не повторяет`() {
        val t = QuartileTracker(duration)
        t.onProgress(0)
        t.onProgress(3_100)                      // start, q1, midpoint
        val back = t.onProgress(500)             // пользователь отмотал назад
        assertTrue("перемотка назад не должна слать события: $back", back.isEmpty())
        assertTrue(t.alreadyFired(TrackingEvent.MIDPOINT))
    }

    @Test
    fun `редкие тики не теряют четверти`() {
        // На слабом устройстве тик может прийти раз в две секунды и
        // перепрыгнуть через порог. Пропускать событие нельзя.
        val t = QuartileTracker(duration)
        val due = t.onProgress(3_100)
        assertEquals(
            listOf(TrackingEvent.START, TrackingEvent.FIRST_QUARTILE, TrackingEvent.MIDPOINT),
            due,
        )
    }

    @Test
    fun `complete приходит по сигналу плеера, даже если позиция не дошла до конца`() {
        // Последний тик почти никогда не бывает ровно на 100%.
        val t = QuartileTracker(duration)
        t.onProgress(5_900)                      // 98% — complete по позиции не наступил
        assertTrue(!t.alreadyFired(TrackingEvent.COMPLETE))
        assertEquals(listOf(TrackingEvent.COMPLETE), t.onEnded())
    }

    @Test
    fun `onEnded досылает всё пропущенное разом`() {
        // Ролик доигран, а тиков не было вовсе — так бывает при очень коротком
        // видео. Уходит вся шкала, в правильном порядке.
        val t = QuartileTracker(duration)
        assertEquals(
            listOf(
                TrackingEvent.START, TrackingEvent.FIRST_QUARTILE, TrackingEvent.MIDPOINT,
                TrackingEvent.THIRD_QUARTILE, TrackingEvent.COMPLETE,
            ),
            t.onEnded(),
        )
    }

    @Test
    fun `повторный onEnded молчит`() {
        val t = QuartileTracker(duration)
        t.onEnded()
        assertTrue(t.onEnded().isEmpty())
    }

    @Test
    fun `нулевая длительность не порождает событий и не делит на ноль`() {
        val t = QuartileTracker(0)
        assertTrue(t.onProgress(1_000).isEmpty())
    }

    @Test
    fun `позиция за пределами длительности не ломает порядок`() {
        val t = QuartileTracker(duration)
        val due = t.onProgress(99_999)
        assertEquals(
            listOf(
                TrackingEvent.START, TrackingEvent.FIRST_QUARTILE, TrackingEvent.MIDPOINT,
                TrackingEvent.THIRD_QUARTILE, TrackingEvent.COMPLETE,
            ),
            due,
        )
    }
}
