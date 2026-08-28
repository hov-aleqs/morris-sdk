package com.morris.ads.media

/**
 * Тип соединения на момент выбора файла.
 *
 * [CELLULAR_SLOW] — это 2G/3G, где крупный файл не успеет прогрузиться к началу
 * показа. Различать быструю мобильную сеть и Wi-Fi для выбора файла смысла нет,
 * поэтому отдельного значения для неё нет.
 */
public enum class Connection {
    WIFI,
    CELLULAR_FAST,
    CELLULAR_SLOW,
    UNKNOWN,
}

/**
 * Перевод типа соединения из заявки в то, что важно плееру.
 *
 * Живёт рядом с [Connection], а не в экране показа: выбор файла делается на
 * этапе загрузки, и второго места, где это решается, быть не должно.
 */
internal fun connectionOf(type: com.morris.ads.device.DeviceContext.ConnectionType): Connection =
    when (type) {
        com.morris.ads.device.DeviceContext.ConnectionType.WIFI,
        com.morris.ads.device.DeviceContext.ConnectionType.ETHERNET,
        -> Connection.WIFI

        com.morris.ads.device.DeviceContext.ConnectionType.CELLULAR_4G,
        com.morris.ads.device.DeviceContext.ConnectionType.CELLULAR_5G,
        -> Connection.CELLULAR_FAST

        // Неопознанную мобильную считаем медленной: ошибиться в эту сторону
        // значит показать файл полегче, в другую — не доиграть.
        com.morris.ads.device.DeviceContext.ConnectionType.CELLULAR_2G,
        com.morris.ads.device.DeviceContext.ConnectionType.CELLULAR_3G,
        com.morris.ads.device.DeviceContext.ConnectionType.CELLULAR_UNKNOWN,
        -> Connection.CELLULAR_SLOW

        com.morris.ads.device.DeviceContext.ConnectionType.UNKNOWN -> Connection.UNKNOWN
    }
