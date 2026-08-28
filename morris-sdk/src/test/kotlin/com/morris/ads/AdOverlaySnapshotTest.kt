package com.morris.ads

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.widget.FrameLayout
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.morris.ads.model.AdChoices
import com.morris.ads.model.AdResponse
import com.morris.ads.model.Branding
import com.morris.ads.model.Click
import com.morris.ads.model.MediaFile
import com.morris.ads.model.Reward
import com.morris.ads.model.Tracking
import com.morris.ads.ui.AdOverlayView
import org.junit.Rule
import org.junit.Test

/**
 * Отрисовка оверлея, снятая в пикселях.
 *
 * Эмулятор для этого не нужен: разметка рендерится на JVM. Смысл теста не в
 * «красиво ли», а в том, что обязательная маркировка физически присутствует на
 * экране во всех состояниях и не перекрывается кнопками.
 *
 * Записать эталоны:  gradle :morris-sdk:recordPaparazziDebug
 * Сверить с ними:    gradle :morris-sdk:verifyPaparazziDebug
 */
class AdOverlaySnapshotTest {

    @get:Rule
    val paparazzi: Paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        theme = "android:Theme.Material.NoActionBar.Fullscreen",
    )

    private fun ad(
        erid: String? = "2VtzqxAjfmM",
        withAdChoices: Boolean = true,
        label: String = "В магазин",
    ) = AdResponse(
        adId = "snap-0001",
        durationMs = 6000,
        skipAfterMs = null,
        controls = false,
        media = listOf(MediaFile("https://cdn/1080.mp4", 1080, 1350, 1080, "video/mp4")),
        click = Click(url = "https://example.com/landing", label = label),
        branding = Branding(
            erid = erid,
            adChoices = if (withAdChoices) {
                AdChoices("https://cdn/ac.png", "https://example.com/about-ads")
            } else null,
        ),
        tracking = Tracking.EMPTY,
        reward = Reward(1, "coins"),
        ttlMs = 1_800_000,
    )

    /**
     * Оверлей прозрачный, поэтому кладём его на кадр — иначе снимок пустой.
     *
     * Кадры настоящие, а не однотонная заливка: проверять надо не «нарисовалось
     * ли», а читается ли обязательная маркировка поверх реального видео. Самый
     * опасный случай — светлый кадр под белым текстом.
     */
    private fun onVideoFrame(overlay: AdOverlayView, frame: String = "frame_dark.png"): ViewGroup {
        val root = FrameLayout(paparazzi.context)
        val bmp = checkNotNull(javaClass.classLoader.getResourceAsStream(frame)) {
            "нет кадра $frame — сгенерируй его ffmpeg'ом, см. README"
        }.use { BitmapFactory.decodeStream(it) }
        root.background = BitmapDrawable(paparazzi.context.resources, bmp).apply {
            gravity = android.view.Gravity.FILL
        }
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        return root
    }

    private fun overlay(
        ad: AdResponse,
        state: AdOverlayView.State,
        icon: Boolean = true,
    ): AdOverlayView =
        AdOverlayView(paparazzi.context).apply {
            bind(ad)
            if (icon) setAdChoicesIcon(ColorDrawable(Color.WHITE))
            render(state)
        }

    @Test
    fun `идёт отсчёт — маркировка на месте, пропуска нет`() {
        paparazzi.snapshot(
            onVideoFrame(overlay(ad(), AdOverlayView.State(remainingSec = 6)))
        )
    }

    @Test
    fun `пропуск стал доступен`() {
        paparazzi.snapshot(
            onVideoFrame(
                overlay(ad(), AdOverlayView.State(remainingSec = 3, skipAvailable = true))
            )
        )
    }

    @Test
    fun `ролик доигран — доступно закрытие`() {
        paparazzi.snapshot(
            onVideoFrame(
                overlay(ad(), AdOverlayView.State(remainingSec = 0, closeAvailable = true))
            )
        )
    }

    @Test
    fun `маркировки нет — плашка и иконка убраны, вёрстка не разъезжается`() {
        paparazzi.snapshot(
            onVideoFrame(
                overlay(
                    ad(erid = null, withAdChoices = false),
                    AdOverlayView.State(remainingSec = 6),
                    icon = false,
                )
            )
        )
    }

    @Test
    fun `маркировка читается поверх светлого кадра`() {
        // Худший случай: белый текст на почти белом видео. Плашка должна
        // держать контраст сама, не полагаясь на то, каким будет ролик.
        paparazzi.snapshot(
            onVideoFrame(overlay(ad(), AdOverlayView.State(remainingSec = 6)), "frame_bright.png")
        )
    }

    @Test
    fun `маркировка читается поверх пёстрого кадра`() {
        paparazzi.snapshot(
            onVideoFrame(overlay(ad(), AdOverlayView.State(remainingSec = 6)), "frame_busy.png")
        )
    }

    @Test
    fun `длинная подпись кнопки не ломает раскладку`() {
        paparazzi.snapshot(
            onVideoFrame(
                overlay(
                    ad(label = "Установить приложение и получить бонус"),
                    AdOverlayView.State(remainingSec = 6),
                )
            )
        )
    }
}
