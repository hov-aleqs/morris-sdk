namespace Morris.Ads
{
    /// <summary>
    /// Почему не удалось получить рекламу.
    /// </summary>
    /// <remarks>
    /// Разбирать ошибку следует по <see cref="Kind"/>, а не по тексту:
    /// текст написан для человека и может измениться, а вид — часть контракта.
    /// </remarks>
    public sealed class MorrisAdError
    {
        /// <summary>Рекламы нет. Это норма, а не сбой.</summary>
        public const string KindNoFill = "no_fill";

        /// <summary>Сеть недоступна или запрос не уложился в таймаут.</summary>
        public const string KindNetwork = "network";

        /// <summary>Бэкенд ответил не 2xx. Код в <see cref="HttpCode"/>.</summary>
        public const string KindServer = "server";

        /// <summary>Ответ пришёл, но показать его нельзя.</summary>
        public const string KindMalformed = "malformed";

        public string Kind { get; internal set; }
        public string Message { get; internal set; }

        /// <summary>Код ответа бэкенда. Ноль для всех видов, кроме <see cref="KindServer"/>.</summary>
        public int HttpCode { get; internal set; }

        /// <summary>
        /// Рекламы просто нет.
        ///
        /// Отдельное свойство потому, что это самый частый исход и обрабатывать
        /// его надо не как ошибку: показывать нечего, игра идёт дальше.
        /// </summary>
        public bool IsNoFill => Kind == KindNoFill;

        public override string ToString() =>
            HttpCode != 0 ? Kind + " (" + HttpCode + "): " + Message : Kind + ": " + Message;
    }
}
