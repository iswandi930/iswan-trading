package com.iswan.trading

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class MarketRepository {
    private val client = OkHttpClient()

    suspend fun getPrices(symbols: List<String>): Map<String, Double> = withContext(Dispatchers.IO) {
        symbols.associateWith { symbol -> fetchPrice(symbol) }.filterValues { it != null }.mapValues { it.value!! }
    }

    private fun fetchPrice(symbol: String): Double? {
        return try {
            val url = "https://query1.finance.yahoo.com/v8/finance/chart/$symbol=X?range=1d&interval=1m"
            val request = Request.Builder().url(url).header("User-Agent", "IswanTrading/0.1").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val result = JSONObject(body).getJSONObject("chart").getJSONArray("result").optJSONObject(0) ?: return null
                result.getJSONObject("meta").optDouble("regularMarketPrice", Double.NaN).takeUnless { it.isNaN() }
            }
        } catch (_: Exception) {
            null
        }
    }
}
