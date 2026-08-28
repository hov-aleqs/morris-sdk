package com.morris.ads.media

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Файл ролика на диске.
 *
 * Без предзагрузки показ начинается с чёрного экрана: плеер тянет видео уже
 * после того, как пользователь его открыл, и на мобильной сети это несколько
 * секунд, за которые досмотр и теряется. Поэтому файл скачивается на этапе
 * `load()`, а `show()` играет с диска.
 */
internal class MediaCache(
    private val dir: File,
    private val http: OkHttpClient,
) {

    /**
     * Скачать файл. Возвращает `null`, если не получилось — это не отказ
     * показа: вызывающий сыграет потоком, что хуже, но лучше, чем ничего.
     */
    fun fetch(url: String, maxBytes: Long = MAX_FILE_BYTES): File? {
        val target = File(dir, nameFor(url))
        if (target.isFile && target.length() > 0) {
            // Трогаем метку, чтобы вычистка не убрала то, что ещё в ходу.
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        if (!dir.exists() && !dir.mkdirs()) return null

        // Пишем во временный и переименовываем: иначе прерванная закачка
        // осталась бы на диске как готовый файл и проигралась бы обрубком.
        val part = File(dir, target.name + ".part")
        return try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                val declared = body.contentLength()
                if (declared > maxBytes) return null

                part.outputStream().use { out ->
                    var written = 0L
                    val buf = ByteArray(64 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            val read = input.read(buf)
                            if (read < 0) break
                            written += read
                            if (written > maxBytes) return null
                            out.write(buf, 0, read)
                        }
                    }
                    if (written == 0L) return null
                }
            }
            if (part.renameTo(target)) target else null
        } catch (e: IOException) {
            null
        } finally {
            part.delete()
        }
    }

    /**
     * Убрать старое. Кэш рекламы живёт коротко: ролик, не показанный за сутки,
     * почти наверняка уже не тот, что открутят завтра.
     */
    fun evict(maxAgeMs: Long = MAX_AGE_MS, maxTotalBytes: Long = MAX_TOTAL_BYTES) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        val now = System.currentTimeMillis()

        val fresh = files.filter { f ->
            if (now - f.lastModified() > maxAgeMs) { f.delete(); false } else true
        }

        var total = fresh.sumOf { it.length() }
        if (total <= maxTotalBytes) return
        // Переполнение — удаляем с самого давнего.
        for (f in fresh.sortedBy { it.lastModified() }) {
            if (total <= maxTotalBytes) break
            total -= f.length()
            f.delete()
        }
    }

    private fun nameFor(url: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)

    companion object {
        /** Ролик тяжелее этого — ошибка на стороне креатива, а не наш случай. */
        const val MAX_FILE_BYTES: Long = 30L * 1024 * 1024
        const val MAX_TOTAL_BYTES: Long = 200L * 1024 * 1024
        const val MAX_AGE_MS: Long = 24L * 60 * 60 * 1000

        @Volatile private var instance: MediaCache? = null

        fun default(context: Context): MediaCache =
            instance ?: synchronized(this) {
                instance ?: MediaCache(
                    dir = File(context.applicationContext.cacheDir, "morris-media"),
                    http = OkHttpClient(),
                ).also { instance = it }
            }

        internal fun resetForTests() { instance = null }
    }
}
