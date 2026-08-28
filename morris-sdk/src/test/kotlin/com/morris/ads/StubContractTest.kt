package com.morris.ads

import com.morris.ads.media.Connection
import com.morris.ads.media.MediaSelector
import com.morris.ads.model.TrackingEvent
import com.morris.ads.net.AdResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверка контракта против того, что реально отдаёт заглушка.
 *
 * Фикстуры сняты с работающего `tools/stub-backend`, а не написаны руками.
 * Смысл теста в том, чтобы расхождение между заглушкой и парсером всплывало
 * здесь, а не на устройстве: контракт — единственное, что связывает две
 * команды, и молча разъезжаться он не должен.
 *
 * Пересобрать фикстуры:
 * ```
 * go run ./tools/stub-backend -addr :18081 &
 * curl -s -XPOST localhost:18081/v1/ad -d '{}' > morris-sdk/src/test/resources/stub-video.json
 * ```
 */
class StubContractTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)) {
            "нет фикстуры $name — пересними её с заглушки"
        }.bufferedReader().use { it.readText() }

    @Test
    fun `ответ заглушки разбирается целиком`() {
        val ad = AdResponseParser.parse(fixture("stub-video.json"))

        assertEquals("stub-0001", ad.adId)
        assertEquals(6000L, ad.durationMs)
        assertEquals(4, ad.media.size)
        assertEquals("В магазин", ad.click.label)
        assertEquals("2VtzqxAjfmM", ad.branding.erid)
        assertNotNull(ad.branding.adChoices)
        assertEquals(1800000L, ad.ttlMs)
    }

    @Test
    fun `в обычном сценарии пропуск запрещён`() {
        assertNull(AdResponseParser.parse(fixture("stub-video.json")).skipAfterMs)
    }

    @Test
    fun `сценарий skippable даёт время до кнопки`() {
        assertEquals(5000L, AdResponseParser.parse(fixture("stub-skippable.json")).skipAfterMs)
    }

    @Test
    fun `сценарий interstitial приходит без награды`() {
        assertNull(AdResponseParser.parse(fixture("stub-interstitial.json")).reward)
    }

    @Test
    fun `сценарий nofill — это отсутствие рекламы, а не ошибка`() {
        assertNull(AdResponseParser.parseOrNoFill(fixture("stub-nofill.json")))
    }

    @Test
    fun `все события, которые шлёт заглушка, известны SDK`() {
        // Если заглушка начнёт присылать событие, которого нет в TrackingEvent,
        // мы узнаем об этом здесь, а не по молчащему пикселю в проде.
        val known = TrackingEvent.entries.map { it.key }.toSet()
        val fromStub = AdResponseParser.parse(fixture("stub-video.json")).tracking.knownKeys
        val unknown = fromStub - known
        assertTrue("заглушка шлёт неизвестные SDK события: $unknown", unknown.isEmpty())
    }

    @Test
    fun `ключевые события присутствуют`() {
        val t = AdResponseParser.parse(fixture("stub-video.json")).tracking
        for (e in listOf(
            TrackingEvent.IMPRESSION, TrackingEvent.START, TrackingEvent.FIRST_QUARTILE,
            TrackingEvent.MIDPOINT, TrackingEvent.THIRD_QUARTILE, TrackingEvent.COMPLETE,
            TrackingEvent.CLICK, TrackingEvent.ERROR,
        )) {
            assertTrue("нет ссылок на ${e.key}", t.has(e))
        }
    }

    @Test
    fun `выбор файла на реальном наборе заглушки`() {
        val media = AdResponseParser.parse(fixture("stub-video.json")).media

        // Типовой телефон 1080 — берём вариант ровно под него.
        assertEquals(1080, MediaSelector.select(media, 1080, Connection.WIFI)?.width)
        // Экран поуже — наименьший, который не придётся растягивать.
        assertEquals(852, MediaSelector.select(media, 800, Connection.WIFI)?.width)
        // Медленная сеть — самый лёгкий из имеющихся.
        assertNotNull(MediaSelector.select(media, 1080, Connection.CELLULAR_SLOW))
    }
}
