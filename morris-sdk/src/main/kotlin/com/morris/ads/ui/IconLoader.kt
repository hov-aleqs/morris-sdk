package com.morris.ads.ui

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.content.res.Resources
import java.io.IOException
import java.util.concurrent.Executor
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Загрузка иконки adChoices.
 *
 * Отдельно от плеера и от кэша роликов: иконка весит килобайты, живёт один
 * показ и не должна ни занимать место на диске, ни задерживать старт.
 *
 * Неудача — не сбой показа. Маркировка обязана остаться на экране, поэтому при
 * ошибке на месте иконки просто останется запасная, а не пустота.
 */
internal class IconLoader(
    private val resources: Resources,
    private val executor: Executor,
    private val http: OkHttpClient = OkHttpClient(),
    private val maxBytes: Long = MAX_BYTES,
) {

    /**
     * @param deliver вызывается ТОЛЬКО при успехе и только с готовой картинкой.
     *   Вызывающий сам решает, на каком потоке её показывать.
     */
    fun load(url: String, deliver: (Drawable) -> Unit) {
        if (url.isBlank()) return
        executor.execute {
            val drawable = fetch(url)
            if (drawable != null) deliver(drawable)
        }
    }

    private fun fetch(url: String): Drawable? = try {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > maxBytes) return null

            val bytes = body.byteStream().use { it.readBytes(maxBytes.toInt()) }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            BitmapDrawable(resources, bitmap)
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        // Пришло не изображение. Для маркировки это то же самое, что не пришло
        // ничего: покажем запасную иконку.
        null
    }

    private fun java.io.InputStream.readBytes(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = read(buf)
            if (read < 0) break
            total += read
            if (total > limit) return out.toByteArray()
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    private companion object {
        /** Иконка маркировки — это килобайты. Всё крупнее не иконка. */
        const val MAX_BYTES = 256L * 1024
    }
}
