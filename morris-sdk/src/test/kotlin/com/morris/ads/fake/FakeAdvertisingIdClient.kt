package com.morris.ads.fake

import android.content.Context

/**
 * Подделка `AdvertisingIdClient` из Google Play Services.
 *
 * Повторяет ровно ту форму, что описана в правилах обфускации вендорских SDK:
 * статический `getAdvertisingIdInfo(Context)` возвращает объект с `getId()` и
 * `isLimitAdTrackingEnabled()`. Настоящую библиотеку в сборку тянуть нельзя —
 * весь смысл нашего чтения через отражение в том, чтобы её не было.
 */
class FakeAdvertisingIdClient {

    class Info(private val id: String?, private val limited: Boolean) {
        fun getId(): String? = id
        fun isLimitAdTrackingEnabled(): Boolean = limited
    }

    companion object {
        @JvmStatic
        var behaviour: (Context) -> Info? = { Info("id", false) }

        @JvmStatic
        fun getAdvertisingIdInfo(context: Context): Info? = behaviour(context)

        fun reset() { behaviour = { Info("id", false) } }
    }
}

/** Класс есть, но метод другой: так выглядит несовместимая версия библиотеки. */
class WrongShapeAdvertisingIdClient {
    companion object {
        @JvmStatic
        fun getAdvertisingIdInfo(): String = "не тот метод"
    }
}
