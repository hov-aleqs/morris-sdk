package com.morris.ads.internal

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.morris.ads.MorrisAdActivity
import com.morris.ads.MorrisAds
import com.morris.ads.device.DeviceContextCollector
import com.morris.ads.media.MediaCache
import com.morris.ads.media.MediaSelector
import com.morris.ads.media.connectionOf
import com.morris.ads.model.AdResponse
import com.morris.ads.model.Reward
import com.morris.ads.net.AdClient
import com.morris.ads.net.AdError
import com.morris.ads.net.AdRequest

/**
 * Общая механика полноэкранного юнита.
 *
 * Rewarded и interstitial отличаются ровно одним — выдаётся ли награда за
 * досмотр. Всё остальное (загрузка, предзагрузка ролика, срок годности, запуск
 * экрана, порядок колбэков) у них общее, и разводить это по двум классам
 * значило бы чинить каждую ошибку дважды.
 *
 * `load()` целиком уходит на фоновый поток: сбор контекста устройства включает
 * чтение рекламного идентификатора — межпроцессный вызов, который на главном
 * потоке запрещён, — и скачивание ролика.
 */
internal class FullScreenAdUnit(
    context: Context,
    private val placement: String,
    private val rewarded: Boolean,
) {

    private val appContext: Context = context.applicationContext

    var onLoaded: (() -> Unit)? = null
    var onLoadFailed: ((AdError) -> Unit)? = null
    var onShown: (() -> Unit)? = null
    var onShowFailed: ((String) -> Unit)? = null
    var onClicked: (() -> Unit)? = null
    var onDismissed: (() -> Unit)? = null
    var onRewarded: ((Reward) -> Unit)? = null

    private var loaded: AdResponse? = null
    /** Видно тестам: это единственный способ убедиться, что играем с диска. */
    var playbackUrl: String? = null
        private set
    private var loadedAtMs: Long = 0
    private var token: String? = null
    private var showing = false
    private var loading = false

    val isReady: Boolean
        get() = loaded?.let { !isExpired(it) } ?: false

    fun load() {
        if (!MorrisAds.isInitialized()) {
            deliverLoadFailure(AdError.Malformed("MorrisAds.initialize() не вызван"))
            return
        }
        if (loading) {
            deliverLoadFailure(AdError.Malformed("загрузка уже идёт"))
            return
        }
        loading = true
        MorrisAds.io.execute { requestAd() }
    }

    private fun requestAd() {
        MorrisAds.ensureAdvertisingId(appContext)
        val device = DeviceContextCollector(appContext)
            .collect(MorrisAds.ifa(), MorrisAds.isLimitAdTracking())

        val request = AdRequest(
            placement = placement,
            app = MorrisAds.requireAppInfo(),
            device = device,
            consent = MorrisAds.consent(),
        )

        MorrisAds.requireClient().load(request, object : AdClient.Callback {
            override fun onLoaded(ad: AdResponse) {
                // Скачивание — снова на фон: сюда нас позвал поток OkHttp,
                // занимать его на мегабайты видео нельзя.
                MorrisAds.io.execute { prepare(ad, device.widthPx, device.connection) }
            }

            override fun onFailed(error: AdError) {
                loading = false
                deliverLoadFailure(error)
            }
        })
    }

    private fun prepare(
        ad: AdResponse,
        screenWidthPx: Int,
        connectionType: com.morris.ads.device.DeviceContext.ConnectionType,
    ) {
        val file = MediaSelector.select(ad.media, screenWidthPx, connectionOf(connectionType))
        if (file == null) {
            loading = false
            deliverLoadFailure(
                AdError.Malformed("нет проигрываемого файла среди ${ad.media.size} вариантов")
            )
            return
        }

        val cache = MediaCache.default(appContext)
        cache.evict()
        val local = cache.fetch(file.url)

        // Не скачалось — не отказ. Покажем потоком: это хуже по досмотру, но
        // несравнимо лучше, чем не показать вовсе.
        playbackUrl = local?.let { Uri.fromFile(it).toString() } ?: file.url

        // Срок годности считаем от монотонных часов: системное время
        // пользователь может перевести, и объявление тогда либо протухнет
        // мгновенно, либо не протухнет никогда.
        loadedAtMs = SystemClock.elapsedRealtime()
        loaded = ad
        loading = false
        MorrisAds.onMain { onLoaded?.invoke() }
    }

    fun show(context: Context) {
        val ad = loaded
        val url = playbackUrl
        when {
            showing -> deliverShowFailure("показ уже идёт")
            ad == null || url == null -> deliverShowFailure("нечего показывать: load() не выполнен")
            isExpired(ad) -> {
                // Показать протухшее объявление хуже, чем не показать: мы
                // отчитаемся о показе, за который нам уже не заплатят.
                loaded = null
                playbackUrl = null
                deliverShowFailure("объявление устарело, нужен новый load()")
            }
            else -> launch(context, ad, url)
        }
    }

    private fun launch(context: Context, ad: AdResponse, url: String) {
        showing = true
        loaded = null                     // одно объявление — один показ
        playbackUrl = null

        val t = AdShowStore.put(
            AdShowRequest(
                ad = ad,
                playbackUrl = url,
                rewarded = rewarded,
                callbacks = object : ShowCallbacks {
                    override fun onShown() = MorrisAds.onMain { this@FullScreenAdUnit.onShown?.invoke() }
                    override fun onClicked() = MorrisAds.onMain { this@FullScreenAdUnit.onClicked?.invoke() }
                    override fun onRewarded(reward: Reward) =
                        MorrisAds.onMain { this@FullScreenAdUnit.onRewarded?.invoke(reward) }

                    override fun onDismissed() {
                        showing = false
                        token = null
                        MorrisAds.onMain { this@FullScreenAdUnit.onDismissed?.invoke() }
                    }

                    override fun onShowFailed(message: String) {
                        showing = false
                        token = null
                        MorrisAds.onMain { this@FullScreenAdUnit.onShowFailed?.invoke(message) }
                    }
                },
            )
        )
        token = t
        context.startActivity(MorrisAdActivity.intent(context, t))
    }

    /** Партнёр закрыл юнит. Колбэки не должны пережить его экран. */
    fun destroy() {
        AdShowStore.drop(token)
        token = null
        loaded = null
        playbackUrl = null
        onLoaded = null
        onLoadFailed = null
        onShown = null
        onShowFailed = null
        onClicked = null
        onDismissed = null
        onRewarded = null
    }

    private fun isExpired(ad: AdResponse): Boolean =
        SystemClock.elapsedRealtime() - loadedAtMs >= ad.ttlMs

    private fun deliverLoadFailure(error: AdError) {
        MorrisAds.onMain { onLoadFailed?.invoke(error) }
    }

    private fun deliverShowFailure(message: String) {
        MorrisAds.onMain { onShowFailed?.invoke(message) }
    }
}
