package com.morris.ads

import com.morris.ads.tracking.PixelFirer
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Пиксель показа терять нельзя, а слать дважды — ещё хуже: дубль превращает
 * отчётность в неправду. Оба требования проверяются на настоящем сервере.
 */
class PixelFirerTest {

    private lateinit var server: MockWebServer
    private val slept = mutableListOf<Long>()

    @Before fun setUp() { server = MockWebServer().apply { start() }; slept.clear() }
    @After fun tearDown() { server.shutdown() }

    private fun firer(maxAttempts: Int = 3) = PixelFirer(
        http = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.SECONDS)
            .build(),
        maxAttempts = maxAttempts,
        retryDelayMs = 1,
        sleeper = { slept += it },   // не тормозим тест настоящим сном
    )

    private fun capture(f: PixelFirer): MutableList<PixelFirer.Result> {
        val out = mutableListOf<PixelFirer.Result>()
        f.observer = PixelFirer.Observer { out += it }
        return out
    }

    @Test
    fun `успешный пиксель уходит один раз`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val f = firer(); val res = capture(f)
        f.fire(server.url("/t/impression").toString())

        assertEquals(1, server.requestCount)
        assertEquals(1, res.single().attempts)
        assertTrue(res.single().ok)
    }

    @Test
    fun `сетевой сбой повторяется`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setResponseCode(200))

        val f = firer(); val res = capture(f)
        f.fire(server.url("/t/impression").toString())

        assertEquals("должен был дойти с третьей попытки", 3, res.single().attempts)
        assertTrue(res.single().ok)
        assertEquals("между попытками должна быть пауза", 2, slept.size)
    }

    @Test
    fun `ответ сервера НЕ повторяется, даже если он неуспешный`() {
        // Ответ означает, что запрос дошёл. Повтор превратился бы в дубль
        // показа на той стороне, если она к этому моменту оживёт.
        server.enqueue(MockResponse().setResponseCode(500))
        val f = firer(); val res = capture(f)
        f.fire(server.url("/t/impression").toString())

        assertEquals("повторов быть не должно", 1, server.requestCount)
        assertEquals(1, res.single().attempts)
        assertFalse(res.single().ok)
        assertEquals(500, res.single().statusCode)
    }

    @Test
    fun `после исчерпания попыток сдаёмся и сообщаем об этом`() {
        repeat(3) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        val f = firer(maxAttempts = 3); val res = capture(f)
        f.fire(server.url("/t/impression").toString())

        assertEquals(3, res.single().attempts)
        assertFalse(res.single().ok)
        assertTrue("должна быть причина", !res.single().error.isNullOrBlank())
    }

    @Test
    fun `все ссылки события уходят, а не только первая`() {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(200)) }
        val f = firer(); val res = capture(f)
        f.fireAll(
            listOf(server.url("/t/a").toString(), server.url("/t/b").toString())
        )
        assertEquals(2, server.requestCount)
        assertEquals(2, res.size)
        assertEquals(setOf("/t/a", "/t/b"), setOf(server.takeRequest().path, server.takeRequest().path))
    }

    @Test
    fun `пустая ссылка молча игнорируется`() {
        val f = firer(); val res = capture(f)
        f.fire("")
        assertEquals(0, server.requestCount)
        assertTrue(res.isEmpty())
    }

    @Test
    fun `метод GET — пиксель это загрузка картинки, а не отправка данных`() {
        server.enqueue(MockResponse().setResponseCode(200))
        firer().fire(server.url("/t/x").toString())
        assertEquals("GET", server.takeRequest().method)
    }
}
