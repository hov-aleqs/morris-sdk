using System;
using Morris.Ads.Internal;

namespace Morris.Ads
{
    /// <summary>
    /// Реклама с вознаграждением.
    /// </summary>
    /// <example>
    /// <code>
    /// var ad = new MorrisRewardedAd("rewarded_main");
    /// ad.Loaded   += a =&gt; a.Show();
    /// ad.Rewarded += (a, reward) =&gt; GiveCoins(reward.Amount);
    /// ad.Load();
    /// </code>
    /// </example>
    /// <remarks>
    /// Все события приходят в главном потоке Unity — из обработчиков можно
    /// свободно трогать сцену и интерфейс.
    /// </remarks>
    public sealed class MorrisRewardedAd : IDisposable
    {
        /// <summary>Объявление получено и готово к показу.</summary>
        public event Action<MorrisRewardedAd> Loaded;

        /// <summary>Не загрузилось. Проверьте <see cref="MorrisAdError.IsNoFill"/> — это норма.</summary>
        public event Action<MorrisRewardedAd, MorrisAdError> LoadFailed;

        /// <summary>Картинка пошла. С этого момента показ засчитан.</summary>
        public event Action<MorrisRewardedAd> Shown;

        /// <summary>Показать не удалось. Вознаграждение не выдаётся.</summary>
        public event Action<MorrisRewardedAd, string> ShowFailed;

        public event Action<MorrisRewardedAd> Clicked;

        /// <summary>Досмотрено до конца — выдавайте вознаграждение здесь.</summary>
        public event Action<MorrisRewardedAd, MorrisReward> Rewarded;

        /// <summary>Экран закрыт. Приходит и после досмотра, и после пропуска.</summary>
        public event Action<MorrisRewardedAd> Dismissed;

        private readonly NativeAdUnit _unit;

        public MorrisRewardedAd(string placement)
        {
            _unit = new NativeAdUnit(
                AndroidBridge.RewardedClass,
                AndroidBridge.RewardedListener,
                placement)
            {
                Loaded = () => Loaded?.Invoke(this),
                LoadFailed = e => LoadFailed?.Invoke(this, e),
                Shown = () => Shown?.Invoke(this),
                ShowFailed = m => ShowFailed?.Invoke(this, m),
                Clicked = () => Clicked?.Invoke(this),
                Rewarded = r => Rewarded?.Invoke(this, r),
                Dismissed = () => Dismissed?.Invoke(this),
            };
        }

        /// <summary>Готово ли объявление к показу прямо сейчас.</summary>
        public bool IsReady => _unit.IsReady;

        public void Load() => _unit.Load();

        public void Show() => _unit.Show();

        /// <summary>Освободить. После вызова события не приходят.</summary>
        public void Destroy() => _unit.Destroy();

        void IDisposable.Dispose() => Destroy();
    }
}
