package com.morris.ads.device

import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Сбор контекста устройства.
 *
 * Всё здесь читается с настоящего телефона и нигде больше взяться не может:
 * подставить эти значения на сервере нельзя, расхождение между заявкой и
 * реальностью видно принимающей стороне.
 *
 * Рекламный идентификатор собирается ОТДЕЛЬНО: его получение — блокирующий
 * вызов Google Play Services, который нельзя делать на главном потоке, и он
 * требует зависимости, которую партнёр может не подключить. Поэтому он
 * приходит параметром, а не читается здесь.
 */
public class DeviceContextCollector(private val context: Context) {

    public fun collect(ifa: String?, limitAdTracking: Boolean): DeviceContext {
        val metrics = context.resources.displayMetrics
        val portrait =
            context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE

        return DeviceContext(
            ifa = ifa,
            limitAdTracking = limitAdTracking,
            osVersion = Build.VERSION.RELEASE ?: "",
            make = Build.MANUFACTURER ?: "",
            model = Build.MODEL ?: "",
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            density = metrics.density,
            orientation = if (portrait) {
                DeviceContext.Orientation.PORTRAIT
            } else {
                DeviceContext.Orientation.LANDSCAPE
            },
            language = language(),
            carrier = carrier(),
            connection = connection(),
        )
    }

    /**
     * Язык. `configuration.locales` появился только в API 24, а minSdk у нас
     * 21 — на Android 5 и 6 обращение к нему роняет приложение партнёра.
     */
    @Suppress("DEPRECATION")
    private fun language(): String {
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales.takeIf { !it.isEmpty }?.get(0)
        } else {
            context.resources.configuration.locale
        }
        return (locale ?: Locale.getDefault()).language
    }

    /**
     * Оператор. Пустая строка у Wi-Fi-планшетов и в эмуляторе — отдаём null,
     * чтобы поле просто не уходило вместо того, чтобы уходить пустым.
     */
    private fun carrier(): String? = runCatching {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        tm?.networkOperatorName?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Тип соединения в кодировке OpenRTB.
     *
     * Всё обёрнуто в runCatching намеренно: на части прошивок обращение к
     * ConnectivityManager без разрешения бросает, и падать из-за поля, которое
     * всего лишь уточняет качество связи, — плохая цена.
     */
    private fun connection(): DeviceContext.ConnectionType = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return DeviceContext.ConnectionType.UNKNOWN

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return legacyConnection(cm)

        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            ?: return DeviceContext.ConnectionType.UNKNOWN

        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                DeviceContext.ConnectionType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                DeviceContext.ConnectionType.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                cellularGeneration()
            else -> DeviceContext.ConnectionType.UNKNOWN
        }
    }.getOrDefault(DeviceContext.ConnectionType.UNKNOWN)

    /**
     * До API 23 сети описывались одним NetworkInfo. Ветка нужна не ради
     * полноты: без неё Android 5 и 6 отдавали бы «соединение неизвестно»
     * поголовно, и это выглядело бы как свойство трафика, а не как наш пробел.
     */
    @Suppress("DEPRECATION")
    private fun legacyConnection(cm: ConnectivityManager): DeviceContext.ConnectionType =
        when (cm.activeNetworkInfo?.takeIf { it.isConnected }?.type) {
            ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_WIMAX ->
                DeviceContext.ConnectionType.WIFI
            ConnectivityManager.TYPE_ETHERNET -> DeviceContext.ConnectionType.ETHERNET
            ConnectivityManager.TYPE_MOBILE -> cellularGeneration()
            else -> DeviceContext.ConnectionType.UNKNOWN
        }

    private fun cellularGeneration(): DeviceContext.ConnectionType = runCatching {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return DeviceContext.ConnectionType.CELLULAR_UNKNOWN
        // dataNetworkType требует READ_PHONE_STATE на части версий; без него
        // бросает SecurityException, и это нормальный случай, а не сбой.
        // Сам метод — с API 24; ниже остаётся устаревший networkType.
        @Suppress("MissingPermission", "DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            tm.dataNetworkType
        } else {
            tm.networkType
        }
        when (type) {
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN,
            -> DeviceContext.ConnectionType.CELLULAR_2G

            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSPAP,
            -> DeviceContext.ConnectionType.CELLULAR_3G

            TelephonyManager.NETWORK_TYPE_LTE -> DeviceContext.ConnectionType.CELLULAR_4G
            TelephonyManager.NETWORK_TYPE_NR -> DeviceContext.ConnectionType.CELLULAR_5G
            else -> DeviceContext.ConnectionType.CELLULAR_UNKNOWN
        }
    }.getOrDefault(DeviceContext.ConnectionType.CELLULAR_UNKNOWN)
}
