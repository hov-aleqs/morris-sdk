package com.morris.ads.internal

import com.morris.ads.model.AdResponse
import com.morris.ads.model.Reward
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Куда экран показа сообщает о происходящем. Наружу это станет колбэками. */
internal interface ShowCallbacks {
    fun onShown()
    fun onClicked()
    fun onRewarded(reward: Reward)
    fun onDismissed()
    fun onShowFailed(message: String)
}

/** Всё, что нужно экрану для одного показа. */
internal class AdShowRequest(
    val ad: AdResponse,
    /**
     * Что играть: путь к скачанному файлу или, если скачать не удалось,
     * сетевая ссылка. Выбор варианта сделан на этапе загрузки — экран показа
     * его не пересматривает, чтобы не было второго места, где это решается.
     */
    val playbackUrl: String,
    val rewarded: Boolean,
    val callbacks: ShowCallbacks,
)

/**
 * Передача объявления в Activity.
 *
 * Через Intent это не проходит: объявление держит ссылку на колбэки партнёра, а
 * складывать в Bundle мегабайт разметки и трекинга — лишняя сериализация и
 * риск `TransactionTooLargeException`. Поэтому в Intent едет только короткий
 * ключ, а само объявление лежит в процессе.
 *
 * Ключ забирается ровно один раз. Если процесс успели убить и Activity
 * восстанавливается на пустом месте, забирать будет нечего — и экран честно
 * закроется, вместо того чтобы показать рекламу второй раз и отчитаться о ней.
 */
internal object AdShowStore {

    private val pending = ConcurrentHashMap<String, AdShowRequest>()

    fun put(request: AdShowRequest): String =
        UUID.randomUUID().toString().also { pending[it] = request }

    fun take(token: String?): AdShowRequest? = token?.let { pending.remove(it) }

    /** Партнёр закрыл юнит, не показав. Иначе колбэки жили бы до конца процесса. */
    fun drop(token: String?) { token?.let { pending.remove(it) } }

    internal fun pendingCount(): Int = pending.size
}
