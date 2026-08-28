package com.morris.ads.net

import com.morris.ads.model.AdResponse
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Почему не удалось получить рекламу. */
public sealed class AdError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * Машиночитаемый вид ошибки.
     *
     * Разбирать по типу можно только там, где доступны классы Kotlin. Обёртки
     * над другими языками видят объект через отражение, а имена классов там
     * ещё и переживают обфускацию не всегда — им нужна стабильная строка.
     * Текст сообщения для этого не годится: он для человека и может меняться.
     */
    public abstract val kind: String

    /** Рекламы нет. Это норма, а не сбой: показывать нечего, и всё. */
    public object NoFill : AdError("нет подходящей рекламы") {
        override val kind: String get() = "no_fill"
        private fun readResolve(): Any = NoFill
    }

    /** Сеть недоступна или запрос не уложился в таймаут. */
    public class Network(message: String, cause: Throwable? = null) : AdError(message, cause) {
        override val kind: String get() = "network"
    }

    /** Бэкенд ответил не 2xx. */
    public class Server(public val code: Int) : AdError("бэкенд ответил $code") {
        override val kind: String get() = "server"
    }

    /** Ответ пришёл, но показать его нельзя. */
    public class Malformed(message: String, cause: Throwable? = null) : AdError(message, cause) {
        override val kind: String get() = "malformed"
    }
}

/**
 * Клиент к нашему бэкенду. Единственный адрес, который знает SDK.
 *
 * Асинхронный на колбэках, без корутин: SDK попадает в чужое приложение, и
 * тянуть туда `kotlinx-coroutines` ради одного запроса — это лишние сотни
 * килобайт в чужом APK и риск конфликта версий с тем, что там уже стоит.
 */
public class AdClient(
    private val endpoint: String,
    private val http: OkHttpClient = defaultHttp(),
) {

    public interface Callback {
        public fun onLoaded(ad: AdResponse)
        public fun onFailed(error: AdError)
    }

    public fun load(request: AdRequest, callback: Callback) {
        val body = AdRequestBuilder.toJson(request).toRequestBody(JSON)
        val call = http.newCall(
            Request.Builder()
                .url(endpoint)
                .post(body)
                .header("Accept", "application/json")
                .build()
        )

        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback.onFailed(AdError.Network(e.message ?: "сетевая ошибка", e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    if (!r.isSuccessful) {
                        callback.onFailed(AdError.Server(r.code))
                        return
                    }
                    val text = try {
                        r.body?.string().orEmpty()
                    } catch (e: IOException) {
                        callback.onFailed(AdError.Network("не дочитали тело", e))
                        return
                    }
                    val ad = try {
                        AdResponseParser.parseOrNoFill(text)
                    } catch (e: AdParseException) {
                        callback.onFailed(AdError.Malformed(e.message ?: "битый ответ", e))
                        return
                    }
                    if (ad == null) callback.onFailed(AdError.NoFill) else callback.onLoaded(ad)
                }
            }
        })
    }

    /** Отменить незавершённые запросы: приложение уходит с экрана. */
    public fun cancelAll() {
        http.dispatcher.cancelAll()
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * Таймауты жёсткие намеренно. Реклама, приехавшая через двадцать
         * секунд, уже никому не нужна: пользователь давно закрыл экран, а
         * показ, о котором мы отчитаемся, никто не увидит.
         */
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
