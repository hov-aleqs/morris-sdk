using System;
using UnityEngine;

namespace Morris.Ads.Internal
{
    /// <summary>
    /// Один полноэкранный юнит по ту сторону моста.
    ///
    /// Rewarded и interstitial отличаются только вознаграждением, поэтому
    /// вызовы в Java собраны здесь одним местом: имена методов — это строки,
    /// компилятор их не проверяет, и чем меньше таких строк, тем меньше шансов
    /// разъехаться с нативным SDK.
    /// </summary>
    internal sealed class NativeAdUnit
    {
        internal Action Loaded;
        internal Action Shown;
        internal Action Clicked;
        internal Action Dismissed;
        internal Action<MorrisAdError> LoadFailed;
        internal Action<string> ShowFailed;
        internal Action<MorrisReward> Rewarded;

        private readonly string _javaClass;
        private readonly string _listenerInterface;
        private readonly string _placement;
        private bool _destroyed;

#if UNITY_ANDROID && !UNITY_EDITOR
        private AndroidJavaObject _ad;
        private AdListenerProxy _proxy;
#endif

        internal NativeAdUnit(string javaClass, string listenerInterface, string placement)
        {
            _javaClass = javaClass;
            _listenerInterface = listenerInterface;
            _placement = placement ?? string.Empty;
        }

        internal bool IsReady
        {
            get
            {
#if UNITY_ANDROID && !UNITY_EDITOR
                return !_destroyed && _ad != null && _ad.Call<bool>("isReady");
#else
                return false;
#endif
            }
        }

        internal void Load()
        {
            if (_destroyed) return;

#if UNITY_ANDROID && !UNITY_EDITOR
            if (!MorrisAds.IsInitialized)
            {
                Fail("MorrisAds.Initialize() не вызван");
                return;
            }
            if (_ad == null)
            {
                _ad = new AndroidJavaObject(_javaClass, AndroidBridge.Activity, _placement);
                _proxy = new AdListenerProxy(_listenerInterface, this);
                _ad.Call("setListener", _proxy);
            }
            _ad.Call("load");
#else
            // В редакторе и на других платформах рекламы нет, и молчать об этом
            // хуже всего: разработчик будет искать причину в своём коде.
            Fail("реклама доступна только на устройстве Android");
#endif
        }

        internal void Show()
        {
            if (_destroyed) return;

#if UNITY_ANDROID && !UNITY_EDITOR
            if (_ad == null)
            {
                MorrisDispatcher.Post(() => ShowFailed?.Invoke("нечего показывать: Load() не выполнен"));
                return;
            }
            // Запуск экрана показа — операция оконной системы. Главный поток
            // Unity потоком интерфейса Android не является.
            AndroidBridge.RunOnUiThread(() => _ad.Call("show", AndroidBridge.Activity));
#else
            MorrisDispatcher.Post(() => ShowFailed?.Invoke("реклама доступна только на устройстве Android"));
#endif
        }

        internal void Destroy()
        {
            if (_destroyed) return;
            _destroyed = true;

            Loaded = null; Shown = null; Clicked = null; Dismissed = null;
            LoadFailed = null; ShowFailed = null; Rewarded = null;

#if UNITY_ANDROID && !UNITY_EDITOR
            if (_ad != null)
            {
                try { _ad.Call("destroy"); }
                catch (Exception e) { Debug.LogException(e); }
                _ad.Dispose();
                _ad = null;
            }
            _proxy = null;
#endif
        }

        private void Fail(string message)
        {
            var error = new MorrisAdError
            {
                Kind = MorrisAdError.KindMalformed,
                Message = message,
            };
            MorrisDispatcher.Post(() => LoadFailed?.Invoke(error));
        }

        // --- вызывается из слушателя, уже в главном потоке Unity -------------

        internal void RaiseLoaded() => Loaded?.Invoke();
        internal void RaiseShown() => Shown?.Invoke();
        internal void RaiseClicked() => Clicked?.Invoke();
        internal void RaiseDismissed() => Dismissed?.Invoke();
        internal void RaiseLoadFailed(MorrisAdError e) => LoadFailed?.Invoke(e);
        internal void RaiseShowFailed(string m) => ShowFailed?.Invoke(m);
        internal void RaiseRewarded(MorrisReward r) => Rewarded?.Invoke(r);
    }
}
