package com.morris.ads.tracking

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Отправка трекинговых пикселей.
 *
 * Два требования, которые здесь и решаются:
 *
 *  - **не потерять.** Сеть на телефоне рвётся постоянно, а потерянный пиксель
 *    показа — это потерянный показ. Поэтому ретраи.
 *  - **не отправить дважды.** Дубль показа хуже пропущенного: он превращает
 *    отчётность в неправду. Поэтому повтор только после сетевого сбоя, и
 *    никогда — после ответа сервера, даже неуспешного: ответ означает, что
 *    запрос дошёл.
 */
public class PixelFirer(
    private val http: OkHttpClient = defaultHttp(),
    private val maxAttempts: Int = 3,
    private val retryDelayMs: Long = 400,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {

    /** Что произошло с одним пикселем. Для метрик и для тестов. */
    public data class Result(
        public val url: String,
        public val ok: Boolean,
        public val attempts: Int,
        public val statusCode: Int?,
        public val error: String?,
    )

    public fun interface Observer {
        public fun onFired(result: Result)
    }

    public var observer: Observer? = null

    /** Отправляет все ссылки события. Блокирующий — вызывать с фонового потока. */
    public fun fireAll(urls: List<String>) {
        for (u in urls) fire(u)
    }

    public fun fire(url: String) {
        if (url.isBlank()) return

        var attempt = 0
        var lastError: String? = null

        while (attempt < maxAttempts) {
            attempt++
            try {
                execute(url).use { r ->
                    // Сервер ответил — запрос дошёл. Повторять нельзя ни при
                    // 200, ни при 500: во втором случае дубль засчитается на
                    // той стороне, если она к тому моменту оживёт.
                    observer?.onFired(
                        Result(url, r.isSuccessful, attempt, r.code, if (r.isSuccessful) null else "http ${r.code}")
                    )
                    return
                }
            } catch (e: IOException) {
                lastError = e.message ?: e.javaClass.simpleName
                if (attempt < maxAttempts) sleeper(retryDelayMs * attempt)
            }
        }

        observer?.onFired(Result(url, ok = false, attempts = attempt, statusCode = null, error = lastError))
    }

    private fun execute(url: String): Response =
        newCall(url).execute()

    private fun newCall(url: String): Call =
        http.newCall(
            Request.Builder()
                .url(url)
                .get()
                .header("Accept", "*/*")
                .build()
        )

    private companion object {
        /**
         * Таймауты короче, чем у заявки: пиксель никого не ждёт, а висящий
         * запрос держит поток и мешает следующим событиям.
         */
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}
