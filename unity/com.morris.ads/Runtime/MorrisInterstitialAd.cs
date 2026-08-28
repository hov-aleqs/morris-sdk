using System;
using Morris.Ads.Internal;

namespace Morris.Ads
{
    /// <summary>
    /// Полноэкранная реклама между экранами игры.
    /// </summary>
    /// <remarks>
    /// Отличается от <see cref="MorrisRewardedAd"/> только отсутствием
    /// вознаграждения: за досмотр здесь ничего не выдаётся, поэтому и события
    /// о награде нет. Все события приходят в главном потоке Unity.
    /// </remarks>
    public sealed class MorrisInterstitialAd : IDisposable
    {
        public event Action<MorrisInterstitialAd> Loaded;
        public event Action<MorrisInterstitialAd, MorrisAdError> LoadFailed;
        public event Action<MorrisInterstitialAd> Shown;
        public event Action<MorrisInterstitialAd, string> ShowFailed;
        public event Action<MorrisInterstitialAd> Clicked;
        public event Action<MorrisInterstitialAd> Dismissed;

        private readonly NativeAdUnit _unit;

        public MorrisInterstitialAd(string placement)
        {
            _unit = new NativeAdUnit(
                AndroidBridge.InterstitialClass,
                AndroidBridge.InterstitialListener,
                placement)
            {
                Loaded = () => Loaded?.Invoke(this),
                LoadFailed = e => LoadFailed?.Invoke(this, e),
                Shown = () => Shown?.Invoke(this),
                ShowFailed = m => ShowFailed?.Invoke(this, m),
                Clicked = () => Clicked?.Invoke(this),
                Dismissed = () => Dismissed?.Invoke(this),
            };
        }

        public bool IsReady => _unit.IsReady;

        public void Load() => _unit.Load();

        public void Show() => _unit.Show();

        public void Destroy() => _unit.Destroy();

        void IDisposable.Dispose() => Destroy();
    }
}
