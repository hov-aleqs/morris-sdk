package com.morris.sample

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.morris.ads.MorrisAds
import com.morris.ads.MorrisInterstitialAd
import com.morris.ads.MorrisRewardedAd
import com.morris.ads.model.Reward
import com.morris.ads.net.AdError

/**
 * Минимальная интеграция — ровно то, что делает партнёр.
 *
 * Экран намеренно уродлив и собран кодом: это не витрина, а способ увидеть
 * рекламу на живом телефоне и проверить, что публичный API удобен в руках.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var endpoint: String
    private var rewarded: MorrisRewardedAd? = null
    private var interstitial: MorrisInterstitialAd? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        endpoint = intent?.getStringExtra(EXTRA_ENDPOINT)?.takeIf { it.isNotBlank() } ?: DEFAULT_ENDPOINT

        status = TextView(this).apply {
            text = "Адрес: $endpoint"
            textSize = 13f
            setPadding(0, 32, 0, 32)
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(48, 48, 48, 48)
                addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(button("Загрузить и показать rewarded") { loadRewarded() })
                addView(button("Загрузить и показать interstitial") { loadInterstitial() })
            }
        )

        MorrisAds.initialize(this, endpoint)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun say(text: String) {
        runOnUiThread { status.text = text }
    }

    private fun loadRewarded() {
        say("Загружаю rewarded…")
        rewarded?.destroy()
        rewarded = MorrisRewardedAd(this, "rewarded_main").apply {
            listener = object : MorrisRewardedAd.Listener {
                override fun onLoaded(ad: MorrisRewardedAd) {
                    say("Загружено, показываю")
                    ad.show(this@MainActivity)
                }
                override fun onLoadFailed(ad: MorrisRewardedAd, error: AdError) {
                    say("Не загрузилось: ${error.message}")
                }
                override fun onShowFailed(ad: MorrisRewardedAd, message: String) {
                    say("Не показалось: $message")
                }
                override fun onRewarded(ad: MorrisRewardedAd, reward: Reward) {
                    say("Награда: ${reward.amount} ${reward.currency}")
                }
                override fun onDismissed(ad: MorrisRewardedAd) {
                    say("Закрыто")
                }
            }
            load()
        }
    }

    private fun loadInterstitial() {
        say("Загружаю interstitial…")
        interstitial?.destroy()
        interstitial = MorrisInterstitialAd(this, "interstitial_main").apply {
            listener = object : MorrisInterstitialAd.Listener {
                override fun onLoaded(ad: MorrisInterstitialAd) {
                    say("Загружено, показываю")
                    ad.show(this@MainActivity)
                }
                override fun onLoadFailed(ad: MorrisInterstitialAd, error: AdError) {
                    say("Не загрузилось: ${error.message}")
                }
                override fun onDismissed(ad: MorrisInterstitialAd) {
                    say("Закрыто")
                }
            }
            load()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        rewarded?.destroy()
        interstitial?.destroy()
    }

    private companion object {
        const val EXTRA_ENDPOINT = "endpoint"

        /**
         * Заглушка бэкенда из `tools/stub-backend`.
         *
         * Адрес localhost, а не адрес машины в сети: `adb reverse tcp:8080
         * tcp:8080` пробрасывает порт компьютера на телефон, и это работает
         * одинаково на телефоне по USB и на эмуляторе, не завися от Wi-Fi.
         *
         * Другой адрес без пересборки:
         *   adb shell am start -n com.morris.sample/.MainActivity \
         *       -e endpoint http://192.168.1.5:8080/api/ad
         */
        const val DEFAULT_ENDPOINT = "http://127.0.0.1:8080/api/ad"
    }
}
