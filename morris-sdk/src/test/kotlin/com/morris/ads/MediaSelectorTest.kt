package com.morris.ads

import com.morris.ads.media.Connection
import com.morris.ads.media.MediaSelector
import com.morris.ads.model.MediaFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Набор вариантов ровно такой, какой приходит на практике: четыре
 * вертикальных файла одного битрейта, разброс по ширине в четыре раза.
 */
private fun realVariants() = listOf(
    MediaFile("https://cdn/256.mp4", 256, 320, 1080, "video/mp4"),
    MediaFile("https://cdn/640.mp4", 640, 800, 1080, "video/mp4"),
    MediaFile("https://cdn/852.mp4", 852, 1064, 1080, "video/mp4"),
    MediaFile("https://cdn/1080.mp4", 1080, 1350, 1080, "video/mp4"),
)

class MediaSelectorTest {

    @Test
    fun `пустой список — нечего показывать`() {
        assertNull(MediaSelector.select(emptyList(), screenWidthPx = 1080, connection = Connection.WIFI))
    }

    @Test
    fun `единственный вариант берётся независимо от экрана`() {
        val only = listOf(MediaFile("https://cdn/one.mp4", 256, 320, 1080, "video/mp4"))
        assertEquals(only[0], MediaSelector.select(only, screenWidthPx = 1080, connection = Connection.WIFI))
    }

    @Test
    fun `берём наименьший вариант, который не придётся растягивать`() {
        // Экран 720: 256 и 640 пришлось бы растягивать, 852 — нет.
        val picked = MediaSelector.select(realVariants(), screenWidthPx = 720, connection = Connection.WIFI)
        assertEquals(852, picked?.width)
    }

    @Test
    fun `точное совпадение с шириной экрана предпочитается`() {
        val picked = MediaSelector.select(realVariants(), screenWidthPx = 1080, connection = Connection.WIFI)
        assertEquals(1080, picked?.width)
    }

    @Test
    fun `экран шире всех вариантов — берём самый крупный, а не самый мелкий`() {
        val picked = MediaSelector.select(realVariants(), screenWidthPx = 1440, connection = Connection.WIFI)
        assertEquals(1080, picked?.width)
    }

    @Test
    fun `на медленной сети берём самый лёгкий файл`() {
        val mixed = listOf(
            MediaFile("https://cdn/big.mp4", 1080, 1350, 4000, "video/mp4"),
            MediaFile("https://cdn/small.mp4", 256, 320, 300, "video/mp4"),
            MediaFile("https://cdn/mid.mp4", 640, 800, 1200, "video/mp4"),
        )
        val picked = MediaSelector.select(mixed, screenWidthPx = 1080, connection = Connection.CELLULAR_SLOW)
        assertEquals(300, picked?.bitrateKbps)
    }

    @Test
    fun `неподдерживаемый контейнер игнорируется`() {
        val withDash = listOf(
            MediaFile("https://cdn/stream.mpd", 1080, 1350, 1080, "application/dash+xml"),
            MediaFile("https://cdn/640.mp4", 640, 800, 1080, "video/mp4"),
        )
        val picked = MediaSelector.select(withDash, screenWidthPx = 1080, connection = Connection.WIFI)
        assertEquals("https://cdn/640.mp4", picked?.url)
    }

    @Test
    fun `все варианты неподдерживаемые — показывать нечего`() {
        val none = listOf(MediaFile("https://cdn/stream.mpd", 1080, 1350, 1080, "application/dash+xml"))
        assertNull(MediaSelector.select(none, screenWidthPx = 1080, connection = Connection.WIFI))
    }

    @Test
    fun `нулевая ширина экрана не роняет выбор`() {
        // Экран ещё не измерен — берём самый лёгкий, а не падаем.
        val picked = MediaSelector.select(realVariants(), screenWidthPx = 0, connection = Connection.WIFI)
        assertEquals(256, picked?.width)
    }
}
