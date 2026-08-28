package com.morris.ads.media

import com.morris.ads.model.MediaFile

/**
 * Выбор варианта видео под конкретное устройство.
 *
 * Правила, в порядке применения:
 *
 *  1. Отбрасываем контейнеры, которые плеер не проигрывает. Бэкенд может
 *     прислать всё, что было в креативе, включая потоковые форматы.
 *  2. На медленной мобильной сети берём самый лёгкий файл: доиграть важнее,
 *     чем показать в высоком разрешении.
 *  3. Иначе берём наименьший файл, который не придётся растягивать, то есть
 *     первый, чья ширина не меньше ширины экрана. Растянутое видео выглядит
 *     мылом, а лишние мегабайты никому не нужны.
 *  4. Если все варианты мельче экрана — берём самый крупный из имеющихся.
 */
public object MediaSelector {

    /** Контейнеры, которые умеет проигрывать плеер SDK. */
    private val SUPPORTED = setOf("video/mp4", "video/3gpp", "video/webm")

    public fun select(
        files: List<MediaFile>,
        screenWidthPx: Int,
        connection: Connection,
    ): MediaFile? {
        val playable = files.filter { it.mimeType.lowercase() in SUPPORTED }
        if (playable.isEmpty()) return null

        if (connection == Connection.CELLULAR_SLOW) {
            return playable.minBy { it.bitrateKbps }
        }

        // Экран ещё не измерен — не угадываем, берём самый лёгкий.
        if (screenWidthPx <= 0) {
            return playable.minBy { it.width }
        }

        return playable.filter { it.width >= screenWidthPx }.minByOrNull { it.width }
            ?: playable.maxBy { it.width }
    }
}
