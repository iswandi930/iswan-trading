package com.iswan.trading

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class Candle(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

object MarketSymbolMapper {
    fun providerSymbol(symbol: String): String = when (symbol.uppercase()) {
        "XAUUSD" -> "XAUUSD=X"
        "EURUSD" -> "EURUSD=X"
        "GBPUSD" -> "GBPUSD=X"
        "USDJPY" -> "JPY=X"
        "BTCUSDT" -> "BTC-USD"
        "ETHUSDT" -> "ETH-USD"
        "NAS100" -> "^NDX"
        "US30" -> "^DJI"
        else -> symbol
    }
}

class CandleRepository {
    private val client = OkHttpClient.Builder()
        .callTimeout(java.time.Duration.ofSeconds(8))
        .connectTimeout(java.time.Duration.ofSeconds(5))
        .readTimeout(java.time.Duration.ofSeconds(8))
        .build()

    suspend fun getCandles(symbols: List<String>, range: String = "1d", interval: String = "5m"): Map<String, List<Candle>> = coroutineScope {
        symbols.map { symbol ->
            async(Dispatchers.IO) { symbol to fetch(symbol, range, interval) }
        }.awaitAll().toMap().filterValues { it.isNotEmpty() }
    }

    suspend fun getCandles(symbol: String, range: String = "1d", interval: String = "5m"): List<Candle> = withContext(Dispatchers.IO) {
        fetch(symbol, range, interval)
    }

    private fun fetch(symbol: String, range: String, interval: String): List<Candle> {
        return try {
            val provider = MarketSymbolMapper.providerSymbol(symbol)
            val url = "https://query1.finance.yahoo.com/v8/finance/chart/$provider?range=$range&interval=$interval&events=history"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "IswanTrading/0.3")
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                val result = JSONObject(body).getJSONObject("chart").getJSONArray("result").optJSONObject(0) ?: return emptyList()
                val timestamps = result.optJSONArray("timestamp") ?: return emptyList()
                val quote = result.getJSONObject("indicators").getJSONArray("quote").optJSONObject(0) ?: return emptyList()
                val open = quote.optJSONArray("open") ?: return emptyList()
                val high = quote.optJSONArray("high") ?: return emptyList()
                val low = quote.optJSONArray("low") ?: return emptyList()
                val close = quote.optJSONArray("close") ?: return emptyList()
                val volume = quote.optJSONArray("volume")
                buildList {
                    for (i in 0 until timestamps.length()) {
                        val o = open.optDouble(i, Double.NaN)
                        val h = high.optDouble(i, Double.NaN)
                        val l = low.optDouble(i, Double.NaN)
                        val c = close.optDouble(i, Double.NaN)
                        if (o.isFinite() && h.isFinite() && l.isFinite() && c.isFinite() && h >= maxOf(o, c) && l <= minOf(o, c)) {
                            add(Candle(timestamps.optLong(i) * 1000L, o, h, l, c, volume?.optDouble(i, 0.0) ?: 0.0))
                        }
                    }
                }.takeLast(160)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
