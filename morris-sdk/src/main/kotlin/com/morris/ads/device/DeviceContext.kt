package com.morris.ads.device

/**
 * Контекст устройства, уходящий в заявку.
 *
 * Собирается на устройстве и только там: подставить эти значения на сервере
 * нельзя — они описывают конкретный телефон конкретного пользователя, и
 * расхождение между заявкой и реальностью видно принимающей стороне.
 *
 * Отдельная data-класс, а не сбор прямо в билдере запроса, чтобы сборку JSON
 * можно было проверять без Android: [DeviceContext] создаётся в тесте руками.
 */
public data class DeviceContext(
    /** Рекламный идентификатор. `null`, если получить не удалось. */
    public val ifa: String?,
    /** Пользователь ограничил отслеживание. Тогда [ifa] слать нельзя. */
    public val limitAdTracking: Boolean,
    public val osVersion: String,
    public val make: String,
    public val model: String,
    public val widthPx: Int,
    public val heightPx: Int,
    public val density: Float,
    public val orientation: Orientation,
    public val language: String,
    public val carrier: String?,
    public val connection: ConnectionType,
) {
    public enum class Orientation(public val wire: String) {
        PORTRAIT("portrait"),
        LANDSCAPE("landscape"),
    }

    /**
     * Тип соединения в кодировке OpenRTB 2.5 (§5.22): важна совместимость с
     * тем, что ждут принимающие системы, а не собственная нумерация.
     */
    public enum class ConnectionType(public val wire: Int) {
        UNKNOWN(0),
        ETHERNET(1),
        WIFI(2),
        CELLULAR_UNKNOWN(3),
        CELLULAR_2G(4),
        CELLULAR_3G(5),
        CELLULAR_4G(6),
        CELLULAR_5G(7),
    }
}

/**
 * Согласия пользователя. Пустые значения означают «нет данных», а не «согласия
 * нет»: это разные утверждения, и подменять одно другим нельзя.
 */
public data class Consent(
    public val gdprApplies: Boolean = false,
    public val gdprConsentString: String = "",
    public val usPrivacy: String = "",
    public val coppa: Boolean = false,
)

/** Приложение, в котором крутится SDK. */
public data class AppInfo(
    public val bundle: String,
    public val version: String,
)
