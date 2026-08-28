package com.morris.ads.model

/**
 * Готовое к показу объявление.
 *
 * Всё, что пришло от источника спроса, бэкенд уже привёл к одному виду: SDK не
 * знает ни про VAST, ни про то, чья это реклама. Добавление нового источника не
 * требует обновления приложений у партнёров — в этом и смысл контракта.
 */
public data class AdResponse(
    public val adId: String,
    public val durationMs: Long,
    /** `null` — кнопки пропуска быть не должно. Число — показать её через столько. */
    public val skipAfterMs: Long?,
    /** `false` — прятать перемотку и прочие элементы плеера. */
    public val controls: Boolean,
    public val media: List<MediaFile>,
    public val click: Click,
    public val branding: Branding,
    public val tracking: Tracking,
    /** Заполнено только для rewarded. */
    public val reward: Reward?,
    /** После истечения объявление показывать нельзя — перезапросить. */
    public val ttlMs: Long,
)

public data class Click(
    public val url: String,
    /** Подпись кнопки приходит от рекламодателя. Своей не подставлять. */
    public val label: String,
)

/**
 * Обязательная маркировка. Отсутствие [erid] или [adChoices] на экране делает
 * показ некорректным независимо от того, проверяет ли это кто-нибудь.
 */
public data class Branding(
    public val erid: String?,
    public val adChoices: AdChoices?,
)

/**
 * Значок «о рекламе».
 *
 * Поля закрытия здесь намеренно нет: у нас adChoices — это ссылка наружу, а не
 * панель, которую можно закрыть, и события «закрыли» просто не существует.
 * Держать пиксель, который не срабатывает никогда, хуже, чем не держать его
 * вовсе: он выглядит как работающий. Появится панель — вернём вместе с ней.
 */
public data class AdChoices(
    public val iconUrl: String,
    public val clickUrl: String,
)

public data class Reward(
    public val amount: Int,
    public val currency: String,
)

/**
 * События, по которым SDK отстреливает пиксели.
 *
 * [key] — то, как событие называется в ответе бэкенда. Значения enum'а и ключи
 * разведены намеренно: переименование в протоколе не должно ломать код.
 */
public enum class TrackingEvent(public val key: String) {
    IMPRESSION("impression"),
    START("start"),
    FIRST_QUARTILE("q1"),
    MIDPOINT("midpoint"),
    THIRD_QUARTILE("q3"),
    COMPLETE("complete"),
    MUTE("mute"),
    UNMUTE("unmute"),
    PAUSE("pause"),
    RESUME("resume"),
    FULLSCREEN("fullscreen"),
    EXIT_FULLSCREEN("exit_fullscreen"),
    CLOSE("close"),
    SKIP("skip"),
    CLICK("click"),
    ERROR("error"),
}

/**
 * Трекинговые ссылки.
 *
 * Хранятся картой по строковому ключу, а не фиксированными полями: бэкенд может
 * начать присылать событие, которого SDK ещё не знает, и это не должно ломать
 * разбор. Неизвестные ключи просто никогда не сработают.
 */
public class Tracking(private val byEvent: Map<String, List<String>>) {

    public fun urlsFor(event: TrackingEvent): List<String> =
        byEvent[event.key].orEmpty()

    public fun has(event: TrackingEvent): Boolean =
        urlsFor(event).isNotEmpty()

    /** Ключи, пришедшие в ответе, включая неизвестные SDK. Для диагностики. */
    public val knownKeys: Set<String> get() = byEvent.keys

    override fun equals(other: Any?): Boolean =
        other is Tracking && other.byEvent == byEvent

    override fun hashCode(): Int = byEvent.hashCode()

    override fun toString(): String = "Tracking(${byEvent.keys.sorted()})"

    public companion object {
        public val EMPTY: Tracking = Tracking(emptyMap())
    }
}
