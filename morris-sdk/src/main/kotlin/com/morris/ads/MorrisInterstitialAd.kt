package com.morris.ads

import android.content.Context
import com.morris.ads.internal.FullScreenAdUnit
import com.morris.ads.net.AdError

/**
 * Полноэкранная реклама между экранами приложения.
 *
 * Отличается от [MorrisRewardedAd] только отсутствием вознаграждения: за
 * досмотр здесь ничего не выдаётся, поэтому и колбэка на награду нет.
 */
public class MorrisInterstitialAd(
    context: Context,
    placement: String,
) {

    public interface Listener {
        public fun onLoaded(ad: MorrisInterstitialAd) {}
        public fun onLoadFailed(ad: MorrisInterstitialAd, error: AdError) {}
        public fun onShown(ad: MorrisInterstitialAd) {}
        public fun onShowFailed(ad: MorrisInterstitialAd, message: String) {}
        public fun onClicked(ad: MorrisInterstitialAd) {}
        public fun onDismissed(ad: MorrisInterstitialAd) {}
    }

    public var listener: Listener? = null

    private val unit = FullScreenAdUnit(context, placement, rewarded = false).apply {
        onLoaded = { listener?.onLoaded(this@MorrisInterstitialAd) }
        onLoadFailed = { listener?.onLoadFailed(this@MorrisInterstitialAd, it) }
        onShown = { listener?.onShown(this@MorrisInterstitialAd) }
        onShowFailed = { listener?.onShowFailed(this@MorrisInterstitialAd, it) }
        onClicked = { listener?.onClicked(this@MorrisInterstitialAd) }
        onDismissed = { listener?.onDismissed(this@MorrisInterstitialAd) }
    }

    public val isReady: Boolean get() = unit.isReady

    public fun load(): Unit = unit.load()

    public fun show(context: Context): Unit = unit.show(context)

    public fun destroy() {
        listener = null
        unit.destroy()
    }
}
