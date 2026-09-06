package com.iswan.trading

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class MarketQuote(val price: Double, val changePercent: Double?)

class MarketRepository {
    private val client = OkHttpClient()

    suspend fun getPrices(symbols: List<String>): Map<String, MarketQuote> = coroutineScope {
        symbols.map { symbol ->
            async(Dispatchers.IO) { symbol to fetchQuote(symbol) }
        }.awaitAll().toMap().filterValues { it != null }.mapValues { it.value!! }
    }

    private fun fetchQuote(symbol: String): MarketQuote? {
        return try {
            val provider = MarketSymbolMapper.providerSymbol(symbol)
            val url = "https://query1.finance.yahoo.com/v8/finance/chart/$provider?range=1d&interval=1m"
            val request = Request.Builder().url(url).header("User-Agent", "IswanTrading/0.2").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val result = JSONObject(body).getJSONObject("chart").getJSONArray("result").optJSONObject(0) ?: return null
                val meta = result.getJSONObject("meta")
                val price = meta.optDouble("regularMarketPrice", Double.NaN)
                    .takeUnless { it.isNaN() } ?: return null
                val previous = meta.optDouble("previousClose", Double.NaN).takeUnless { it.isNaN() }
                MarketQuote(price, previous?.let { if (it != 0.0) (price - it) / it * 100.0 else null })
            }
        } catch (_: Exception) {
            null
        }
    }
}
