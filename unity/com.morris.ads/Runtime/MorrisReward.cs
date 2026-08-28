namespace Morris.Ads
{
    /// <summary>Вознаграждение за досмотр.</summary>
    public readonly struct MorrisReward
    {
        public readonly int Amount;
        public readonly string Currency;

        public MorrisReward(int amount, string currency)
        {
            Amount = amount;
            Currency = currency ?? string.Empty;
        }

        public override string ToString() => Amount + " " + Currency;
    }
}
