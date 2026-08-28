package com.morris.ads.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.morris.ads.R
import com.morris.ads.model.AdResponse

/**
 * Оверлей поверх видео: маркировка, кнопка действия, таймер и пропуск.
 *
 * Отдельный View, а не логика внутри Activity, по двум причинам: его можно
 * отрисовать и снять скриншотом на JVM без эмулятора, и его состояние
 * описывается одним [State] вместо разбросанных по Activity флагов.
 */
public class AdOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * Что показано на экране в данный момент.
     *
     * @param remainingSec сколько секунд ролика осталось
     * @param skipAvailable кнопка пропуска доступна
     * @param closeAvailable ролик доигран, доступно закрытие
     * @param muted звук выключен
     */
    public data class State(
        public val remainingSec: Int,
        public val skipAvailable: Boolean = false,
        public val closeAvailable: Boolean = false,
        public val muted: Boolean = false,
    )

    /** Что нажали. Activity превращает это в пиксели и в колбэки партнёру. */
    public interface Listener {
        public fun onCtaClicked()
        public fun onAdChoicesClicked()
        public fun onSkipClicked()
        public fun onCloseClicked()
        public fun onSoundToggled()
    }

    public var listener: Listener? = null

    private val adChoices: ImageView
    private val erid: TextView
    private val timer: TextView
    private val cta: TextView
    private val sound: ImageView

    init {
        LayoutInflater.from(context).inflate(R.layout.morris_ad_overlay, this, true)
        adChoices = findViewById(R.id.morris_adchoices)
        erid = findViewById(R.id.morris_erid)
        timer = findViewById(R.id.morris_timer)
        cta = findViewById(R.id.morris_cta)
        sound = findViewById(R.id.morris_sound)

        adChoices.setOnClickListener { listener?.onAdChoicesClicked() }
        sound.setOnClickListener { listener?.onSoundToggled() }
        cta.setOnClickListener { listener?.onCtaClicked() }
        timer.setOnClickListener {
            when {
                timer.tag == TAG_CLOSE -> listener?.onCloseClicked()
                timer.tag == TAG_SKIP -> listener?.onSkipClicked()
            }
        }
    }

    /**
     * Привязать объявление. Вызывается один раз перед началом показа.
     *
     * Маркировка выводится, только если она пришла: рисовать «Реклама» без
     * идентификатора смысла нет, а прятать пришедший — нельзя.
     */
    public fun bind(ad: AdResponse) {
        val eridValue = ad.branding.erid
        if (eridValue.isNullOrBlank()) {
            erid.visibility = View.GONE
        } else {
            erid.visibility = View.VISIBLE
            erid.text = context.getString(R.string.morris_ad_label, eridValue)
        }

        if (ad.branding.adChoices == null) {
            adChoices.visibility = View.GONE
        } else {
            adChoices.visibility = View.VISIBLE
            // Запасная иконка ставится сразу. Настоящая приходит по сети и
            // может не дойти — но маркировка обязана быть на экране с первого
            // кадра, а не с той секунды, когда докачается картинка.
            adChoices.setImageResource(R.drawable.morris_ic_ad_info)
        }

        val label = ad.click.label
        if (label.isBlank()) {
            cta.visibility = View.GONE
        } else {
            cta.visibility = View.VISIBLE
            cta.text = label
        }
    }

    /** Обновить состояние. Вызывается на каждом тике плеера. */
    public fun render(state: State) {
        sound.setImageResource(
            if (state.muted) R.drawable.morris_ic_sound_off else R.drawable.morris_ic_sound_on
        )
        // Подпись для озвучки описывает действие, а не текущее состояние:
        // «Включить звук» на выключенном — это то, что произойдёт по нажатию.
        sound.contentDescription = context.getString(
            if (state.muted) R.string.morris_sound_off else R.string.morris_sound_on
        )
        when {
            state.closeAvailable -> {
                timer.tag = TAG_CLOSE
                timer.text = context.getString(R.string.morris_close)
                timer.isClickable = true
            }
            state.skipAvailable -> {
                timer.tag = TAG_SKIP
                timer.text = context.getString(R.string.morris_skip)
                timer.isClickable = true
            }
            else -> {
                timer.tag = null
                timer.text = state.remainingSec.coerceAtLeast(0).toString()
                // Пока идёт отсчёт, тап по таймеру ничего не делает — иначе
                // пользователь закрывал бы рекламу мимо кнопки пропуска.
                timer.isClickable = false
            }
        }
    }

    /** Иконку adChoices грузит Activity: сеть — не дело View. */
    public fun setAdChoicesIcon(icon: android.graphics.drawable.Drawable?) {
        adChoices.setImageDrawable(icon)
    }

    private companion object {
        const val TAG_SKIP = "skip"
        const val TAG_CLOSE = "close"
    }
}
