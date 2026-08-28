package com.morris.ads

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.morris.ads.device.AdvertisingId
import com.morris.ads.device.AppInfo
import com.morris.ads.device.Consent
import com.morris.ads.net.AdClient
import com.morris.ads.net.AdRequest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Точка входа. Партнёр вызывает [initialize] один раз при старте приложения.
 *
 * SDK ходит только на один адрес — наш бэкенд. Ни один источник спроса SDK не
 * знает и напрямую с ним не разговаривает: добавление нового источника не
 * требует обновления приложений у партнёров.
 */
public object MorrisAds {

    /**
     * Версия SDK. Уходит в заявку — по ней видно, что и где крутится.
     *
     * Значение одно на весь SDK: два независимых числа разъехались бы молча,
     * и отчётность стала бы врать, не сломавшись.
     */
    public const val VERSION: String = AdRequest.MORRIS_SDK_VERSION

    private var client: AdClient? = null
    private var appInfo: AppInfo? = null

    /**
     * Идентификатор, прочитанный нами. Партнёрское значение имеет приоритет:
     * если он читает его сам (например, уже делает это для другого SDK), не
     * заставляем платить за второй межпроцессный вызов.
     */
    @Volatile private var readInfo: AdvertisingId.Info? = null
    @Volatile private var readAtMs: Long = 0
    @Volatile private var partnerInfo: AdvertisingId.Info? = null

    /**
     * Согласия пользователя.
     *
     * По умолчанию — «данных нет», и это НЕ то же самое, что «согласия нет».
     * Приложение обязано сообщить настоящие значения там, где они применимы:
     * иначе каждая заявка утверждает, что GDPR не действует и аудитория не
     * детская, а такое утверждение мы делать не вправе.
     */
    @Volatile private var consent: Consent = Consent()

    /**
     * Раз в полчаса перечитываем: пользователь может сбросить профиль или
     * включить запрет отслеживания, не перезапуская приложение.
     */
    private const val IFA_TTL_MS: Long = 30 * 60 * 1000

    /**
     * Фоновые работы SDK: сбор контекста устройства и скачивание ролика.
     *
     * На главном потоке этого быть не может — чтение рекламного идентификатора
     * идёт межпроцессно, а ролик весит мегабайты.
     *
     * Потоков несколько, а не один, именно из-за скачивания: приложение обычно
     * греет rewarded и interstitial разом, и на одном потоке заявка второго
     * ждала бы, пока докачается ролик первого. Три — потолок: больше юнитов
     * одновременно приложения не держат, а неограниченный пул на плохой сети
     * превратился бы в десяток висящих закачек.
     */
    internal val io: ExecutorService = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "morris-io").apply { isDaemon = true }
    }

    @JvmStatic
    public fun initialize(context: Context, endpoint: String) {
        // Заявка несёт рекламный идентификатор и модель устройства. По
        // открытому HTTP это уходит в сеть как есть, и прочитать может любой на
        // пути. Ошибиться здесь легко — адрес приходит из настроек приложения.
        if (!endpoint.startsWith("https://", ignoreCase = true)) {
            android.util.Log.w(
                "Morris",
                "адрес бэкенда не https — данные устройства пойдут открытым текстом: $endpoint",
            )
        }
        val app = context.applicationContext
        client = AdClient(endpoint)
        appInfo = AppInfo(
            bundle = app.packageName,
            version = runCatching {
                @Suppress("DEPRECATION")
                app.packageManager.getPackageInfo(app.packageName, 0).versionName
            }.getOrNull().orEmpty(),
        )
        // Греем идентификатор заранее, чтобы первая же заявка ушла с ним, а не
        // без него: именно первая обычно и есть самая ценная.
        io.execute { ensureAdvertisingId(app) }
    }

    /**
     * Сообщить рекламный идентификатор самостоятельно.
     *
     * Необязательно: SDK читает его сам. Метод для случая, когда приложение уже
     * получило идентификатор и не хочет второго обращения к Play Services.
     */
    @JvmStatic
    public fun setAdvertisingId(id: String?, limitAdTracking: Boolean) {
        partnerInfo = AdvertisingId.Info(id, limitAdTracking)
    }

    /**
     * Прочитать идентификатор, если его ещё нет или он устарел.
     * Блокирующий — только с фонового потока.
     */
    internal fun ensureAdvertisingId(context: Context) {
        if (partnerInfo != null) return
        val now = SystemClock.elapsedRealtime()
        if (readInfo != null && now - readAtMs < IFA_TTL_MS) return
        readInfo = AdvertisingId.read(context)
        readAtMs = now
    }

    private fun effective(): AdvertisingId.Info =
        partnerInfo ?: readInfo ?: AdvertisingId.UNKNOWN

    /** `null`, если отслеживание запрещено: тогда идентификатор не уходит вовсе. */
    /**
     * Сообщить согласия. Вызывать до первой загрузки и заново при изменении.
     *
     * @param gdprApplies действует ли GDPR для этого пользователя
     * @param gdprConsentString строка согласия IAB TCF, если она есть
     * @param usPrivacy строка CCPA, если она есть
     * @param coppa обращено ли приложение к детям
     */
    @JvmStatic
    @JvmOverloads
    public fun setConsent(
        gdprApplies: Boolean,
        gdprConsentString: String = "",
        usPrivacy: String = "",
        coppa: Boolean = false,
    ) {
        consent = Consent(gdprApplies, gdprConsentString, usPrivacy, coppa)
    }

    internal fun consent(): Consent = consent

    internal fun ifa(): String? = effective().let { if (it.limitAdTracking) null else it.id }

    internal fun isLimitAdTracking(): Boolean = effective().limitAdTracking

    internal fun requireClient(): AdClient =
        client ?: error("MorrisAds.initialize() не вызван")

    internal fun requireAppInfo(): AppInfo =
        appInfo ?: error("MorrisAds.initialize() не вызван")

    internal fun isInitialized(): Boolean = client != null

    /** Только для тестов: вернуть объект в состояние до инициализации. */
    internal fun resetForTests() {
        client = null
        appInfo = null
        readInfo = null
        readAtMs = 0
        partnerInfo = null
        consent = Consent()
    }

    /**
     * Колбэки партнёра выполняются на главном потоке.
     *
     * Ответ приходит с потока OkHttp, а партнёр в колбэке почти наверняка
     * трогает UI. Перекладывать это на него — источник падений, которые он
     * будет считать нашими.
     */
    internal val main: Handler = Handler(Looper.getMainLooper())

    internal fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
