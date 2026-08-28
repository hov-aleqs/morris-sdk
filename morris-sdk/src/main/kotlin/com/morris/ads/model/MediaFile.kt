package com.morris.ads.model

/**
 * Один вариант видеофайла из ответа бэкенда.
 *
 * Вариантов приходит несколько — на практике четыре-пять вертикальных файлов
 * с разбросом ширины в несколько раз. Выбор конкретного делает SDK: только он
 * знает реальный экран и тип соединения. См. [com.morris.ads.media.MediaSelector].
 */
public data class MediaFile(
    public val url: String,
    public val width: Int,
    public val height: Int,
    public val bitrateKbps: Int,
    public val mimeType: String,
)
