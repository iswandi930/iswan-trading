package com.iswan.trading

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Duration

data class MarketQuote(val price: Double, val changePercent: Double?, val marketTime: Long?)

class MarketRepository {
    private val client = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(6))
        .connectTimeout(Duration.ofSeconds(4))
        .readTimeout(Duration.ofSeconds(6))
        .build()

    suspend fun getPrices(symbols: List<String>): Map<String, MarketQuote> = coroutineScope {
        symbols.map { symbol ->
            async(Dispatchers.IO) { symbol to fetchQuote(symbol) }
        }.awaitAll().mapNotNull { (symbol, quote) -> quote?.let { symbol to it } }.toMap()
    }

    private fun fetchQuote(symbol: String): MarketQuote? = try {
        val provider = MarketSymbolMapper.providerSymbol(symbol)
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$provider?range=1d&interval=1m&events=history"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "IswanTrading/0.3")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val result = JSONObject(body).getJSONObject("chart").getJSONArray("result").optJSONObject(0) ?: return null
            val meta = result.getJSONObject("meta")
            val price = meta.optDouble("regularMarketPrice", Double.NaN).takeUnless { it.isNaN() } ?: return null
            val previous = meta.optDouble("previousClose", Double.NaN).takeUnless { it.isNaN() }
            val marketTime = meta.optLong("regularMarketTime", 0L).takeIf { it > 0L }?.times(1000L)
            MarketQuote(price, previous?.takeIf { it != 0.0 }?.let { (price - it) / it * 100.0 }, marketTime)
        }
    } catch (_: Exception) {
        null
    }

    fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        client.cache?.close()
    }
}
