package com.morris.ads

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.morris.ads.internal.FullScreenAdUnit
import com.morris.ads.media.MediaCache
import com.morris.ads.net.AdError
import java.util.Collections
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Загрузка объявления целиком: заявка, предзагрузка ролика, срок годности.
 *
 * Проверяется на настоящем HTTP-сервере, поэтому «ролик скачан» здесь значит
 * «за ним действительно сходили», а не «мы вызвали метод скачивания».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FullScreenAdUnitTest {

    private lateinit var server: MockWebServer
    private val paths = Collections.synchronizedList(mutableListOf<String>())
    private val bodies = Collections.synchronizedList(mutableListOf<String>())

    private var loaded = false
    private var loadError: AdError? = null
    private var showFailure: String? = null

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        MediaCache.resetForTests()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    paths += path
                    if (request.method == "POST") bodies += request.body.readUtf8()
                    return when {
                        path.startsWith("/bid") -> MockResponse().setResponseCode(200).setBody(adJson())
                        path.endsWith(".mp4") -> MockResponse().setResponseCode(200)
                            .setBody(Buffer().write(ByteArray(2048)))
                        else -> MockResponse().setResponseCode(200)
                    }
                }
            }
            start()
        }
        MorrisAds.initialize(context, server.url("/bid").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
        MorrisAds.resetForTests()
        MediaCache.resetForTests()
    }

    // --- сборка ------------------------------------------------------------

    private fun adJson(
        mime: String = "video/mp4",
        mediaPath: String = "/v.mp4",
        ttlMs: Long = 1_800_000,
    ): String = JSONObject(
        mapOf(
            "ad_id" to "ad-1",
            "duration_ms" to 15000,
            "controls" to false,
            "ttl_ms" to ttlMs,
            "media" to listOf(
                mapOf(
                    "url" to server.url(mediaPath).toString(),
                    "w" to 720, "h" to 1280, "bitrate" to 800, "mime" to mime,
                )
            ),
            "click" to mapOf("url" to "https://example.com/l", "label" to "Установить"),
            "branding" to mapOf("erid" to "2Vfnxy"),
            "tracking" to mapOf("impression" to listOf(server.url("/t/i").toString())),
        )
    ).toString()

    private fun unit(rewarded: Boolean = true) =
        FullScreenAdUnit(context, "rewarded_main", rewarded).apply {
            onLoaded = { loaded = true }
            onLoadFailed = { loadError = it }
            onShowFailed = { showFailure = it }
        }

    /** Дожидаемся фонового потока, прокручивая главный: колбэки приходят туда. */
    private fun await(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return true
            Thread.sleep(10)
        }
        return false
    }

    // --- проверки ----------------------------------------------------------

    @Test
    fun `load не выполняется на вызывающем потоке`() {
        val u = unit()
        u.load()
        // Сбор контекста включает межпроцессное чтение идентификатора и
        // скачивание мегабайт видео — на главном потоке этого быть не должно.
        assertEquals("к возврату из load() в сеть ещё не ходили", 0, server.requestCount)
        assertTrue(await { loaded })
    }

    @Test
    fun `ролик скачивается до готовности, и показывать будем с диска`() {
        val u = unit()
        u.load()

        assertTrue("объявление должно загрузиться", await { loaded })
        assertTrue("за роликом должны были сходить", paths.any { it.endsWith(".mp4") })
        assertTrue(
            "играть должны с диска, а не потоком: ${u.playbackUrl}",
            u.playbackUrl!!.startsWith("file://"),
        )
        assertTrue(u.isReady)
    }

    @Test
    fun `не скачался ролик — показываем потоком, а не отказываем`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return if (path.startsWith("/bid")) MockResponse().setResponseCode(200).setBody(adJson())
                else MockResponse().setResponseCode(500)
            }
        }
        val u = unit()
        u.load()

        assertTrue("отказа быть не должно", await { loaded })
        assertNull(loadError)
        assertTrue(
            "падаем на потоковое проигрывание: ${u.playbackUrl}",
            u.playbackUrl!!.startsWith("http"),
        )
    }

    @Test
    fun `нет проигрываемого файла — это отказ загрузки, а не пустой показ`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200)
                    .setBody(adJson(mime = "application/x-mpegURL", mediaPath = "/v.m3u8"))
        }
        val u = unit()
        u.load()

        assertTrue(await { loadError != null })
        assertFalse(loaded)
        assertTrue(loadError!!.message!!.contains("нет проигрываемого файла"))
    }

    @Test
    fun `без Play Services заявка уходит без идентификатора, но с lmt=0`() {
        // В тестовой среде Google Play Services нет — ровно как на устройстве
        // без сервисов Google. Это не сбой, и заявка должна уйти.
        val u = unit()
        u.load()
        assertTrue(await { loaded })

        val device = JSONObject(bodies.first()).getJSONObject("device")
        assertFalse("идентификатора нет — поля быть не должно", device.has("ifa"))
        assertEquals("но запрета отслеживания пользователь не ставил", 0, device.getInt("lmt"))
    }

    @Test
    fun `идентификатор от партнёра попадает в заявку`() {
        MorrisAds.setAdvertisingId("38400000-8cf0-11bd-b23e-10b96e40000d", limitAdTracking = false)
        val u = unit()
        u.load()
        assertTrue(await { loaded })

        val device = JSONObject(bodies.first()).getJSONObject("device")
        assertEquals("38400000-8cf0-11bd-b23e-10b96e40000d", device.getString("ifa"))
    }

    @Test
    fun `при запрете отслеживания идентификатор не уходит вовсе`() {
        MorrisAds.setAdvertisingId("38400000-8cf0-11bd-b23e-10b96e40000d", limitAdTracking = true)
        val u = unit()
        u.load()
        assertTrue(await { loaded })

        val device = JSONObject(bodies.first()).getJSONObject("device")
        assertFalse("отправить его — уже нарушить запрет", device.has("ifa"))
        assertEquals(1, device.getInt("lmt"))
    }

    @Test
    fun `согласия из приложения доезжают до заявки`() {
        MorrisAds.setConsent(
            gdprApplies = true,
            gdprConsentString = "CPXxRfAPXxRfAAfKABENB",
            usPrivacy = "1YNN",
            coppa = true,
        )
        val u = unit()
        u.load()
        assertTrue(await { loaded })

        val consent = JSONObject(bodies.first()).getJSONObject("consent")
        assertEquals(1, consent.getInt("gdpr"))
        assertEquals("CPXxRfAPXxRfAAfKABENB", consent.getString("gdpr_consent"))
        assertEquals("1YNN", consent.getString("us_privacy"))
        assertEquals(1, consent.getInt("coppa"))
    }

    @Test
    fun `без вызова setConsent уходят значения по умолчанию`() {
        // Это осознанный компромисс формата: полей «неизвестно» в нём нет.
        // Приложение обязано сообщить настоящие значения там, где они нужны, —
        // молчание здесь читается как «GDPR не действует».
        val u = unit()
        u.load()
        assertTrue(await { loaded })

        val consent = JSONObject(bodies.first()).getJSONObject("consent")
        assertEquals(0, consent.getInt("gdpr"))
        assertEquals("", consent.getString("gdpr_consent"))
        assertEquals(0, consent.getInt("coppa"))
    }

    @Test
    fun `версия SDK в заявке одна и не пустая`() {
        val u = unit()
        u.load()
        assertTrue(await { loaded })

        val sdk = JSONObject(bodies.first()).getJSONObject("sdk")
        assertEquals(MorrisAds.VERSION, sdk.getString("ver"))
        assertTrue(sdk.getString("ver").isNotBlank())
        assertEquals("android", sdk.getString("platform"))
    }

    @Test
    fun `протухшее объявление не показывается`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/bid") -> MockResponse().setResponseCode(200).setBody(adJson(ttlMs = 0))
                    else -> MockResponse().setResponseCode(200).setBody(Buffer().write(ByteArray(64)))
                }
            }
        }
        val u = unit()
        u.load()
        assertTrue(await { loaded })
        assertFalse("срок годности нулевой", u.isReady)

        u.show(context)
        assertTrue(await { showFailure != null })
        assertTrue(showFailure!!.contains("устарело"))
    }

    @Test
    fun `показ без загрузки не открывает пустой экран`() {
        unit().show(context)
        assertTrue(await { showFailure != null })
        assertTrue(showFailure!!.contains("load() не выполнен"))
    }

    @Test
    fun `второй load во время первого отклоняется`() {
        val u = unit()
        u.load()
        u.load()

        assertTrue(await { loadError != null })
        assertTrue(loadError!!.message!!.contains("загрузка уже идёт"))
    }

    @Test
    fun `destroy отвязывает колбэки`() {
        val u = unit()
        u.destroy()
        u.load()

        assertFalse("после destroy партнёр не должен получать ничего", await(1_000) { loaded })
    }
}
