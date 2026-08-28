using Morris.Ads;
using UnityEngine;
using UnityEngine.UI;

/// <summary>
/// Минимальная интеграция целиком.
///
/// Повесьте на объект в сцене и укажите две кнопки. Больше для работы рекламы
/// ничего не нужно.
/// </summary>
public sealed class MorrisQuickStart : MonoBehaviour
{
    [Tooltip("Адрес вашего бэкенда")]
    public string Endpoint = "https://ads.example.com/api/ad";

    public Button RewardedButton;
    public Button InterstitialButton;
    public Text Status;

    private MorrisRewardedAd _rewarded;
    private MorrisInterstitialAd _interstitial;

    private void Start()
    {
        MorrisAds.Initialize(Endpoint);
        Say(MorrisAds.IsSupported
            ? "Готово. Нативный SDK " + MorrisAds.NativeVersion
            : "Не Android — реклама показываться не будет");

        if (RewardedButton != null) RewardedButton.onClick.AddListener(ShowRewarded);
        if (InterstitialButton != null) InterstitialButton.onClick.AddListener(ShowInterstitial);
    }

    private void ShowRewarded()
    {
        Say("Загружаю…");
        _rewarded?.Destroy();
        _rewarded = new MorrisRewardedAd("rewarded_main");

        _rewarded.Loaded += ad => { Say("Показываю"); ad.Show(); };
        _rewarded.LoadFailed += (ad, error) =>
            // Отсутствие рекламы — обычный исход, а не сбой: игра идёт дальше.
            Say(error.IsNoFill ? "Рекламы сейчас нет" : "Не загрузилось: " + error);
        _rewarded.Rewarded += (ad, reward) => Say("Начислено: " + reward);
        _rewarded.Dismissed += ad => Say("Закрыто");

        _rewarded.Load();
    }

    private void ShowInterstitial()
    {
        Say("Загружаю…");
        _interstitial?.Destroy();
        _interstitial = new MorrisInterstitialAd("interstitial_main");

        _interstitial.Loaded += ad => { Say("Показываю"); ad.Show(); };
        _interstitial.LoadFailed += (ad, error) =>
            Say(error.IsNoFill ? "Рекламы сейчас нет" : "Не загрузилось: " + error);
        _interstitial.Dismissed += ad => Say("Закрыто");

        _interstitial.Load();
    }

    private void Say(string text)
    {
        if (Status != null) Status.text = text;
        Debug.Log("[Morris] " + text);
    }

    private void OnDestroy()
    {
        _rewarded?.Destroy();
        _interstitial?.Destroy();
    }
}
