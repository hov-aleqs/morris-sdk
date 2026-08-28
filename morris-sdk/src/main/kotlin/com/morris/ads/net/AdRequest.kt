package com.morris.ads.net

import com.morris.ads.device.AppInfo
import com.morris.ads.device.Consent
import com.morris.ads.device.DeviceContext
import org.json.JSONObject

/** Заявка на рекламу. Всё, что SDK знает и отправляет бэкенду. */
public data class AdRequest(
    public val placement: String,
    public val app: AppInfo,
    public val device: DeviceContext,
    public val consent: Consent = Consent(),
    public val sdkVersion: String = MORRIS_SDK_VERSION,
) {
    public companion object {
        public const val MORRIS_SDK_VERSION: String = "1.0.0"
    }
}

/**
 * Сборка тела заявки.
 *
 * Вынесено из клиента отдельной функцией, потому что это единственное место,
 * где решается, что именно уходит наружу, — и проверять это нужно построчно,
 * без сети и без устройства.
 */
public object AdRequestBuilder {

    public fun toJson(req: AdRequest): String = JSONObject().apply {
        put("sdk", JSONObject().apply {
            put("ver", req.sdkVersion)
            put("platform", "android")
        })
        put("placement", req.placement)
        put("app", JSONObject().apply {
            put("bundle", req.app.bundle)
            put("ver", req.app.version)
        })
        put("device", deviceJson(req.device))
        put("consent", JSONObject().apply {
            put("gdpr", if (req.consent.gdprApplies) 1 else 0)
            put("gdpr_consent", req.consent.gdprConsentString)
            put("us_privacy", req.consent.usPrivacy)
            put("coppa", if (req.consent.coppa) 1 else 0)
        })
    }.toString()

    private fun deviceJson(d: DeviceContext): JSONObject = JSONObject().apply {
        // Пользователь запретил отслеживание — идентификатор не уходит вообще.
        // Слать его с пометкой «но вы не используйте» смысла нет: если он
        // покинул устройство, запрет уже нарушен.
        if (!d.limitAdTracking && !d.ifa.isNullOrBlank()) {
            put("ifa", d.ifa)
        }
        put("lmt", if (d.limitAdTracking) 1 else 0)
        put("os", "android")
        put("osv", d.osVersion)
        put("make", d.make)
        put("model", d.model)
        put("w", d.widthPx)
        put("h", d.heightPx)
        put("dpr", d.density.toDouble())
        put("orientation", d.orientation.wire)
        put("lang", d.language)
        d.carrier?.takeIf { it.isNotBlank() }?.let { put("carrier", it) }
        put("connection", d.connection.wire)
    }
}
