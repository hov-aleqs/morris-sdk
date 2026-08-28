package com.morris.sample

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.morris.ads.MorrisAds
import com.morris.ads.MorrisRewardedAd
import com.morris.ads.model.Reward
import com.morris.ads.net.AdError
import java.io.File
import java.util.Collections
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Показ на живом устройстве.
 *
 * Всё, что нельзя проверить на JVM: настоящее декодирование, длительность от
 * декодера, аудиофокус в реальной системе, поворот живого экрана. Сервер
 * поднимается прямо на устройстве, поэтому заявка, ролик и пиксели идут по
 * настоящему HTTP.
 *
 * Запуск:
 *   gradle :sample:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RealPlaybackTest {

    private lateinit var server: MockWebServer
    private val hits = Collections.synchronizedList(mutableListOf<String>())

    private lateinit var video: ByteArray

    private var loaded = false
    private var loadError: AdError? = null
    private var showFailure: String? = null
    private var reward: Reward? = null
    private var dismissed = false

    private val app: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        video = InstrumentationRegistry.getInstrumentation().context.assets
            .open("morris_test_6s.mp4").use { it.readBytes() }

        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    hits += path
                    return when {
                        path.startsWith("/bid") ->
                            MockResponse().setResponseCode(200).setBody(adJson())
                        path.endsWith(".mp4") -> MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "video/mp4")
                            .setBody(Buffer().write(video))
                        else -> MockResponse().setResponseCode(200)
                    }
                }
            }
            start()
        }
        MorrisAds.initialize(app, server.url("/bid").toString())
    }

    @After
    fun tearDown() {
        // Реклама остаётся на экране до закрытия — уводим её, иначе следующий
        // тест начнётся поверх чужого показа.
        repeat(3) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            Thread.sleep(300)
        }
        server.shutdown()
    }

    // --- сборка ------------------------------------------------------------

    private fun adJson(): String = JSONObject(
        mapOf(
            "ad_id" to "device-1",
            // Настоящая длительность ролика. Если декодер увидит другую,
            // шкала просмотра разъедется — это и проверяем.
            "duration_ms" to 6000,
            "controls" to false,
            "ttl_ms" to 1_800_000,
            "media" to listOf(
                mapOf(
                    "url" to server.url("/video.mp4").toString(),
                    "w" to 640, "h" to 360, "bitrate" to 400, "mime" to "video/mp4",
                )
            ),
            "click" to mapOf("url" to "https://example.com/l", "label" to "Установить"),
            "branding" to mapOf("erid" to "2Vfnxy"),
            "reward" to mapOf("amount" to 10, "currency" to "coins"),
            "tracking" to listOf(
                "impression", "start", "q1", "midpoint", "q3", "complete",
                "pause", "resume", "close", "error",
            ).associateWith { listOf(server.url("/t/$it").toString()) },
        )
    ).toString()

    private fun loadAndShow(): MorrisRewardedAd {
        lateinit var ad: MorrisRewardedAd
        onMain {
            ad = MorrisRewardedAd(app, "rewarded_main").apply {
                listener = object : MorrisRewardedAd.Listener {
                    override fun onLoaded(a: MorrisRewardedAd) { loaded = true }
                    override fun onLoadFailed(a: MorrisRewardedAd, e: AdError) { loadError = e }
                    override fun onShowFailed(a: MorrisRewardedAd, m: String) { showFailure = m }
                    override fun onRewarded(a: MorrisRewardedAd, r: Reward) { reward = r }
                    override fun onDismissed(a: MorrisRewardedAd) { dismissed = true }
                }
                load()
            }
        }
        assertTrue("объявление не загрузилось: $loadError", await(30_000) { loaded })

        val scenario = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { ad.show(it) }
        return ad
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun await(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun awaitHit(path: String, timeoutMs: Long = 45_000) =
        await(timeoutMs) { hits.contains("/t/$path") }

    // --- проверки ----------------------------------------------------------

    @Test
    fun ролик_доигрывает_до_конца_и_шкала_уходит_по_порядку() {
        loadAndShow()

        assertTrue("не дошло до конца за 45 с, дошли только: ${quartiles()}", awaitHit("complete"))

        assertEquals(
            "шкала должна уйти по возрастанию и без пропусков",
            listOf("impression", "start", "q1", "midpoint", "q3", "complete"),
            quartiles(),
        )
        assertEquals(Reward(10, "coins"), reward)
    }

    @Test
    fun длительность_приходит_от_настоящего_декодера() {
        val started = System.currentTimeMillis()
        loadAndShow()
        assertTrue(awaitHit("start"))
        val startAt = System.currentTimeMillis()
        assertTrue(awaitHit("complete"))
        val played = System.currentTimeMillis() - startAt

        // Ролик шестисекундный. Допуск широкий: на слабом устройстве старт
        // декодера занимает заметное время. Но десятикратного расхождения быть
        // не должно — оно означало бы, что шкала считается не по видео.
        assertTrue(
            "просмотр занял $played мс при шестисекундном ролике (всего с загрузки ${System.currentTimeMillis() - started} мс)",
            played in 3_000..20_000,
        )
    }

    @Test
    fun ролик_скачивается_на_диск_до_показа() {
        onMain {
            MorrisRewardedAd(app, "rewarded_main").apply {
                listener = object : MorrisRewardedAd.Listener {
                    override fun onLoaded(a: MorrisRewardedAd) { loaded = true }
                    override fun onLoadFailed(a: MorrisRewardedAd, e: AdError) { loadError = e }
                }
                load()
            }
        }
        assertTrue("не загрузилось: $loadError", await(30_000) { loaded })

        val dir = File(app.cacheDir, "morris-media")
        val files = dir.listFiles()?.filter { it.isFile && it.length() > 0 }
        assertNotNull("каталога кэша нет — показ пойдёт потоком", files)
        assertTrue("в кэше пусто: показ начнётся с буферизации", files!!.isNotEmpty())
        assertEquals(
            "на диске должен лежать весь ролик",
            video.size.toLong(),
            files.maxOf { it.length() },
        )
    }

    @Test
    fun перехват_аудиофокуса_ставит_рекламу_на_паузу() {
        loadAndShow()
        assertTrue(awaitHit("start"))

        // Так выглядит входящий звонок или запущенная музыка: фокус забирает
        // кто-то другой. Проверяется настоящая системная служба.
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        am.requestAudioFocus(
            { },
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN,
        )

        assertTrue("реклама обязана встать, а не говорить поверх", awaitHit("pause", 10_000))
    }

    @Test
    fun поворот_экрана_не_обрывает_показ() {
        loadAndShow()
        assertTrue(awaitHit("start"))

        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90)
        Thread.sleep(1_000)
        automation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0)

        assertTrue("после поворота показ должен доиграть", awaitHit("complete"))
        assertEquals(
            "начало показа не должно засчитаться дважды",
            1, hits.count { it == "/t/start" },
        )
        assertEquals(1, hits.count { it == "/t/impression" })
    }

    private fun quartiles(): List<String> = synchronized(hits) {
        hits.filter { it.startsWith("/t/") }
            .map { it.removePrefix("/t/") }
            .filter { it in setOf("impression", "start", "q1", "midpoint", "q3", "complete") }
            .distinct()
    }
}
