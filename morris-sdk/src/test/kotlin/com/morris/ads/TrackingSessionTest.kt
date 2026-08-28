package com.morris.ads

import com.morris.ads.model.AdResponse
import com.morris.ads.model.Branding
import com.morris.ads.model.Click
import com.morris.ads.model.MediaFile
import com.morris.ads.model.Tracking
import com.morris.ads.model.TrackingEvent
import com.morris.ads.tracking.PixelFirer
import com.morris.ads.tracking.TrackingSession
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Склейка трекинга: шкала просмотра, защита от дублей и гарантия, что
 * поставленные в очередь пиксели успевают уйти до закрытия экрана.
 */
class TrackingSessionTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun url(p: String) = server.url(p).toString()

    private fun ad(durationMs: Long = 6_000): AdResponse = AdResponse(
        adId = "t-1",
        durationMs = durationMs,
        skipAfterMs = null,
        controls = false,
        media = listOf(MediaFile(url("/v.mp4"), 1080, 1350, 1080, "video/mp4")),
        click = Click(url("/landing"), "В магазин"),
        branding = Branding(null, null),
        tracking = Tracking(
            mapOf(
                "impression" to listOf(url("/t/impression")),
                "start" to listOf(url("/t/start")),
                "q1" to listOf(url("/t/q1")),
                "midpoint" to listOf(url("/t/midpoint")),
                "q3" to listOf(url("/t/q3")),
                "complete" to listOf(url("/t/complete"), url("/t/complete2")),
                "click" to listOf(url("/t/click")),
            )
        ),
        reward = null,
        ttlMs = 1_800_000,
    )

    private fun session(a: AdResponse = ad()) = TrackingSession(
        ad = a,
        firer = PixelFirer(
            http = OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(1, TimeUnit.SECONDS)
                .build(),
            maxAttempts = 1,
            retryDelayMs = 1,
            sleeper = {},
        ),
        // Один поток и синхронное ожидание в shutdown делают тест
        // детерминированным, не меняя проверяемого поведения.
        executor = Executors.newSingleThreadExecutor(),
    )

    private fun paths(n: Int): List<String> =
        (1..n).mapNotNull { server.takeRequest(2, TimeUnit.SECONDS)?.path }

    @Test
    fun `полный просмотр отправляет всю шкалу по одному разу`() {
        repeat(12) { server.enqueue(MockResponse().setResponseCode(200)) }
        val s = session()

        s.fire(TrackingEvent.IMPRESSION)
        s.onProgress(0)
        s.onProgress(1_600)
        s.onProgress(3_100)
        s.onProgress(4_600)
        s.onEnded()
        s.shutdown()

        val got = paths(server.requestCount)
        assertEquals(
            "каждое событие ровно один раз, complete — обе ссылки",
            listOf("/t/impression", "/t/start", "/t/q1", "/t/midpoint", "/t/q3", "/t/complete", "/t/complete2"),
            got,
        )
    }

    @Test
    fun `повторный вызов события ничего не шлёт`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val s = session()

        assertTrue("первый раз — уходит", s.fire(TrackingEvent.IMPRESSION))
        assertFalse("второй — нет", s.fire(TrackingEvent.IMPRESSION))
        assertFalse("и третий тоже", s.fire(TrackingEvent.IMPRESSION))
        s.shutdown()

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `частые тики плеера не порождают лавину запросов`() {
        repeat(10) { server.enqueue(MockResponse().setResponseCode(200)) }
        val s = session()
        // Имитируем реальный плеер: 61 тик, последний на 2400 мс. Это 40%
        // ролика — первая четверть пройдена, середина ещё нет.
        for (i in 0..60) s.onProgress(i * 40L)
        s.shutdown()

        assertEquals("start и q1 — и всё", 2, server.requestCount)
        assertEquals(setOf(TrackingEvent.START, TrackingEvent.FIRST_QUARTILE), s.firedEvents())
    }

    @Test
    fun `событие без ссылок считается отправленным, но запросов не делает`() {
        val bare = ad().copy(tracking = Tracking(emptyMap()))
        val s = session(bare)

        assertTrue("событие засчитано", s.fire(TrackingEvent.IMPRESSION))
        assertTrue(s.wasFired(TrackingEvent.IMPRESSION))
        s.shutdown()
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `shutdown дожидается очереди — complete не теряется при закрытии`() {
        // Самый важный пиксель уходит последним, ровно когда пользователь
        // закрывает экран. Обрывать очередь на этом месте нельзя.
        repeat(8) { server.enqueue(MockResponse().setResponseCode(200).setBodyDelay(120, TimeUnit.MILLISECONDS)) }
        val s = session()

        s.onEnded()      // ставит в очередь всю шкалу разом
        s.shutdown()     // и сразу закрываем

        assertTrue(
            "должны были уйти все 6 запросов, ушло ${server.requestCount}",
            server.requestCount >= 6,
        )
        assertTrue(s.wasFired(TrackingEvent.COMPLETE))
    }

    @Test
    fun `клик и закрытие живут отдельно от шкалы просмотра`() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(200)) }
        val s = session()

        s.onProgress(0)                       // start
        s.fire(TrackingEvent.CLICK)
        s.shutdown()

        assertEquals(setOf(TrackingEvent.START, TrackingEvent.CLICK), s.firedEvents())
    }
}
