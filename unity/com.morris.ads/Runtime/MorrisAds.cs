using Morris.Ads.Internal;
using UnityEngine;

namespace Morris.Ads
{
    /// <summary>
    /// Точка входа. Вызовите <see cref="Initialize"/> один раз при старте игры.
    /// </summary>
    /// <remarks>
    /// SDK ходит только на ваш бэкенд. Ни один источник спроса он не знает и
    /// напрямую с ним не разговаривает.
    /// </remarks>
    public static class MorrisAds
    {
        /// <summary>Версия этой обёртки. Версия нативного SDK — <see cref="NativeVersion"/>.</summary>
        public const string BridgeVersion = "1.0.0";

        /// <summary>Реклама доступна: игра запущена на устройстве Android.</summary>
        public static bool IsSupported => AndroidBridge.IsSupported;

        internal static bool IsInitialized { get; private set; }

        /// <param name="endpoint">Адрес вашего бэкенда, например https://ads.example.com/api/ad</param>
        public static void Initialize(string endpoint)
        {
            if (string.IsNullOrEmpty(endpoint))
            {
                Debug.LogError("[Morris] Initialize: адрес бэкенда пуст");
                return;
            }

#if UNITY_ANDROID && !UNITY_EDITOR
            using (var morris = new AndroidJavaClass(AndroidBridge.MorrisAdsClass))
            {
                morris.CallStatic("initialize", AndroidBridge.Activity, endpoint);
            }
            IsInitialized = true;
#else
            Debug.Log("[Morris] Реклама работает только на устройстве Android. " +
                      "В редакторе загрузка будет завершаться отказом — это ожидаемо.");
#endif
        }

        /// <summary>
        /// Сообщить рекламный идентификатор самостоятельно.
        /// </summary>
        /// <remarks>
        /// Необязательно: SDK читает его сам. Метод пригодится, если игра уже
        /// получила идентификатор для другого SDK и не хочет второго обращения
        /// к сервисам Google.
        /// </remarks>
        public static void SetAdvertisingId(string id, bool limitAdTracking)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            using (var morris = new AndroidJavaClass(AndroidBridge.MorrisAdsClass))
            {
                morris.CallStatic("setAdvertisingId", id, limitAdTracking);
            }
#endif
        }

        /// <summary>
        /// Сообщить согласия пользователя.
        /// </summary>
        /// <remarks>
        /// Вызывать до первой загрузки и заново при изменении. Без этого вызова
        /// каждая заявка утверждает, что GDPR не действует и аудитория не
        /// детская — а такое утверждение игра делать не вправе, если это не так.
        /// </remarks>
        /// <param name="gdprApplies">действует ли GDPR для этого пользователя</param>
        /// <param name="gdprConsentString">строка согласия IAB TCF, если она есть</param>
        /// <param name="usPrivacy">строка CCPA, если она есть</param>
        /// <param name="coppa">обращена ли игра к детям</param>
        public static void SetConsent(
            bool gdprApplies,
            string gdprConsentString = "",
            string usPrivacy = "",
            bool coppa = false)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            using (var morris = new AndroidJavaClass(AndroidBridge.MorrisAdsClass))
            {
                morris.CallStatic("setConsent",
                    gdprApplies, gdprConsentString ?? "", usPrivacy ?? "", coppa);
            }
#endif
        }

        /// <summary>Версия нативного SDK. Пусто, если игра не на Android.</summary>
        public static string NativeVersion
        {
            get
            {
#if UNITY_ANDROID && !UNITY_EDITOR
                using (var morris = new AndroidJavaClass(AndroidBridge.MorrisAdsClass))
                {
                    return morris.GetStatic<string>("VERSION");
                }
#else
                return string.Empty;
#endif
            }
        }
    }
}
