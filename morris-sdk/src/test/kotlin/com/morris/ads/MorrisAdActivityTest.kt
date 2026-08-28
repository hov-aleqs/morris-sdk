package com.morris.ads

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Looper
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.morris.ads.internal.AdShowRequest
import com.morris.ads.internal.AdShowStore
import com.morris.ads.internal.ShowCallbacks
import com.morris.ads.model.AdChoices
import com.morris.ads.model.AdResponse
import com.morris.ads.model.Branding
import com.morris.ads.model.Click
import com.morris.ads.model.MediaFile
import com.morris.ads.model.Reward
import com.morris.ads.model.Tracking
import java.time.Duration
import java.util.Collections
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Показ целиком: отсчёт, гейт пропуска, четверти, награда, поведение при
 * сворачивании, повороте и кнопке «назад».
 *
 * Пиксели проверяются на настоящем HTTP-сервере — то есть утверждение здесь
 * «этот запрос действительно ушёл», а не «мы вызвали метод, который должен был
 * его отправить».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MorrisAdActivityTest {

    private lateinit var server: MockWebServer
    private val hits = Collections.synchronizedList(mutableListOf<String>())

    private lateinit var player: FakeVideoPlayer
    private var controller: ActivityController<MorrisAdActivity>? = null

    // что сообщил экран наружу
    private var shown = false
    private var clicked = false
    private var dismissed = false
    private var rewarded: Reward? = null
    private var showFailure: String? = null

    @Before
    fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    hits += path
                    // Иконку маркировки отдаём настоящим PNG: подделка не
                    // проверила бы декодирование, ради которого всё и делается.
                    return if (path.endsWith(".png")) {
                        MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "image/png")
                            .setBody(okio.Buffer().write(pngBytes()))
                    } else {
                        MockResponse().setResponseCode(200)
                    }
                }
            }
            start()
        }
        MorrisAdActivity.playerFactory = { ctx -> FakeVideoPlayer(ctx).also { player = it } }
    }

    @After
    fun tearDown() {
        controller?.let { runCatching { it.destroy() } }
        server.shutdown()
        MorrisAds.resetForTests()
    }

    // --- сборка показа -----------------------------------------------------

    private fun url(p: String) = server.url(p).toString()

    private fun ad(
        durationMs: Long = 15_000,
        skipAfterMs: Long? = 5_000,
        reward: Reward? = Reward(10, "coins"),
    ) = AdResponse(
        adId = "ad-1",
        durationMs = durationMs,
        skipAfterMs = skipAfterMs,
        controls = false,
        media = listOf(MediaFile(url("/video.mp4"), 720, 1280, 800, "video/mp4")),
        click = Click(url("/landing"), "Установить"),
        branding = Branding("2Vfnxy", AdChoices(url("/ac.png"), url("/ac"))),
        tracking = Tracking(
            listOf(
                "impression", "start", "q1", "midpoint", "q3",
                "complete", "click", "skip", "close", "pause", "resume", "error",
                "mute", "unmute", "fullscreen", "exit_fullscreen",
            ).associateWith { listOf(url("/t/$it")) }
        ),
        reward = reward,
        ttlMs = 1_800_000,
    )

    private fun start(
        ad: AdResponse = ad(),
        rewardedUnit: Boolean = true,
        playbackUrl: String = url("/video.mp4"),
    ): MorrisAdActivity {
        val token = AdShowStore.put(
            AdShowRequest(ad, playbackUrl, rewardedUnit, object : ShowCallbacks {
                override fun onShown() { shown = true }
                override fun onClicked() { clicked = true }
                override fun onRewarded(reward: Reward) { rewarded = reward }
                override fun onDismissed() { dismissed = true }
                override fun onShowFailed(message: String) { showFailure = message }
            })
        )
        val intent = Intent(ApplicationProvider.getApplicationContext(), MorrisAdActivity::class.java)
            .putExtra(MorrisAdActivity.EXTRA_TOKEN, token)
        val c = Robolectric.buildActivity(MorrisAdActivity::class.java, intent).setup()
        controller = c
        return c.get()
    }

    /** Сдвинуть плеер и дать экрану один тик. */
    private fun advanceTo(ms: Long) {
        player.position = ms
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
    }

    private fun awaitPath(path: String, timeoutMs: Long = 3_000): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (hits.contains(path)) return true
            Thread.sleep(10)
        }
        return false
    }

    /** Однопиксельный PNG: настоящая картинка, которую декодер примет. */
    private fun pngBytes(): ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    )

    private fun awaitHit(path: String, timeoutMs: Long = 2_000): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (hits.contains(path)) return true
            Thread.sleep(10)
        }
        return false
    }

    private fun timerText(a: MorrisAdActivity): String =
        a.findViewById<TextView>(R.id.morris_timer).text.toString()

    // --- собственно проверки -----------------------------------------------

    @Test
    fun `показ засчитывается когда картинка пошла, а не когда экран открылся`() {
        val a = start()

        assertFalse("до готовности плеера показа нет", shown)
        assertFalse(hits.contains("/t/impression"))

        player.becomeReady()

        assertTrue("плеер должен быть запущен", player.playing)
        assertTrue(shown)
        assertTrue(awaitHit("/t/impression"))
        assertEquals(url("/video.mp4"), player.preparedUrl)
        assertTrue(a.findViewById<TextView>(R.id.morris_erid).text.contains("2Vfnxy"))
    }

    @Test
    fun `четверти уходят по мере просмотра и по одному разу`() {
        start()
        player.becomeReady()

        advanceTo(0)
        assertTrue(awaitHit("/t/start"))

        advanceTo(4_000)                     // 26%
        assertTrue(awaitHit("/t/q1"))

        advanceTo(8_000)                     // 53%
        assertTrue(awaitHit("/t/midpoint"))

        // Несколько тиков на том же месте не должны ничего добавить.
        repeat(5) { advanceTo(8_100) }
        assertEquals("midpoint ровно один", 1, hits.count { it == "/t/midpoint" })
        assertFalse("до 75% третьей четверти быть не должно", hits.contains("/t/q3"))
    }

    @Test
    fun `кнопка пропуска не появляется раньше срока`() {
        val a = start(ad(skipAfterMs = 5_000))
        player.becomeReady()

        advanceTo(2_000)
        assertEquals("идёт отсчёт", "13", timerText(a))

        advanceTo(4_999)
        assertEquals("за миллисекунду до — всё ещё отсчёт", "11", timerText(a))

        advanceTo(5_000)
        assertEquals("Пропустить", timerText(a))
    }

    @Test
    fun `у неотключаемого ролика пропуска нет вовсе`() {
        val a = start(ad(skipAfterMs = null))
        player.becomeReady()

        advanceTo(14_000)
        assertEquals("1", timerText(a))
        assertFalse(hits.contains("/t/skip"))
    }

    @Test
    fun `назад до появления пропуска не закрывает рекламу`() {
        val a = start(ad(skipAfterMs = 5_000))
        player.becomeReady()
        advanceTo(2_000)

        @Suppress("DEPRECATION")
        a.onBackPressed()

        assertFalse("экран должен остаться", a.isFinishing)
        assertFalse(hits.contains("/t/skip"))
    }

    @Test
    fun `назад после появления пропуска считается пропуском`() {
        val a = start(ad(skipAfterMs = 5_000))
        player.becomeReady()
        advanceTo(6_000)

        @Suppress("DEPRECATION")
        a.onBackPressed()

        assertTrue(a.isFinishing)
        assertTrue(awaitHit("/t/skip"))
        assertNull("за пропуск награды нет", rewarded)
    }

    @Test
    fun `досмотр выдаёт награду ровно один раз и досылает шкалу`() {
        start()
        player.becomeReady()
        advanceTo(0)

        player.playOut()

        assertTrue(awaitHit("/t/complete"))
        assertTrue(awaitHit("/t/q3"))
        assertEquals(Reward(10, "coins"), rewarded)

        // Повторное событие от плеера не должно выдать награду второй раз.
        player.playOut()
        assertEquals(1, hits.count { it == "/t/complete" })
    }

    @Test
    fun `interstitial награду не выдаёт`() {
        start(rewardedUnit = false)
        player.becomeReady()
        player.playOut()

        assertTrue(awaitHit("/t/complete"))
        assertNull("у interstitial награды нет", rewarded)
    }

    @Test
    fun `сворачивание ставит на паузу, возврат продолжает`() {
        start()
        val c = controller!!
        player.becomeReady()
        advanceTo(3_000)

        c.pause()
        assertFalse("плеер должен встать", player.playing)
        assertTrue(awaitHit("/t/pause"))

        c.resume()
        assertTrue("плеер должен поехать", player.playing)
        assertTrue(awaitHit("/t/resume"))
    }

    @Test
    fun `поворот экрана не пересоздаёт показ`() {
        val a = start()
        player.becomeReady()
        advanceTo(4_000)
        assertTrue(awaitHit("/t/q1"))

        val landscape = Configuration(a.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
        controller!!.configurationChange(landscape)

        assertSame("Activity обязана пережить поворот", a, controller!!.get())
        assertFalse("плеер не должен быть освобождён", player.released)
        assertEquals("start ровно один", 1, hits.count { it == "/t/start" })
        assertEquals("impression ровно один", 1, hits.count { it == "/t/impression" })
    }

    // --- аудиофокус --------------------------------------------------------
    //
    // Без него реклама говорит поверх входящего звонка и поверх музыки
    // пользователя. Поведение проверяется на старом пути (до API 26), где
    // Robolectric отдаёт зарегистрированного слушателя; на новом проверяется,
    // что фокус вообще запрошен и отпущен.

    private fun audioManager(a: MorrisAdActivity) =
        a.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun focusListener(a: MorrisAdActivity): AudioManager.OnAudioFocusChangeListener =
        shadowOf(audioManager(a)).lastAudioFocusRequest.listener

    /**
     * Коды фокуса берём из платформы в рантайме.
     *
     * В classpath юнит-тестов у `AudioManager` числовые константы вырезаны —
     * класс виден, а поля нет. Переписать значения числами было бы легко и
     * неверно: они разъехались бы с платформой молча. Отражение читает ровно
     * то, что подставит система.
     */
    private fun focus(name: String): Int =
        AudioManager::class.java.getField(name).getInt(null)

    @Test
    @Config(sdk = [21])
    fun `фокус запрашивается, когда пошла картинка`() {
        val a = start()
        assertNull("до старта фокус не занимаем", shadowOf(audioManager(a)).lastAudioFocusRequest)

        player.becomeReady()

        assertNotNull("показ обязан попросить звук", shadowOf(audioManager(a)).lastAudioFocusRequest)
    }

    @Test
    @Config(sdk = [21])
    fun `входящий звонок ставит рекламу на паузу, а не только глушит`() {
        val a = start()
        player.becomeReady()
        advanceTo(3_000)

        focusListener(a).onAudioFocusChange(focus("AUDIOFOCUS_LOSS_TRANSIENT"))

        assertFalse("иначе пользователь досмотрит рекламу, не видя её", player.playing)
        assertTrue(awaitHit("/t/pause"))
    }

    @Test
    @Config(sdk = [21])
    fun `после звонка показ продолжается сам`() {
        val a = start()
        player.becomeReady()
        advanceTo(3_000)
        focusListener(a).onAudioFocusChange(focus("AUDIOFOCUS_LOSS_TRANSIENT"))
        assertFalse(player.playing)

        focusListener(a).onAudioFocusChange(focus("AUDIOFOCUS_GAIN"))

        assertTrue("иначе реклама осталась бы стоять навсегда", player.playing)
        assertTrue(awaitHit("/t/resume"))
    }

    @Test
    @Config(sdk = [21])
    fun `просьба приглушиться не останавливает показ`() {
        val a = start()
        player.becomeReady()
        advanceTo(1_000)

        // Навигатор объявляет поворот: замолкать целиком не нужно.
        focusListener(a).onAudioFocusChange(focus("AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"))

        assertTrue("ролик должен продолжать идти", player.playing)
        assertEquals(0.3f, player.volume, 0.001f)

        focusListener(a).onAudioFocusChange(focus("AUDIOFOCUS_GAIN"))
        assertEquals("громкость возвращается", 1f, player.volume, 0.001f)
    }

    @Test
    @Config(sdk = [21])
    fun `выключенный пользователем звук не включается обратно фокусом`() {
        val a = start()
        player.becomeReady()
        advanceTo(1_000)
        a.findViewById<android.widget.ImageView>(R.id.morris_sound).performClick()
        assertEquals(0f, player.volume, 0.001f)

        focusListener(a).onAudioFocusChange(focus("AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"))
        focusListener(a).onAudioFocusChange(focus("AUDIOFOCUS_GAIN"))

        assertEquals("решение пользователя важнее системного", 0f, player.volume, 0.001f)
    }

    @Test
    @Config(sdk = [21])
    fun `сигнал фокуса с чужого потока не теряется и не роняет показ`() {
        val a = start()
        player.becomeReady()
        advanceTo(2_000)

        // Система зовёт слушателя откуда угодно, а трогать плеер можно только
        // с главного потока.
        val listener = focusListener(a)
        val t = Thread { listener.onAudioFocusChange(focus("AUDIOFOCUS_LOSS")) }
        t.start(); t.join()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(player.playing)
        assertTrue(awaitHit("/t/pause"))
    }

    @Test
    fun `на новых версиях фокус тоже запрашивается и отпускается`() {
        val a = start()
        player.becomeReady()
        assertNotNull(shadowOf(audioManager(a)).lastAudioFocusRequest)

        controller!!.destroy()

        // Robolectric помнит «последний запрос», а не текущее состояние, и на
        // отпускании его не обнуляет — факт отпускания лежит отдельно.
        assertNotNull(
            "не отпустить — значит оставить чужую музыку приглушённой",
            shadowOf(audioManager(a)).lastAbandonedAudioFocusRequest,
        )
    }

    // --- маркировка --------------------------------------------------------

    private fun adChoicesDrawable(a: MorrisAdActivity) =
        a.findViewById<android.widget.ImageView>(R.id.morris_adchoices).drawable

    /**
     * Придерживает ответ с иконкой, пока тест не отпустит.
     *
     * Без этого проверка недетерминирована: картинка успевает дойти до того,
     * как тест посмотрит на запасную, и «подменилась» не отличить от «сразу
     * была сетевая».
     */
    private fun gatedIconServer(): java.util.concurrent.CountDownLatch {
        val gate = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                hits += path
                if (!path.endsWith(".png")) return MockResponse().setResponseCode(200)
                gate.await(5, java.util.concurrent.TimeUnit.SECONDS)
                return MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "image/png")
                    .setBody(okio.Buffer().write(pngBytes()))
            }
        }
        return gate
    }

    @Test
    fun `иконка маркировки стоит с первого кадра, не дожидаясь сети`() {
        // Пустой квадрат на месте значка «о рекламе» — это отсутствие
        // обязательной маркировки, а не мелкий изъян отрисовки.
        gatedIconServer()
        val a = start()

        val shown = adChoicesDrawable(a)
        assertNotNull("маркировка обязана быть на экране сразу", shown)
        assertFalse(
            "пока сеть не ответила, это должна быть запасная иконка",
            shown is android.graphics.drawable.BitmapDrawable,
        )
    }

    @Test
    fun `настоящая иконка подменяет запасную, когда доедет`() {
        val gate = gatedIconServer()
        val a = start()
        assertTrue("за иконкой должны были сходить", awaitPath("/ac.png"))

        gate.countDown()

        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline &&
            adChoicesDrawable(a) !is android.graphics.drawable.BitmapDrawable
        ) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue(
            "иконка рекламодателя должна была заменить запасную",
            adChoicesDrawable(a) is android.graphics.drawable.BitmapDrawable,
        )
    }

    @Test
    fun `иконка не дошла — маркировка всё равно на экране`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                hits += request.path.orEmpty()
                return if (request.path.orEmpty().endsWith(".png")) MockResponse().setResponseCode(500)
                else MockResponse().setResponseCode(200)
            }
        }
        val a = start()
        assertTrue(awaitPath("/ac.png"))
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull("маркировка не может зависеть от того, дошла ли картинка",
            adChoicesDrawable(a))
    }

    @Test
    fun `сообщение о сбое называет объявление`() {
        // Без идентификатора жалобу партнёра не с чем связать.
        start(playbackUrl = "")
        assertTrue("в сообщении нет объявления: $showFailure", showFailure!!.contains("ad-1"))
    }

    @Test
    fun `управление плеера включается ответом бэкенда, а не нами`() {
        // У части рекламодателей перемотка внутри ролика запрещена условиями
        // размещения, у части — нет. Решает ответ, а не SDK.
        start(ad().copy(controls = true))
        assertEquals(true, player.controlsEnabled)
    }

    @Test
    fun `по умолчанию управления нет — оверлей рисуем мы`() {
        start()
        assertEquals(false, player.controlsEnabled)
    }

    @Test
    fun `полноэкранность отмечается при старте и при закрытии`() {
        // Показ у нас всегда во весь экран. Бэкенд эти события запрашивает,
        // и не слать их значит терять то, о чём нас просили.
        start()
        player.becomeReady()
        assertTrue(awaitHit("/t/fullscreen"))

        controller!!.destroy()
        assertTrue(awaitHit("/t/exit_fullscreen"))
    }

    @Test
    fun `клик по кнопке отправляет пиксель и открывает ссылку`() {
        val a = start()
        player.becomeReady()

        a.findViewById<TextView>(R.id.morris_cta).performClick()

        assertTrue(clicked)
        assertTrue(awaitHit("/t/click"))
        val started = shadowOf(a).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(url("/landing"), started.data.toString())
    }

    @Test
    fun `ссылка не из веба не открывается`() {
        // `ACTION_VIEW` запускает не только браузер. Испорченный или
        // подменённый ответ иначе стал бы запуском чужого приложения от имени
        // игры партнёра.
        val a = start(ad().copy(click = Click("tel:+79001234567", "Позвонить")))
        player.becomeReady()

        a.findViewById<TextView>(R.id.morris_cta).performClick()

        assertNull("звонилка открываться не должна", shadowOf(a).nextStartedActivity)
        assertTrue("а клик всё равно засчитан — по нему заплатили", awaitHit("/t/click"))
    }

    @Test
    fun `обычная веб-ссылка открывается`() {
        val a = start()
        player.becomeReady()
        a.findViewById<TextView>(R.id.morris_cta).performClick()

        assertEquals(url("/landing"), shadowOf(a).nextStartedActivity.data.toString())
    }

    @Test
    fun `закрытие экрана отправляет close и освобождает плеер`() {
        start()
        player.becomeReady()
        advanceTo(1_000)

        controller!!.destroy()

        assertTrue(awaitHit("/t/close"))
        assertTrue("плеер должен быть освобождён", player.released)
        assertTrue("партнёр должен узнать о закрытии", dismissed)
    }

    // --- зависание ---------------------------------------------------------

    /** Прокрутить время, ничего не двигая: так выглядит зависший плеер. */
    private fun waitSeconds(sec: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(sec))
    }

    @Test
    fun `ролик не пошёл — экран закрывается, а не висит чёрным`() {
        // Самый неприятный исход: кнопки закрытия ещё нет, «назад» до пропуска
        // не работает, и пользователь заперт в чужой рекламе.
        val a = start()
        assertFalse(a.isFinishing)

        waitSeconds(11)

        assertTrue("экран обязан закрыться сам", a.isFinishing)
        assertTrue(showFailure!!.contains("не началось"))
        assertTrue(awaitHit("/t/error"))
    }

    @Test
    fun `воспроизведение встало посреди ролика — показ прекращается`() {
        val a = start()
        player.becomeReady()
        advanceTo(3_000)

        // Плеер не сообщает ни об ошибке, ни о конце — просто ждёт данных,
        // которых уже не будет.
        waitSeconds(11)

        assertTrue(a.isFinishing)
        assertTrue(showFailure!!.contains("встало"))
    }

    @Test
    fun `пауза не считается зависанием`() {
        start()
        player.becomeReady()
        advanceTo(3_000)

        controller!!.pause()
        waitSeconds(30)          // пользователь надолго ушёл из приложения
        controller!!.resume()
        advanceTo(3_200)

        assertTrue("показ должен продолжиться, а не оборваться", player.playing)
        assertNull(showFailure)
    }

    @Test
    fun `звонок посреди ролика не выглядит зависанием`() {
        val a = start()
        player.becomeReady()
        advanceTo(3_000)

        focusListener(a).onAudioFocusChange(focus("AUDIOFOCUS_LOSS_TRANSIENT"))
        waitSeconds(30)
        focusListener(a).onAudioFocusChange(focus("AUDIOFOCUS_GAIN"))
        advanceTo(3_200)

        assertNull("долгий разговор — не повод обрывать рекламу", showFailure)
        assertFalse(a.isFinishing)
    }

    @Test
    fun `сбой плеера завершает показ и сообщает партнёру`() {
        val a = start()
        player.fail("SOURCE_UNAVAILABLE")

        assertTrue(awaitHit("/t/error"))
        assertTrue(showFailure!!.startsWith("SOURCE_UNAVAILABLE"))
        assertTrue(a.isFinishing)
    }

    @Test
    fun `без ссылки на файл показ не начинается`() {
        val a = start(playbackUrl = "")

        assertTrue(a.isFinishing)
        assertFalse(shown)
        assertTrue(showFailure!!.contains("нечего проигрывать"))
    }

    @Test
    fun `звук выключается и включается, и каждый раз это отдельное событие`() {
        val a = start()
        player.becomeReady()
        advanceTo(1_000)

        val sound = a.findViewById<android.widget.ImageView>(R.id.morris_sound)
        assertEquals("ролик стартует со звуком", 1f, player.volume, 0.001f)

        sound.performClick()
        assertEquals(0f, player.volume, 0.001f)
        assertTrue(awaitHit("/t/mute"))
        assertEquals("Включить звук", sound.contentDescription)

        sound.performClick()
        assertEquals(1f, player.volume, 0.001f)
        assertTrue(awaitHit("/t/unmute"))
        assertEquals("Выключить звук", sound.contentDescription)

        // Третий раз — снова отдельный факт, а не дубль.
        sound.performClick()
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline && hits.count { it == "/t/mute" } < 2) Thread.sleep(10)
        assertEquals("выключений звука должно быть два", 2, hits.count { it == "/t/mute" })
    }

    @Test
    fun `экран, восстановленный после смерти процесса, закрывается вместо повторного показа`() {
        // Объявления в памяти уже нет, а Intent со старым ключом остался.
        val intent = Intent(ApplicationProvider.getApplicationContext(), MorrisAdActivity::class.java)
            .putExtra(MorrisAdActivity.EXTRA_TOKEN, "ключ-которого-нет")
        val c = Robolectric.buildActivity(MorrisAdActivity::class.java, intent).setup()
        controller = c

        assertTrue("показывать нечего — закрываемся", c.get().isFinishing)
        assertTrue("и ни одного пикселя", hits.isEmpty())
    }
}
