package com.iswan.trading

data class Market(val symbol: String, val name: String, val category: String)

object MarketCatalog {
    // Familiar MT4-style symbols mapped by the backend to Twelve Data symbols.
    val markets: List<Market> = listOf(
        Market("XAUUSD", "Gold / US Dollar", "Metals"),
        Market("EURUSD", "Euro / US Dollar", "Forex"),
        Market("GBPUSD", "British Pound / US Dollar", "Forex"),
        Market("USDJPY", "US Dollar / Japanese Yen", "Forex"),
        Market("USDCHF", "US Dollar / Swiss Franc", "Forex"),
        Market("AUDUSD", "Australian Dollar / US Dollar", "Forex"),
        Market("USDCAD", "US Dollar / Canadian Dollar", "Forex"),
        Market("NZDUSD", "New Zealand Dollar / US Dollar", "Forex"),
        Market("EURGBP", "Euro / British Pound", "Forex"),
        Market("EURJPY", "Euro / Japanese Yen", "Forex"),
        Market("GBPJPY", "British Pound / Japanese Yen", "Forex"),
        Market("AUDJPY", "Australian Dollar / Japanese Yen", "Forex"),
        Market("EURAUD", "Euro / Australian Dollar", "Forex"),
        Market("EURCHF", "Euro / Swiss Franc", "Forex"),
        Market("GBPCHF", "British Pound / Swiss Franc", "Forex"),
        Market("AUDCAD", "Australian Dollar / Canadian Dollar", "Forex"),
        Market("AUDCHF", "Australian Dollar / Swiss Franc", "Forex"),
        Market("CADJPY", "Canadian Dollar / Japanese Yen", "Forex"),
        Market("CHFJPY", "Swiss Franc / Japanese Yen", "Forex"),
        Market("NZDJPY", "New Zealand Dollar / Japanese Yen", "Forex"),
        Market("NZDCHF", "New Zealand Dollar / Swiss Franc", "Forex"),
        Market("USOIL", "WTI Crude Oil", "Commodities"),
        Market("UKOIL", "Brent Crude Oil", "Commodities"),
        Market("BTCUSD", "Bitcoin / US Dollar", "Crypto"),
        Market("ETHUSD", "Ethereum / US Dollar", "Crypto"),
        Market("AAPL", "Apple", "US Stocks"),
        Market("MSFT", "Microsoft", "US Stocks"),
        Market("NVDA", "NVIDIA", "US Stocks"),
        Market("AMZN", "Amazon", "US Stocks"),
        Market("META", "Meta Platforms", "US Stocks"),
        Market("TSLA", "Tesla", "US Stocks"),
        Market("SPY", "S&P 500 ETF", "US ETF"),
        Market("QQQ", "Nasdaq 100 ETF", "US ETF")
    )
}
