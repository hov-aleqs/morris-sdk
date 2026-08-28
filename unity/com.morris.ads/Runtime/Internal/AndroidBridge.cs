using System;
using UnityEngine;

namespace Morris.Ads.Internal
{
    /// <summary>Общие мелочи работы с Android из Unity.</summary>
    internal static class AndroidBridge
    {
        internal const string MorrisAdsClass = "com.morris.ads.MorrisAds";
        internal const string RewardedClass = "com.morris.ads.MorrisRewardedAd";
        internal const string InterstitialClass = "com.morris.ads.MorrisInterstitialAd";
        internal const string RewardedListener = "com.morris.ads.MorrisRewardedAd$Listener";
        internal const string InterstitialListener = "com.morris.ads.MorrisInterstitialAd$Listener";

#if UNITY_ANDROID && !UNITY_EDITOR
        private static AndroidJavaObject _activity;

        /// <summary>Текущая Activity игры. Нужна и как контекст, и для показа.</summary>
        internal static AndroidJavaObject Activity
        {
            get
            {
                if (_activity != null) return _activity;
                using (var player = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
                {
                    _activity = player.GetStatic<AndroidJavaObject>("currentActivity");
                }
                return _activity;
            }
        }

        /// <summary>
        /// Выполнить в потоке интерфейса Android.
        ///
        /// Главный поток Unity им не является, а запуск экрана показа — операция
        /// оконной системы, и делать её со стороннего потока нельзя.
        /// </summary>
        internal static void RunOnUiThread(Action action)
        {
            Activity.Call("runOnUiThread", new AndroidJavaRunnable(() =>
            {
                try { action(); }
                catch (Exception e) { Debug.LogException(e); }
            }));
        }
#endif

        internal static bool IsSupported
        {
            get
            {
#if UNITY_ANDROID && !UNITY_EDITOR
                return true;
#else
                return false;
#endif
            }
        }

        /// <summary>
        /// Достать текст из Java-строки, пришедшей аргументом колбэка.
        ///
        /// Именно текст, а не ссылку: объект живёт только на время вызова, и
        /// откладывать обращение к нему на кадр вперёд нельзя.
        /// </summary>
        internal static string StringOf(object javaObject)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            var o = javaObject as AndroidJavaObject;
            return o == null ? null : o.Call<string>("toString");
#else
            return javaObject as string;
#endif
        }
    }
}
