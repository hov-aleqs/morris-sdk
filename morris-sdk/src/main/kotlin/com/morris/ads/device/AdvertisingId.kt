package com.morris.ads.device

import android.content.Context

/**
 * Рекламный идентификатор устройства.
 *
 * Читается через отражение, а не прямым вызовом, намеренно: иначе SDK тянул бы
 * в каждое партнёрское приложение `play-services-ads-identifier` — лишний вес и
 * гарантированный конфликт версий с тем, что у партнёра уже стоит. Если
 * библиотеки на устройстве нет, идентификатора просто не будет, и это не сбой.
 *
 * Вызов блокирующий и на главном потоке запрещён — Google Play Services ходит
 * за идентификатором через межпроцессное взаимодействие.
 */
public object AdvertisingId {

    /**
     * @param id идентификатор или `null`, если получить не удалось
     * @param limitAdTracking пользователь запретил отслеживание
     */
    public data class Info(
        public val id: String?,
        public val limitAdTracking: Boolean,
    )

    /** Ничего не известно. Это не то же самое, что «отслеживание запрещено». */
    public val UNKNOWN: Info = Info(null, false)

    /**
     * Идентификатор из одних нулей Google отдаёт вместо настоящего, когда
     * пользователь сбросил рекламный профиль. Слать его наружу бессмысленно:
     * он одинаковый у всех и склеит в отчётности разных людей в одного.
     */
    private const val ZEROES = "00000000-0000-0000-0000-000000000000"

    /** Класс Play Services. Имя, а не тип, — библиотеки в зависимостях нет. */
    private const val CLIENT_CLASS = "com.google.android.gms.ads.identifier.AdvertisingIdClient"

    public fun read(context: Context): Info = read(context, CLIENT_CLASS)

    /**
     * Отдельный вход с именем класса нужен тестам.
     *
     * Библиотеки Google в сборке нет и быть не должно, поэтому путь «сервисы
     * есть» иначе не проверить вовсе: без него мы бы знали только, что код не
     * падает без них. Форма вызова здесь ровно та, что описана в правилах
     * обфускации вендорских SDK: `getAdvertisingIdInfo(Context)` → `Info`,
     * `Info.getId()`, `Info.isLimitAdTrackingEnabled()`.
     */
    internal fun read(context: Context, className: String): Info = runCatching {
        val client = Class.forName(className)
        val info = client
            .getMethod("getAdvertisingIdInfo", Context::class.java)
            .invoke(null, context.applicationContext)
            ?: return UNKNOWN

        val id = info.javaClass.getMethod("getId").invoke(info) as? String
        val limited = info.javaClass.getMethod("isLimitAdTrackingEnabled").invoke(info) as? Boolean

        Info(
            id = id?.takeIf { it.isNotBlank() && it != ZEROES },
            limitAdTracking = limited ?: false,
        )
    }.getOrDefault(UNKNOWN)
}
