package com.morris.ads

import com.morris.ads.device.AppInfo
import com.morris.ads.device.Consent
import com.morris.ads.device.DeviceContext
import com.morris.ads.model.AdResponse
import com.morris.ads.net.AdClient
import com.morris.ads.net.AdError
import com.morris.ads.net.AdRequest
import com.morris.ads.net.AdRequestBuilder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Клиент проверяется на НАСТОЯЩЕМ HTTP-сервере, а не на подменённом
 * интерфейсе: половина того, что здесь может сломаться, — это коды ответа,
 * таймауты и чтение тела, то есть ровно то, что подмена скрывает.
 */
class AdClientTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun device(
        ifa: String? = "38400000-8cf0-11bd-b23e-10b96e40000d",
        lmt: Boolean = false,
    ) = DeviceContext(
        ifa = ifa,
        limitAdTracking = lmt,
        osVersion = "14",
        make = "Samsung",
        model = "SM-G991B",
        widthPx = 1080,
        heightPx = 2340,
        density = 3.0f,
        orientation = DeviceContext.Orientation.PORTRAIT,
        language = "ru",
        carrier = "MTS",
        connection = DeviceContext.ConnectionType.WIFI,
    )

    private fun request(ifa: String? = "abc", lmt: Boolean = false) = AdRequest(
        placement = "rewarded_main",
        app = AppInfo(bundle = "com.partner.game", version = "3.4.1"),
        device = device(ifa, lmt),
        consent = Consent(),
    )

    private fun client() = AdClient(
        endpoint = server.url("/v1/ad").toString(),
        http = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .build(),
    )

    /** Ждём колбэк, чтобы тест не зависел от того, в каком потоке он придёт. */
    private fun await(block: (AdClient.Callback) -> Unit): Pair<AdResponse?, AdError?> {
        var ad: AdResponse? = null
        var err: AdError? = null
        val latch = CountDownLatch(1)
        block(object : AdClient.Callback {
            override fun onLoaded(a: AdResponse) { ad = a; latch.countDown() }
            override fun onFailed(e: AdError) { err = e; latch.countDown() }
        })
        assertTrue("колбэк не пришёл за 10с", latch.await(10, TimeUnit.SECONDS))
        return ad to err
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(name)).bufferedReader().use { it.readText() }

    @Test
    fun `успешный ответ доезжает разобранным`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture("stub-video.json")))
        val (ad, err) = await { client().load(request(), it) }
        assertNull(err)
        assertEquals("stub-0001", ad?.adId)
        assertEquals(4, ad?.media?.size)
    }

    @Test
    fun `пустой ответ — это NoFill, а не ошибка разбора`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val (ad, err) = await { client().load(request(), it) }
        assertNull(ad)
        assertTrue("ожидали NoFill, получили $err", err is AdError.NoFill)
    }

    @Test
    fun `битое тело — Malformed, и его видно отдельно от NoFill`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{ это не json"))
        val (_, err) = await { client().load(request(), it) }
        assertTrue("ожидали Malformed, получили $err", err is AdError.Malformed)
    }

    @Test
    fun `код 500 отдаётся с самим кодом`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        val (_, err) = await { client().load(request(), it) }
        assertTrue(err is AdError.Server)
        assertEquals(500, (err as AdError.Server).code)
    }

    @Test
    fun `медленный бэкенд обрывается по таймауту, а не висит`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(fixture("stub-video.json"))
                .setBodyDelay(6, TimeUnit.SECONDS)
        )
        val (_, err) = await { client().load(request(), it) }
        assertTrue("ожидали Network, получили $err", err is AdError.Network)
    }

    @Test
    fun `в теле заявки уходит то, что задумано`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(fixture("stub-video.json")))
        await { client().load(request(), it) }

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/ad", recorded.path)

        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("rewarded_main", body.getString("placement"))
        assertEquals("android", body.getJSONObject("sdk").getString("platform"))
        assertEquals("com.partner.game", body.getJSONObject("app").getString("bundle"))

        val d = body.getJSONObject("device")
        assertEquals("abc", d.getString("ifa"))
        assertEquals(0, d.getInt("lmt"))
        assertEquals(1080, d.getInt("w"))
        assertEquals("portrait", d.getString("orientation"))
        assertEquals(2, d.getInt("connection"))   // WIFI по OpenRTB
    }

    @Test
    fun `при запрете отслеживания идентификатор не уходит вовсе`() {
        // Слать ifa с пометкой «но вы не используйте» бессмысленно: он уже
        // покинул устройство. Поэтому поля просто нет.
        val json = JSONObject(AdRequestBuilder.toJson(request(ifa = "abc", lmt = true)))
        val d = json.getJSONObject("device")
        assertFalse("ifa не должен уходить при lmt=1", d.has("ifa"))
        assertEquals(1, d.getInt("lmt"))
    }

    @Test
    fun `пустой идентификатор не превращается в пустую строку в теле`() {
        val json = JSONObject(AdRequestBuilder.toJson(request(ifa = null)))
        assertFalse(json.getJSONObject("device").has("ifa"))
    }

    @Test
    fun `недоступный бэкенд — сетевая ошибка, а не падение`() {
        server.shutdown()
        val (_, err) = await { client().load(request(), it) }
        assertTrue("ожидали Network, получили $err", err is AdError.Network)
    }
}
