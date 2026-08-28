package com.morris.ads

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.media.AudioManager
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import com.morris.ads.internal.AdShowRequest
import com.morris.ads.internal.AdShowStore
import com.morris.ads.model.TrackingEvent
import com.morris.ads.player.AudioFocus
import com.morris.ads.player.ExoVideoPlayer
import com.morris.ads.player.VideoPlayer
import com.morris.ads.tracking.PixelFirer
import com.morris.ads.tracking.TrackingSession
import com.morris.ads.ui.AdOverlayView
import com.morris.ads.ui.IconLoader

/**
 * Экран показа: видео на весь экран и оверлей поверх него.
 *
 * Здесь живёт вся логика, которую можно сделать неправильно, — отсчёт, момент
 * появления пропуска, отправка четвертей, выдача награды, поведение при
 * сворачивании и при кнопке «назад». Плеер за интерфейсом [VideoPlayer]
 * именно поэтому: настоящий ExoPlayer вне устройства не воспроизводит, и без
 * подмены всё перечисленное впервые проверялось бы на живом телефоне.
 *
 * Поворот экрана Activity не пересоздаёт — в манифесте объявлен `configChanges`.
 * Пересоздание обрывало бы воспроизведение и приводило бы либо к повторному
 * `start`, либо к потерянному `complete`.
 */
public class MorrisAdActivity : Activity() {

    private var request: AdShowRequest? = null
    private var tracking: TrackingSession? = null
    private var player: VideoPlayer? = null
    private var overlay: AdOverlayView? = null

    private val ticker = Handler(Looper.getMainLooper())
    private var ticking = false

    private var started = false
    private var ended = false
    private var rewardGiven = false
    private var dismissed = false
    /** Ролик стартует со звуком: беззвучный старт заметно снижает досмотр. */
    private var muted = false
    /** Система попросила приглушиться, но не замолчать. */
    private var ducked = false
    /** Пауза случилась из-за потери фокуса, а не по воле пользователя. */
    private var pausedByFocusLoss = false

    private var audioFocus: AudioFocus? = null

    /** Для сторожа зависания: где был плеер и когда он там оказался. */
    private var lastPositionMs = -1L
    private var lastProgressAtMs = 0L

    /**
     * Ролик так и не пошёл.
     *
     * Без этого сторожа экран висит чёрным навсегда: кнопки закрытия ещё нет,
     * «назад» до появления пропуска не работает — пользователь заперт в чужой
     * рекламе и уходит убивать приложение.
     */
    private val startWatchdog = Runnable {
        if (!started) fail("воспроизведение не началось за ${START_TIMEOUT_MS / 1000} с")
    }

