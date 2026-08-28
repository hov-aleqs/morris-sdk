#if UNITY_ANDROID && !UNITY_EDITOR
using System;
using UnityEngine;

namespace Morris.Ads.Internal
{
    /// <summary>
    /// Реализация Java-интерфейса слушателя на стороне C#.
    ///
    /// Два правила, нарушение любого из которых ломает игру не сразу:
    ///
    /// 1. Значения из аргументов достаются ЗДЕСЬ И СЕЙЧАС. Java-объекты живут
    ///    только на время вызова; отложить обращение к ним на кадр вперёд —
    ///    значит однажды прочитать освобождённую память.
    /// 2. Обработчики игры вызываются не отсюда, а через диспетчер: этот метод
    ///    выполняется на потоке Android, а не Unity.
    /// </summary>
    internal sealed class AdListenerProxy : AndroidJavaProxy
    {
        private readonly NativeAdUnit _unit;

        internal AdListenerProxy(string interfaceName, NativeAdUnit unit) : base(interfaceName)
        {
            _unit = unit;
        }

        public override AndroidJavaObject Invoke(string methodName, AndroidJavaObject[] javaArgs)
        {
            try
            {
                switch (methodName)
                {
                    case "onLoaded":
                        MorrisDispatcher.Post(_unit.RaiseLoaded);
                        break;

                    case "onShown":
                        MorrisDispatcher.Post(_unit.RaiseShown);
                        break;

                    case "onClicked":
                        MorrisDispatcher.Post(_unit.RaiseClicked);
                        break;

                    case "onDismissed":
                        MorrisDispatcher.Post(_unit.RaiseDismissed);
                        break;

                    case "onLoadFailed":
                    {
                        var error = ReadError(Arg(javaArgs, 1));
                        MorrisDispatcher.Post(() => _unit.RaiseLoadFailed(error));
                        break;
                    }

                    case "onShowFailed":
                    {
                        var message = AndroidBridge.StringOf(Arg(javaArgs, 1));
                        MorrisDispatcher.Post(() => _unit.RaiseShowFailed(message));
                        break;
                    }

                    case "onRewarded":
                    {
                        var reward = ReadReward(Arg(javaArgs, 1));
                        MorrisDispatcher.Post(() => _unit.RaiseRewarded(reward));
                        break;
                    }
                }
            }
            catch (Exception e)
            {
                // Исключение, вылетевшее отсюда в Java, обрывает показ.
                Debug.LogException(e);
            }

            // Все методы слушателя объявлены как void.
            return null;
        }

        private static AndroidJavaObject Arg(AndroidJavaObject[] args, int index) =>
            args != null && args.Length > index ? args[index] : null;

        private static MorrisAdError ReadError(AndroidJavaObject java)
        {
            var error = new MorrisAdError
            {
                Kind = MorrisAdError.KindMalformed,
                Message = string.Empty,
            };
            if (java == null) return error;

            // Вид берём строкой, а не по имени класса: имена классов переживают
            // обфускацию не всегда, а эта строка — часть контракта SDK.
            error.Kind = java.Call<string>("getKind");
            error.Message = java.Call<string>("getMessage");
            if (error.Kind == MorrisAdError.KindServer)
            {
                error.HttpCode = java.Call<int>("getCode");
            }
            return error;
        }

        private static MorrisReward ReadReward(AndroidJavaObject java)
        {
            if (java == null) return new MorrisReward(0, string.Empty);
            return new MorrisReward(java.Call<int>("getAmount"), java.Call<string>("getCurrency"));
        }
    }
}
#endif
