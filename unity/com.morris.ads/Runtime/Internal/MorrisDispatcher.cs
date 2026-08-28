using System;
using System.Collections.Concurrent;
using UnityEngine;

namespace Morris.Ads.Internal
{
    /// <summary>
    /// Перекладывает вызовы из Java в главный поток Unity.
    ///
    /// Это не удобство, а обязательное условие. Нативный SDK зовёт слушателя на
    /// главном потоке Android, а это НЕ главный поток Unity — их два разных.
    /// Обращение к Unity API с чужого потока роняет игру, причём не сразу и не
    /// каждый раз, что делает такую ошибку особенно неприятной.
    /// </summary>
    internal sealed class MorrisDispatcher : MonoBehaviour
    {
        private static MorrisDispatcher _instance;
        private static readonly ConcurrentQueue<Action> Queue = new ConcurrentQueue<Action>();

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        private static void Bootstrap()
        {
            if (_instance != null) return;

            var host = new GameObject("MorrisAds");
            host.hideFlags = HideFlags.HideInHierarchy;
            DontDestroyOnLoad(host);
            _instance = host.AddComponent<MorrisDispatcher>();
        }

        /// <summary>Выполнить в главном потоке Unity на ближайшем кадре.</summary>
        internal static void Post(Action action)
        {
            if (action == null) return;
            Queue.Enqueue(action);
        }

        private void Update()
        {
            while (Queue.TryDequeue(out var action))
            {
                // Исключение в колбэке игры не должно останавливать очередь:
                // иначе одна ошибка в обработчике награды съедает все
                // последующие события.
                try { action(); }
                catch (Exception e) { Debug.LogException(e); }
            }
        }
    }
}
