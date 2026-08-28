package com.morris.ads

import android.content.Context
import com.morris.ads.internal.FullScreenAdUnit
import com.morris.ads.model.Reward
import com.morris.ads.net.AdError

/**
 * Реклама с вознаграждением.
 *
 * ```
 * val ad = MorrisRewardedAd(context, "rewarded_main")
 * ad.listener = object : MorrisRewardedAd.Listener {
 *     override fun onLoaded(ad: MorrisRewardedAd) = ad.show(activity)
 *     override fun onRewarded(ad: MorrisRewardedAd, reward: Reward) = grant(reward)
 *     // остальные колбэки по необходимости
 * }
 * ad.load()
 * ```
 *
 * Все колбэки приходят на главном потоке.
 */
public class MorrisRewardedAd(
    context: Context,
    placement: String,
) {

    /**
     * Состояния показа.
     *
     * Набор совпадает с тем, что принято у мобильных рекламных SDK, — партнёр,
     * уже интегрировавший чужой, не должен искать, куда делся привычный
     * колбэк. Все методы имеют реализацию по умолчанию: обязателен только тот,
     * что нужен конкретному приложению.
     */
    public interface Listener {
        /** Объявление получено и готово к показу. */
        public fun onLoaded(ad: MorrisRewardedAd) {}

        /** Рекламы нет или запрос не удался. [AdError.NoFill] — это норма. */
        public fun onLoadFailed(ad: MorrisRewardedAd, error: AdError) {}

        /** Картинка пошла. С этого момента показ засчитан. */
        public fun onShown(ad: MorrisRewardedAd) {}

        /** Показать не удалось. Награда не выдаётся. */
        public fun onShowFailed(ad: MorrisRewardedAd, message: String) {}

        public fun onClicked(ad: MorrisRewardedAd) {}

        /** Досмотрено до конца — выдавайте вознаграждение здесь. */
        public fun onRewarded(ad: MorrisRewardedAd, reward: Reward) {}

        /** Экран закрыт. Приходит и после досмотра, и после пропуска. */
        public fun onDismissed(ad: MorrisRewardedAd) {}
    }

    public var listener: Listener? = null

    private val unit = FullScreenAdUnit(context, placement, rewarded = true).apply {
        onLoaded = { listener?.onLoaded(this@MorrisRewardedAd) }
        onLoadFailed = { listener?.onLoadFailed(this@MorrisRewardedAd, it) }
        onShown = { listener?.onShown(this@MorrisRewardedAd) }
        onShowFailed = { listener?.onShowFailed(this@MorrisRewardedAd, it) }
        onClicked = { listener?.onClicked(this@MorrisRewardedAd) }
        onRewarded = { listener?.onRewarded(this@MorrisRewardedAd, it) }
        onDismissed = { listener?.onDismissed(this@MorrisRewardedAd) }
    }

    /** Готово ли объявление к показу прямо сейчас (загружено и не устарело). */
    public val isReady: Boolean get() = unit.isReady

    public fun load(): Unit = unit.load()

    public fun show(context: Context): Unit = unit.show(context)

    /** Освободить юнит. После вызова колбэки не приходят. */
    public fun destroy() {
        listener = null
        unit.destroy()
    }
}
