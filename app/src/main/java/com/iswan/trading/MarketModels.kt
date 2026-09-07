package com.iswan.trading

data class Market(val symbol: String, val name: String, val category: String)

object MarketCatalog {
    val markets: List<Market> = listOf(
        Market("XAUUSD", "Gold / US Dollar", "Forex/Metal"),
        Market("EURUSD", "Euro / US Dollar", "Forex"),
        Market("GBPUSD", "British Pound / US Dollar", "Forex"),
        Market("USDJPY", "US Dollar / Japanese Yen", "Forex"),
        Market("BTCUSDT", "Bitcoin / Tether", "Crypto"),
        Market("ETHUSDT", "Ethereum / Tether", "Crypto"),
        Market("NAS100", "Nasdaq 100", "Index"),
        Market("US30", "Dow Jones 30", "Index")
    )
}