    private val tick = object : Runnable {
        override fun run() {
            onTick()
            if (ticking) ticker.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val req = AdShowStore.take(intent?.getStringExtra(EXTRA_TOKEN))
        if (req == null) {
            // Показывать нечего: процесс пережил смерть, а объявление — нет.
            finish()
            return
        }
        request = req

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val p = playerFactory(this)
        player = p
        val ov = AdOverlayView(this)
        overlay = ov

        setContentView(
            FrameLayout(this).apply {
                addView(p.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(ov, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
        )

        tracking = TrackingSession(req.ad, PixelFirer())
        audioFocus = AudioFocus(this, ::onAudioFocusChange)
        ov.bind(req.ad)
        ov.listener = OverlayActions()
        loadAdChoicesIcon(req)
        renderOverlay(0)

        if (req.playbackUrl.isBlank()) {
            fail("нечего проигрывать: ссылка на файл пуста")
            return
        }

        p.setControlsEnabled(req.ad.controls)
        p.listener = PlayerEvents()
        p.prepare(req.playbackUrl)
        ticker.postDelayed(startWatchdog, START_TIMEOUT_MS)
    }

    /**
     * Подтянуть настоящую иконку adChoices поверх запасной.
     *
     * Сеть — не дело View, поэтому загрузка живёт здесь. Ошибка молчаливая:
     * запасная иконка уже стоит, и маркировка не пропадёт.
     */
    private fun loadAdChoicesIcon(req: AdShowRequest) {
        val url = req.ad.branding.adChoices?.iconUrl ?: return
        IconLoader(resources, MorrisAds.io).load(url) { drawable ->
            // Экран мог закрыться, пока картинка шла по сети.
            ticker.post { if (!isFinishing) overlay?.setAdChoicesIcon(drawable) }
        }
    }

    // --- события плеера ----------------------------------------------------

    private inner class PlayerEvents : VideoPlayer.Listener {

        override fun onReady(durationMs: Long) {
            if (started) return          // повторная готовность после паузы
            started = true
            // Отказ в фокусе — не повод не показывать: играем всё равно,
            // просто без права заглушать чужой звук.
            ticker.removeCallbacks(startWatchdog)
            audioFocus?.request()
            applyVolume()
            player?.play()
            // Показ засчитывается, когда картинка реально пошла, а не когда
            // экран открылся: между этим бывает несколько секунд буферизации.
            tracking?.fire(TrackingEvent.IMPRESSION)
            // Показ у нас всегда во весь экран — событие наступает ровно здесь.
            // Не слать его значило бы терять то, что бэкенд у нас запрашивает.
            tracking?.fire(TrackingEvent.FULLSCREEN)
            request?.callbacks?.onShown()
            startTicking()
        }

        override fun onEnded() = handleEnded()

        override fun onError(message: String) = fail(message)
    }

    private fun handleEnded() {
        if (ended) return
        ended = true
        stopTicking()
        tracking?.onEnded()

        val req = request
        val reward = req?.ad?.reward
        if (req != null && req.rewarded && reward != null && !rewardGiven) {
            rewardGiven = true
            req.callbacks.onRewarded(reward)
        }
        renderOverlay(req?.ad?.durationMs ?: 0)
    }

    // --- нажатия в оверлее -------------------------------------------------

    private inner class OverlayActions : AdOverlayView.Listener {

        override fun onCtaClicked() {
            tracking?.fire(TrackingEvent.CLICK)
            request?.callbacks?.onClicked()
            openExternally(request?.ad?.click?.url)
        }

        override fun onAdChoicesClicked() {
            openExternally(request?.ad?.branding?.adChoices?.clickUrl)
        }

        override fun onSkipClicked() {
            tracking?.fire(TrackingEvent.SKIP)
            finish()
        }

        override fun onCloseClicked() = finish()

        override fun onSoundToggled() {
            muted = !muted
            applyVolume()
            // Повторяемое событие: за показ звук выключают и включают сколько
            // угодно раз, и каждый такой раз — отдельный факт.
            tracking?.fireRepeatable(
                if (muted) TrackingEvent.MUTE else TrackingEvent.UNMUTE
            )
            renderOverlay(currentPosition())
        }
    }

    /**
     * «Назад» до появления пропуска не закрывает рекламу.
     *
     * Иначе награда за rewarded доставалась бы за одно нажатие, а мы отчитались
     * бы о показе, которого не было.
     */
    @Deprecated("Заменён на OnBackPressedDispatcher, недоступный без AndroidX")
    @Suppress("DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        val ad = request?.ad ?: return super.onBackPressed()
        when {
            ended -> finish()
            ad.skipAfterMs != null && currentPosition() >= ad.skipAfterMs -> {
                tracking?.fire(TrackingEvent.SKIP)
                finish()
            }
            // иначе нажатие просто игнорируется
        }
    }

    // --- звук --------------------------------------------------------------

    /**
     * Смена аудиофокуса. Приходит уже на главном потоке — [AudioFocus] за этим
     * следит, потому что система зовёт слушателя откуда угодно.
     */
    private fun onAudioFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                ducked = false
                applyVolume()
                // Продолжаем только то, что сами же и остановили. Паузу,
                // поставленную пользователем или уходом в фон, не трогаем.
                if (pausedByFocusLoss && started && !ended) {
                    pausedByFocusLoss = false
                    player?.play()
                    tracking?.fireRepeatable(TrackingEvent.RESUME)
                    startTicking()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> if (started && !ended) {
                // Звонок или чужое видео. Приглушить мало — реклама должна
                // встать, иначе пользователь досмотрит её, не видя.
                pausedByFocusLoss = true
                player?.pause()
                tracking?.fireRepeatable(TrackingEvent.PAUSE)
                stopTicking()
            }

            // Навигатор объявляет поворот: замолкать целиком не нужно.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                ducked = true
                applyVolume()
            }
        }
    }

    private fun applyVolume() {
        player?.setVolume(
            when {
                muted -> 0f
                ducked -> DUCK_VOLUME
                else -> 1f
            }
        )
    }

    // --- жизненный цикл ----------------------------------------------------

    override fun onPause() {
        super.onPause()
        if (!started || ended) return
        player?.pause()
        tracking?.fireRepeatable(TrackingEvent.PAUSE)
        stopTicking()
    }

    override fun onResume() {
        super.onResume()
        if (!started || ended) return
        // Вернулись на экран — чья бы ни была пауза, она кончилась.
        pausedByFocusLoss = false
        player?.play()
        tracking?.fireRepeatable(TrackingEvent.RESUME)
        startTicking()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTicking()
        ticker.removeCallbacks(startWatchdog)
        // Не отпустить фокус — значит оставить чужую музыку приглушённой.
        audioFocus?.abandon()
        audioFocus = null
        player?.release()
        player = null

        val t = tracking
        if (t != null) {
            // Закрытие — это тоже событие показа, и уйти оно должно до того,
            // как мы погасим очередь.
            t.fire(TrackingEvent.EXIT_FULLSCREEN)
            t.fire(TrackingEvent.CLOSE)
            t.shutdown()
        }
        notifyDismissed()
    }

    private fun notifyDismissed() {
        if (dismissed) return
        dismissed = true
        request?.callbacks?.onDismissed()
        request = null
    }

    // --- отсчёт ------------------------------------------------------------

    private fun startTicking() {
        if (ticking) return
        ticking = true
        // Отсчёт зависания ведём с этого мгновения: пауза, из которой мы
        // только что вышли, зависанием не была.
        lastPositionMs = -1
        lastProgressAtMs = SystemClock.elapsedRealtime()
        ticker.post(tick)
    }

    private fun stopTicking() {
        ticking = false
        ticker.removeCallbacks(tick)
    }

    private fun onTick() {
        val pos = currentPosition()
        if (checkStalled(pos)) return
        tracking?.onProgress(pos)
        renderOverlay(pos)
    }

    /**
     * Плеер стоит на месте, хотя должен играть.
     *
     * Так выглядит оборвавшаяся посреди ролика сеть: плеер не сообщает ни об
     * ошибке, ни о конце — он просто ждёт данных, которых уже не будет.
     *
     * @return true, если показ на этом закончен
     */
    private fun checkStalled(positionMs: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (positionMs != lastPositionMs) {
            lastPositionMs = positionMs
            lastProgressAtMs = now
            return false
        }
        if (now - lastProgressAtMs < STALL_TIMEOUT_MS) return false
        fail("воспроизведение встало на ${positionMs} мс")
        return true
    }

    private fun currentPosition(): Long = player?.positionMs ?: 0

    private fun renderOverlay(positionMs: Long) {
        val ad = request?.ad ?: return
        val skipAt = ad.skipAfterMs
        val remainingMs = (ad.durationMs - positionMs).coerceAtLeast(0)
        overlay?.render(
            AdOverlayView.State(
                // Округляем вверх: «0» на экране при ещё идущем ролике
                // выглядит как зависший таймер.
                remainingSec = ((remainingMs + 999) / 1000).toInt(),
                skipAvailable = !ended && skipAt != null && positionMs >= skipAt,
                closeAvailable = ended,
                muted = muted,
            )
        )
    }

    // --- прочее ------------------------------------------------------------

    /** Идентификатор объявления в сообщении: без него жалобу не с чем связать. */
    private fun fail(message: String) {
        stopTicking()
        ticker.removeCallbacks(startWatchdog)
        tracking?.fire(TrackingEvent.ERROR)
        val adId = request?.ad?.adId
        request?.callbacks?.onShowFailed(
            if (adId.isNullOrBlank()) message else "$message (объявление $adId)"
        )
        finish()
    }

    private fun openExternally(url: String?) {
        if (url.isNullOrBlank()) return

        // Открываем ТОЛЬКО веб-ссылки.
        //
        // Сюда приходит адрес из ответа бэкенда, а `ACTION_VIEW` умеет запускать
        // не только браузер: `tel:`, `sms:`, `market:` и схемы других
        // приложений. Подменённый или испорченный ответ иначе превращается в
        // запуск чужого приложения от имени игры партнёра.
        //
        // Ссылки на установку (`market:`) появятся, когда появятся кампании с
        // установками, — и это будет отдельное осознанное решение.
        val scheme = runCatching { Uri.parse(url).scheme }.getOrNull()?.lowercase()
        if (scheme != "http" && scheme != "https") return
        // Ошибку открытия глотаем намеренно: на устройстве может не оказаться
        // ничего, что откроет ссылку, и падать из-за этого посреди показа —
        // худший из возможных исходов.
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }


    internal companion object {
        const val EXTRA_TOKEN: String = "com.morris.ads.TOKEN"
        private const val TICK_MS = 200L

        /**
         * Насколько приглушаемся по просьбе системы. Значение подобрано так же,
         * как у вендорских SDK: слышно, что реклама идёт, но она не перебивает.
         */
        private const val DUCK_VOLUME = 0.3f

        /**
         * Сколько ждём начала воспроизведения и сколько терпим остановку
         * посреди ролика.
         *
         * Десять секунд — наш выбор, а не чьё-то опубликованное значение.
         * Отмерено от того, что файл к показу уже скачан на диск: ждать
         * приходится только при потоковом проигрывании, когда скачать не
         * удалось. Меньше — рискуем обрывать медленные, но живые сети.
         */
        private const val START_TIMEOUT_MS = 10_000L
        private const val STALL_TIMEOUT_MS = 10_000L

        /**
         * Подменяется только в тестах. Настоящий ExoPlayer на JVM создаётся, но
         * не воспроизводит — без подмены весь показ был бы непроверяем.
         */
        internal var playerFactory: (Context) -> VideoPlayer = { ExoVideoPlayer(it) }

        internal fun intent(context: Context, token: String): Intent =
            Intent(context, MorrisAdActivity::class.java).putExtra(EXTRA_TOKEN, token)
    }
}
