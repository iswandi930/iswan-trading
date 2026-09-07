package com.iswan.trading

data class Market(val symbol: String, val name: String, val category: String)

object MarketCatalog {
    // Symbols intentionally match the Alpaca backend mapping.
    val markets: List<Market> = listOf(
        Market("AAPL", "Apple", "US Stocks"),
        Market("MSFT", "Microsoft", "US Stocks"),
        Market("NVDA", "NVIDIA", "US Stocks"),
        Market("AMZN", "Amazon", "US Stocks"),
        Market("META", "Meta Platforms", "US Stocks"),
        Market("TSLA", "Tesla", "US Stocks"),
        Market("SPY", "S&P 500 ETF", "US ETF"),
        Market("QQQ", "Nasdaq 100 ETF", "US ETF"),
        Market("BTCUSD", "Bitcoin / US Dollar", "Crypto"),
        Market("ETHUSD", "Ethereum / US Dollar", "Crypto")
    )
}
